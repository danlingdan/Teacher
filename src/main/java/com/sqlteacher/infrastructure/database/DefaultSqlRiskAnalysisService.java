package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.application.risk.SqlRiskLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class DefaultSqlRiskAnalysisService implements SqlRiskAnalysisService {
    @Override
    public SqlRiskAnalysis analyze(String sql) {

        if (sql == null || sql.isBlank()) {
            return forbidden("UNKNOWN", false, "SQL must not be blank");
        }

        String normalized = normalizeForAiPatterns(sql.strip());

        boolean multiStatement = hasMultipleStatements(normalized);
        String statementType = firstKeyword(normalized);

        if (multiStatement) {
            return new SqlRiskAnalysis(
                    SqlRiskLevel.HIGH,
                    false,
                    true,
                    true,
                    statementType,
                    List.of("Multiple SQL statements are not allowed.")
            );
        }

        if (containsAiSeparatedStatement(normalized)) {
            return new SqlRiskAnalysis(
                    SqlRiskLevel.HIGH,
                    false,
                    true,
                    true,
                    "MULTI_STATEMENT",
                    List.of("Multiple SQL statements detected. Execute only one statement at a time.")
            );
        }

        if (statementType.equals("DROP")
                && normalized.toUpperCase(Locale.ROOT).matches("DROP\\s+DATABASE\\b.*")) {
            return forbidden(
                    "DROP",
                    false,
                    "DROP DATABASE is not allowed."
            );
        }

        return switch (statementType) {

            case "SELECT" -> new SqlRiskAnalysis(
                    SqlRiskLevel.LOW,
                    true,
                    false,
                    false,
                    statementType,
                    List.of("Read-only query.")
            );

            case "INSERT", "UPDATE", "DELETE" -> new SqlRiskAnalysis(
                    SqlRiskLevel.MEDIUM,
                    true,
                    true,
                    false,
                    statementType,
                    List.of("This statement modifies data.")
            );

            case "CREATE", "ALTER", "DROP", "TRUNCATE" -> new SqlRiskAnalysis(
                    SqlRiskLevel.HIGH,
                    true,
                    true,
                    false,
                    statementType,
                    List.of("This statement modifies database schema.")
            );

            default -> forbidden(
                    statementType,
                    false,
                    "Unsupported SQL statement."
            );
        };
    }

    private SqlRiskAnalysis forbidden(
            String statementType,
            boolean multiStatement,
            String reason
    ) {

        List<String> reasons = new ArrayList<>();
        reasons.add(reason);

        return new SqlRiskAnalysis(
                SqlRiskLevel.FORBIDDEN,
                false,
                false,
                multiStatement,
                statementType,
                reasons
        );
    }

    private static final Pattern AI_MULTI_STATEMENT = Pattern.compile(
            "(?is)\\R\\s*\\R\\s*(SELECT|INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE)\\b"
    );

    private boolean containsAiSeparatedStatement(String sql) {
        return AI_MULTI_STATEMENT.matcher(sql).find();
    }

    private boolean hasMultipleStatements(String sql) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;

        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';

            if (lineComment) {
                lineComment = current != '\n' && current != '\r';
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
                continue;
            }
            if (!singleQuoted && !doubleQuoted && current == '-' && next == '-') {
                lineComment = true;
                index++;
                continue;
            }
            if (!singleQuoted && !doubleQuoted && current == '/' && next == '*') {
                blockComment = true;
                index++;
                continue;
            }
            if (!doubleQuoted && current == '\'') {
                if (singleQuoted && next == '\'') {
                    index++;
                } else {
                    singleQuoted = !singleQuoted;
                }
                continue;
            }
            if (!singleQuoted && current == '"') {
                if (doubleQuoted && next == '"') {
                    index++;
                } else {
                    doubleQuoted = !doubleQuoted;
                }
                continue;
            }
            if (!singleQuoted && !doubleQuoted && current == ';'
                    && hasStatementContent(sql, index + 1)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasStatementContent(String sql, int start) {
        String remainder = sql.substring(start)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("--[^\\r\\n]*", "")
                .replace(";", "")
                .strip();
        return !remainder.isEmpty();
    }

    private String firstKeyword(String sql) {

        int index = 0;

        while (index < sql.length() && Character.isLetter(sql.charAt(index))) {
            index++;
        }

        if (index == 0) {
            return "UNKNOWN";
        }

        return sql.substring(0, index).toUpperCase(Locale.ROOT);
    }

    /**
     * Normalizes SQL to handle AI-generated patterns that might bypass detection.
     * Removes common AI-generated comments and normalizes whitespace while preserving
     * the semantic structure needed for risk analysis.
     */
    private String normalizeForAiPatterns(String sql) {

        String normalized = sql;

        normalized = normalized.replaceAll(
                "(?i)/\\*\\s*AI\\s*generated\\s*\\*/",
                "");

        normalized = normalized.replaceAll(
                "(?i)/\\*\\s*generated\\s+by\\s+AI\\s*\\*/",
                "");

        return normalized.strip();
    }
}
