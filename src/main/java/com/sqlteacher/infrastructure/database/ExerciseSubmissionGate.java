package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.connection.DatabaseDialect;
import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.domain.exercise.ExerciseType;

import java.util.List;
import java.util.Set;

/**
 * Single enforcement point for what a student may submit per exercise type, shared by the
 * session runner, the deterministic evaluator, and the package self-test so all three
 * paths reject exactly the same statements.
 *
 * <p>Safety boundary (v3.3 W1): QUERY exercises stay read-only single SELECT. STATE
 * accepts one statement of a teacher-declared type; SCRIPT accepts an ordered script of
 * common DML/DDL plus transaction control; TRIGGER accepts one CREATE TRIGGER. The
 * FORBIDDEN class (unsupported statements, file reads/writes, user and role
 * administration, multi-statement trickery) stays hard-blocked for every type; the
 * confirmation gate is exempted only because these run on one-shot isolated sandbox
 * databases, never on user databases.</p>
 */
final class ExerciseSubmissionGate {
    static final int MAX_SCRIPT_STATEMENTS = 100;
    private static final Set<String> TRANSACTION_STATEMENT_TYPES =
        Set.of("BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE", "END");
    private static final Set<String> SCRIPT_STATEMENT_TYPES =
        Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "DROP", "ALTER");

    private final SqlRiskAnalysisService riskAnalysisService;

    ExerciseSubmissionGate(SqlRiskAnalysisService riskAnalysisService) {
        this.riskAnalysisService = riskAnalysisService;
    }

    record Decision(boolean allowed, String reason, List<String> statements) {
        static Decision reject(String reason) {
            return new Decision(false, reason, List.of());
        }
    }

    Decision check(ExerciseType type, List<String> allowedStatementTypes, String submittedSql) {
        if (submittedSql == null || submittedSql.isBlank()) {
            return Decision.reject("提交内容不能为空。");
        }
        List<String> statements = SqlScriptSplitter.split(submittedSql);
        if (statements.isEmpty()) {
            return Decision.reject("提交内容不能为空。");
        }
        if (type == ExerciseType.STATE) {
            if (statements.size() != 1) {
                return Decision.reject("写操作题只接受单条语句，请去掉多余的分号或语句。");
            }
            return checkStatement(statements.getFirst(), effectiveTypes(allowedStatementTypes),
                "写操作题只允许 " + String.join("、", effectiveTypes(allowedStatementTypes)) + " 语句。");
        }
        if (type == ExerciseType.TRIGGER) {
            if (statements.size() != 1) {
                return Decision.reject("触发器题只接受单条 CREATE TRIGGER 语句。");
            }
            String statement = statements.getFirst();
            if (!isTriggerDefinition(statement)) {
                return Decision.reject("触发器题只接受单条 CREATE TRIGGER 语句。");
            }
            SqlRiskAnalysis risk = riskAnalysisService.analyze(statement, DatabaseDialect.SQLITE);
            Decision executable = executable(risk, "触发器题只接受可执行的 CREATE TRIGGER 语句。");
            if (!executable.allowed()) {
                return executable;
            }
            return new Decision(true, "", List.of(statement));
        }
        // SCRIPT
        if (statements.size() > MAX_SCRIPT_STATEMENTS) {
            return Decision.reject("脚本语句数超过上限（" + MAX_SCRIPT_STATEMENTS + " 条）。");
        }
        for (int index = 0; index < statements.size(); index++) {
            String statement = statements.get(index);
            SqlRiskAnalysis risk = riskAnalysisService.analyze(statement, DatabaseDialect.SQLITE);
            if (TRANSACTION_STATEMENT_TYPES.contains(risk.statementType())) {
                continue;
            }
            if (!risk.executable() || risk.multiStatement()
                || !SCRIPT_STATEMENT_TYPES.contains(risk.statementType())) {
                return Decision.reject(
                    "脚本第 " + (index + 1) + " 条语句不被允许：该语句类型在脚本题中禁止执行。");
            }
        }
        return new Decision(true, "", statements);
    }

    private Decision checkStatement(String statement, List<String> allowedTypes, String typeMessage) {
        SqlRiskAnalysis risk = riskAnalysisService.analyze(statement, DatabaseDialect.SQLITE);
        Decision executable = executable(risk, typeMessage);
        if (!executable.allowed()) {
            return executable;
        }
        if (!allowedTypes.contains(risk.statementType())) {
            return Decision.reject(typeMessage);
        }
        return new Decision(true, "", List.of(statement));
    }

    private Decision executable(SqlRiskAnalysis risk, String typeMessage) {
        if (!risk.executable() || risk.multiStatement()) {
            return Decision.reject("提交被安全策略拒绝：" + typeMessage);
        }
        return new Decision(true, "", List.of());
    }

    private static List<String> effectiveTypes(List<String> allowedStatementTypes) {
        return allowedStatementTypes.isEmpty() ? List.of("INSERT", "UPDATE", "DELETE") : allowedStatementTypes;
    }

    static boolean isTransactionStatementType(String statementType) {
        return TRANSACTION_STATEMENT_TYPES.contains(statementType);
    }

    static boolean isScriptStatementType(String statementType) {
        return SCRIPT_STATEMENT_TYPES.contains(statementType);
    }

    static boolean isTriggerDefinition(String statement) {
        String normalized = SqlStructureMatcher.normalize(statement);
        return normalized.matches("^CREATE\\s+(TEMP\\s+|TEMPORARY\\s+)?TRIGGER\\b.*");
    }
}
