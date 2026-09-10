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

        String normalized = removeComments(sql, dialect).strip();

        if (normalized.isBlank()) {
            return forbidden("UNKNOWN", false, "SQL must contain a statement");
        }

        boolean multiStatement = hasMultipleStatements(normalized);
        String statementType = firstStatementKeyword(normalized);
        boolean triggerDefinition = isCreateTriggerStatement(normalized);

        if (isForbiddenAdministrativeStatement(normalized, statementType)) {
            return forbidden(statementType + "_ADMIN", false,
                statementType + " administrative statements are not allowed.");
        }

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

        if (!triggerDefinition && containsAiSeparatedStatement(normalized)) {
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

        // SQLite query-plan inspection is read-only and bounded like a SELECT (W3.1).
        if ("EXPLAIN".equals(statementType) && dialect.family() == DatabaseDialect.Family.SQLITE) {
            return new SqlRiskAnalysis(
                SqlRiskLevel.LOW,
                true,
                false,
                false,
                statementType,
                List.of("Read-only query plan inspection.")
            );
        }

        if (isWholeDatabaseDrop(statementType, normalized)) {
            return new SqlRiskAnalysis(
                    SqlRiskLevel.HIGH,
                    true,
                    true,
                    false,
                    statementType,
                    List.of(
                            "This statement deletes an entire database and cannot be undone.",
                            "Confirm that a recent backup exists before executing."
                    )
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

            case "INSERT" -> new SqlRiskAnalysis(
                    SqlRiskLevel.MEDIUM,
                    true,
                    true,
                    false,
                    statementType,
                    List.of("This statement modifies data.")
            );

            case "UPDATE", "DELETE" -> new SqlRiskAnalysis(
                    SqlRiskLevel.HIGH,
                    true,
                    true,
                    false,
                    statementType,
                    List.of("This statement modifies or deletes existing data and requires explicit confirmation.")
            );

            case "CREATE", "ALTER" -> new SqlRiskAnalysis(
                    SqlRiskLevel.HIGH,
                    true,
                    true,
                    false,
                    statementType,
                    List.of("This statement modifies database schema.")
            );

            case "DROP", "TRUNCATE" -> new SqlRiskAnalysis(
                    SqlRiskLevel.HIGH,
                    true,
                    true,
                    false,
                    statementType,
                    List.of(statementType + " is irreversible and requires explicit confirmation.")
            );

            case "GRANT", "REVOKE" -> new SqlRiskAnalysis(
                    SqlRiskLevel.HIGH,
                    true,
                    true,
                    false,
                    statementType,
                    List.of(statementType + " changes database permissions and requires explicit confirmation.")
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
        String tokens = maskQuotedText(sql).toUpperCase(Locale.ROOT);
        // Write-class file operations stay forbidden on every dialect: they place data on the
        // machine outside anything the preview can show, regardless of the safety mode.
        if (MYSQL_FILE_OUTPUT.matcher(tokens).find()) {
            return forbidden(statementType, false,
                    "Writing query output to files (INTO OUTFILE/DUMPFILE) is not allowed.");
        }
        if (isDestructiveCopyTarget(tokens)) {
            return forbidden(statementType, false,
                    "Copying data to files or programs (COPY ... TO FILE/PROGRAM) is not allowed.");
        }
        if (dialect.family() == DatabaseDialect.Family.MYSQL && "SELECT".equals(statementType)
                && MYSQL_LOCKING_SELECT.matcher(tokens).find()) {
            return forbidden(statementType, false, "MySQL locking queries are not allowed.");
        }
        if (dialect.family() == DatabaseDialect.Family.MYSQL
                && MYSQL_DELAY_LOCK_FUNCTION.matcher(tokens).find()) {
            return forbidden(statementType, false, "MySQL delay or lock functions are not allowed.");
        }
        // Read-class file functions expose local file content; allowed only behind an explicit
        // confirmation because the file belongs to the user's own machine.
        if (fileReadFunctionPattern(dialect).matcher(tokens).find()) {
            return new SqlRiskAnalysis(
                    SqlRiskLevel.HIGH,
                    true,
                    true,
                    false,
                    statementType,
                    List.of("This statement reads local files through a database file function "
                            + "and requires explicit confirmation.")
            );
        }
        if (("COPY".equals(statementType))
                && (dialect.family() == DatabaseDialect.Family.POSTGRESQL
                    || dialect.family() == DatabaseDialect.Family.GENERIC)) {
            return new SqlRiskAnalysis(
                    SqlRiskLevel.HIGH,
                    true,
                    true,
                    false,
                    statementType,
                    List.of("COPY imports data from a file or standard input "
                            + "and requires explicit confirmation.")
            );
        }
        return null;
    }

    private static Pattern fileReadFunctionPattern(DatabaseDialect dialect) {
        return switch (dialect.family()) {
            case MYSQL -> Pattern.compile("\\bLOAD_FILE\\s*\\(");
            case H2 -> Pattern.compile("\\b(FILE_READ|CSVREAD)\\s*\\(");
            case DUCKDB -> Pattern.compile("\\b(READ_TEXT|READ_CSV|READ_JSON|READ_PARQUET)\\s*\\(");
            case POSTGRESQL -> Pattern.compile("\\b(PG_READ_FILE|PG_READ_BINARY_FILE)\\s*\\(");
            case GENERIC -> Pattern.compile(
                    "\\b(LOAD_FILE|FILE_READ|CSVREAD|READ_TEXT|READ_CSV|READ_JSON|READ_PARQUET"
                            + "|PG_READ_FILE|PG_READ_BINARY_FILE)\\s*\\(");
            default -> Pattern.compile("\\bLOAD_FILE\\s*\\(");
        };
    }

    private static boolean isForbiddenAdministrativeStatement(String sql, String statementType) {
        String tokens = maskQuotedText(sql).toUpperCase(Locale.ROOT);
        return switch (statementType) {
            case "DROP" -> tokens.matches("(?s)^DROP\\s+(USER|ROLE)\\b.*");
            case "CREATE" -> tokens.matches("(?s)^CREATE\\s+(USER|ROLE)\\b.*");
            case "ALTER" -> tokens.matches("(?s)^ALTER\\s+USER\\b.*");
            default -> false;
        };
    }

    /**
     * Decides whether a COPY ... TO target writes outside the session. Quoted targets are
     * masked out of {@code tokens}, so an invisible target after TO means a quoted file path;
     * only STDOUT stays out of the forbidden class (it writes no file).
     */
    private static boolean isDestructiveCopyTarget(String tokens) {
        java.util.regex.Matcher matcher = COPY_TO_TARGET.matcher(tokens);
        if (!matcher.find()) {
            return false;
        }
        String target = matcher.group(1);
        return target.isEmpty() || target.equals("PROGRAM") || target.equals("FILE");
    }

    private static boolean isWholeDatabaseDrop(String statementType, String sql) {
        if (!"DROP".equals(statementType)) {
            return false;
        }
        String tokens = maskQuotedText(sql).toUpperCase(Locale.ROOT);
        return tokens.matches("(?s)^DROP\\s+(DATABASE|SCHEMA)\\b.*");
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
    private static final Pattern MYSQL_DELAY_LOCK_FUNCTION = Pattern.compile(
            "\\b(SLEEP|BENCHMARK|GET_LOCK|RELEASE_LOCK)\\s*\\("
    );
    private static final Pattern COPY_TO_TARGET = Pattern.compile("\\bCOPY\\b(?s).*\\bTO\\s+(\\S*)\\s*$");

    private boolean containsAiSeparatedStatement(String sql) {
        return AI_MULTI_STATEMENT.matcher(sql).find();
    }

    private boolean hasMultipleStatements(String sql) {
        if (isCreateTriggerStatement(sql)) {
            // A trigger body legitimately contains semicolon-separated statements;
            // only content after the body's terminating END makes it multi-statement.
            return contentAfterTriggerBody(sql);
        }
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

    /** True when the (comment-stripped) text is a single CREATE TRIGGER definition. */
    private static boolean isCreateTriggerStatement(String normalizedSql) {
        String tokens = maskQuotedText(normalizedSql).toUpperCase(Locale.ROOT).trim();
        return tokens.matches("(?s)^CREATE\\s+(?:TEMP|TEMPORARY)?\\s*TRIGGER\\b.*");
    }

    /**
     * Detects statements appended after a trigger definition. Quotes are masked first, so
     * the first standalone END that follows the body-opening BEGIN terminates the trigger.
     */
    private static boolean contentAfterTriggerBody(String sql) {
        String tokens = maskQuotedText(sql).toUpperCase(Locale.ROOT);
        java.util.regex.Matcher header = java.util.regex.Pattern
            .compile("(?s)^CREATE\\s+(?:TEMP|TEMPORARY)?\\s*TRIGGER\\b.*?\\bBEGIN\\b")
            .matcher(tokens);
        if (!header.find()) {
            // Malformed header without a body; fall back to plain semicolon counting.
            return true;
        }
        String tail = tokens.substring(header.end());
        java.util.regex.Matcher end = java.util.regex.Pattern.compile("\\bEND\\b").matcher(tail);
        if (!end.find()) {
            return false;
        }
        String remainder = tail.substring(end.end()).strip();
        if (remainder.startsWith(";")) {
            remainder = remainder.substring(1).strip();
        }
        return !remainder.isEmpty();
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
     * Resolves the risk-bearing statement keyword, seeing through CTE headers
     * (WITH name AS (...), ...) and leading parenthesized compound groups
     * ((SELECT ...) UNION (SELECT ...)) instead of rejecting them as unsupported.
     */
    private String firstStatementKeyword(String sql) {
        String stripped = sql.strip();
        if (stripped.isEmpty()) {
            return "UNKNOWN";
        }
        if (stripped.charAt(0) == '(') {
            int close = endOfBalancedGroup(stripped, 0);
            if (close < 0) {
                return "UNKNOWN";
            }
            String innerVerb = leadingVerb(stripped.substring(1, close));
            String remainder = stripped.substring(close + 1).strip();
            if (remainder.isEmpty()) {
                return innerVerb;
            }
            String following = leadingVerb(remainder);
            return switch (following) {
                case "UNION", "INTERSECT", "EXCEPT", "ORDER", "LIMIT", "OFFSET", "FETCH" -> innerVerb;
                case "SELECT", "INSERT", "UPDATE", "DELETE", "VALUES" -> following;
                default -> "UNKNOWN";
            };
        }
        if (startsWithWord(stripped, "WITH")) {
            return mainVerbAfterCtes(stripped);
        }
        return firstKeyword(stripped);
    }

    private String mainVerbAfterCtes(String sql) {
        int index = "WITH".length();
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (Character.isWhitespace(current) || current == ',') {
                index++;
                continue;
            }
            if (current == '(') {
                index = endOfBalancedGroup(sql, index);
                if (index < 0) {
                    return "WITH";
                }
                index++;
                continue;
            }
            if (Character.isLetter(current)) {
                String word = leadingVerb(sql.substring(index));
                index += word.length();
                if (isRiskBearingVerb(word)) {
                    return word;
                }
                continue;
            }
            return "WITH";
        }
        return "WITH";
    }

    private static boolean isRiskBearingVerb(String word) {
        return switch (word) {
            case "SELECT", "INSERT", "UPDATE", "DELETE", "VALUES",
                 "DROP", "TRUNCATE", "ALTER", "CREATE", "GRANT", "REVOKE" -> true;
            default -> false;
        };
    }

    private static String leadingVerb(String sql) {
        int index = 0;
        while (index < sql.length() && !Character.isLetter(sql.charAt(index))) {
            index++;
        }
        int start = index;
        while (index < sql.length()
                && (Character.isLetterOrDigit(sql.charAt(index)) || sql.charAt(index) == '_')) {
            index++;
        }
        if (index == start) {
            return "UNKNOWN";
        }
        return sql.substring(start, index).toUpperCase(Locale.ROOT);
    }

    private static boolean startsWithWord(String sql, String word) {
        if (!sql.regionMatches(true, 0, word, 0, word.length())) {
            return false;
        }
        if (sql.length() == word.length()) {
            return true;
        }
        char following = sql.charAt(word.length());
        return !Character.isLetterOrDigit(following) && following != '_';
    }

    private static int endOfBalancedGroup(String sql, int openIndex) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean backtickQuoted = false;
        int depth = 0;
        for (int index = openIndex; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (singleQuoted) {
                if (current == '\'') {
                    if (next == '\'') {
                        index++;
                    } else {
                        singleQuoted = false;
                    }
                }
                continue;
            }
            if (doubleQuoted) {
                if (current == '"') {
                    if (next == '"') {
                        index++;
                    } else {
                        doubleQuoted = false;
                    }
                }
                continue;
            }
            if (backtickQuoted) {
                if (current == '`') {
                    if (next == '`') {
                        index++;
                    } else {
                        backtickQuoted = false;
                    }
                }
                continue;
            }
            if (current == '\'') {
                singleQuoted = true;
            } else if (current == '"') {
                doubleQuoted = true;
            } else if (current == '`') {
                backtickQuoted = true;
            } else if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private String removeComments(String sql, DatabaseDialect dialect) {
        // MySQL-family servers (and unknown servers, conservatively) execute the content of
        // /*! ... */ comments, so that content must stay in the analyzed text. Stripping it
        // here would let hidden payloads pass analysis while the original text is executed.
        boolean keepExecutableComments = dialect.family() == DatabaseDialect.Family.MYSQL
                || dialect.family() == DatabaseDialect.Family.GENERIC;
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
                boolean executableComment = keepExecutableComments
                        && index + 2 < sql.length() && sql.charAt(index + 2) == '!';
                normalized.append(' ');
                index += 2;
                if (executableComment) {
                    index++;
                }
                while (index < sql.length()) {
                    char commentCurrent = sql.charAt(index);
                    char commentNext = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
                    if (commentCurrent == '*' && commentNext == '/') {
                        index++;
                        break;
                    }
                    if (executableComment || commentCurrent == '\n' || commentCurrent == '\r') {
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
