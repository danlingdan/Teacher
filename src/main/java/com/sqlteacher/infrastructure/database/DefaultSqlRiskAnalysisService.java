package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.connection.DatabaseDialect;
import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.application.risk.SqlRiskLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class DefaultSqlRiskAnalysisService implements SqlRiskAnalysisService {
    @Override
    public SqlRiskAnalysis analyze(String sql) {
        return analyze(sql, DatabaseDialect.SQLITE);
    }

    @Override
    public SqlRiskAnalysis analyze(String sql, DatabaseDialect dialect) {
        Objects.requireNonNull(dialect, "dialect must not be null");

        if (sql == null || sql.isBlank()) {
            return forbidden("UNKNOWN", false, "SQL must not be blank");
        }

        String normalized = removeComments(sql).strip();

        if (normalized.isBlank()) {
            return forbidden("UNKNOWN", false, "SQL must contain a statement");
        }

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

        SqlRiskAnalysis dialectRisk = analyzeDialectSpecificRisk(normalized, statementType, dialect);
        if (dialectRisk != null) {
            return dialectRisk;
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

    private SqlRiskAnalysis analyzeDialectSpecificRisk(
            String sql,
            String statementType,
            DatabaseDialect dialect
    ) {
        if ((dialect != DatabaseDialect.MYSQL && dialect != DatabaseDialect.MARIADB)
                || !"SELECT".equals(statementType)) {
            return null;
        }
        String tokens = maskQuotedText(sql).toUpperCase(Locale.ROOT);
        if (MYSQL_FILE_OUTPUT.matcher(tokens).find()) {
            return forbidden("SELECT", false, "MySQL file output is not allowed.");
        }
        if (MYSQL_LOCKING_SELECT.matcher(tokens).find()) {
            return forbidden("SELECT", false, "MySQL locking queries are not allowed.");
        }
        if (MYSQL_DANGEROUS_FUNCTION.matcher(tokens).find()) {
            return forbidden("SELECT", false, "MySQL file, lock, or delay functions are not allowed.");
        }
        return null;
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
    private static final Pattern MYSQL_FILE_OUTPUT = Pattern.compile("\\bINTO\\s+(OUTFILE|DUMPFILE)\\b");
    private static final Pattern MYSQL_LOCKING_SELECT = Pattern.compile(
            "\\bFOR\\s+UPDATE\\b|\\bLOCK\\s+IN\\s+SHARE\\s+MODE\\b"
    );
    private static final Pattern MYSQL_DANGEROUS_FUNCTION = Pattern.compile(
            "\\b(SLEEP|BENCHMARK|GET_LOCK|RELEASE_LOCK|LOAD_FILE)\\s*\\("
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

    private String removeComments(String sql) {
        StringBuilder normalized = new StringBuilder(sql.length());
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean backtickQuoted = false;
        boolean bracketQuoted = false;

        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';

            if (!singleQuoted && !doubleQuoted && !backtickQuoted && !bracketQuoted
                    && current == '-' && next == '-') {
                normalized.append(' ');
                index += 2;
                while (index < sql.length() && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') {
                    index++;
                }
                if (index < sql.length()) {
                    normalized.append(sql.charAt(index));
                }
                continue;
            }
            if (!singleQuoted && !doubleQuoted && !backtickQuoted && !bracketQuoted
                    && current == '/' && next == '*') {
                normalized.append(' ');
                index += 2;
                while (index < sql.length()) {
                    char commentCurrent = sql.charAt(index);
                    char commentNext = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
                    if (commentCurrent == '*' && commentNext == '/') {
                        index++;
                        break;
                    }
                    if (commentCurrent == '\n' || commentCurrent == '\r') {
                        normalized.append(commentCurrent);
                    }
                    index++;
                }
                normalized.append(' ');
                continue;
            }

            normalized.append(current);

            if (singleQuoted && current == '\'' && next == '\'') {
                normalized.append(next);
                index++;
            } else if (doubleQuoted && current == '"' && next == '"') {
                normalized.append(next);
                index++;
            } else if (backtickQuoted && current == '`' && next == '`') {
                normalized.append(next);
                index++;
            } else if (!doubleQuoted && !backtickQuoted && !bracketQuoted && current == '\'') {
                singleQuoted = !singleQuoted;
            } else if (!singleQuoted && !backtickQuoted && !bracketQuoted && current == '"') {
                doubleQuoted = !doubleQuoted;
            } else if (!singleQuoted && !doubleQuoted && !bracketQuoted && current == '`') {
                backtickQuoted = !backtickQuoted;
            } else if (!singleQuoted && !doubleQuoted && !backtickQuoted && current == '[') {
                bracketQuoted = true;
            } else if (bracketQuoted && current == ']') {
                bracketQuoted = false;
            }
        }

        return normalized.toString();
    }

    private static String maskQuotedText(String sql) {
        StringBuilder masked = new StringBuilder(sql.length());
        char quote = '\0';
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (quote == '\0' && (current == '\'' || current == '"' || current == '`')) {
                quote = current;
                masked.append(' ');
            } else if (quote != '\0') {
                masked.append(' ');
                if (current == quote && next == quote) {
                    masked.append(' ');
                    index++;
                } else if (current == quote && (index == 0 || sql.charAt(index - 1) != '\\')) {
                    quote = '\0';
                }
            } else {
                masked.append(current);
            }
        }
        return masked.toString();
    }
}
