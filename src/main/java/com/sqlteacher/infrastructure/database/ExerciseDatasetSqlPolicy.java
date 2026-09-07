package com.sqlteacher.infrastructure.database;

import com.sqlteacher.domain.SqlTeacherException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic whitelist for exercise dataset setup scripts. Imported or bundled dataset
 * SQL may only build tables and indexes and insert sample rows; every other statement type
 * (ATTACH, PRAGMA, DELETE, UPDATE, DROP, ALTER, ...) is rejected before any statement runs.
 * This is the shared gate for package self-test, session databases, and evaluation databases.
 */
final class ExerciseDatasetSqlPolicy {
    private ExerciseDatasetSqlPolicy() {
    }

    static void validate(String setupSql) {
        if (setupSql == null || setupSql.isBlank()) {
            throw violation("数据集 SQL 不能为空");
        }
        List<String> statements = SqlScriptSplitter.split(setupSql);
        if (statements.isEmpty()) {
            throw violation("数据集 SQL 不能为空");
        }
        for (int index = 0; index < statements.size(); index++) {
            List<String> keywords = leadingKeywords(statements.get(index));
            if (keywords.isEmpty()) {
                // The statement carries comments only; nothing to execute or classify.
                continue;
            }
            if (!allowed(keywords)) {
                throw violation(
                    "数据集 SQL 第 " + (index + 1) + " 条语句类型不被允许，仅支持 CREATE TABLE、CREATE INDEX 和 INSERT INTO"
                );
            }
        }
    }

    private static boolean allowed(List<String> keywords) {
        String first = keywords.get(0);
        if ("INSERT".equals(first)) {
            return keywords.size() >= 2 && "INTO".equals(keywords.get(1));
        }
        if ("CREATE".equals(first)) {
            if (keywords.size() < 2) {
                return false;
            }
            String second = keywords.get(1);
            if ("TABLE".equals(second) || "INDEX".equals(second)) {
                return true;
            }
            return "UNIQUE".equals(second) && keywords.size() >= 3 && "INDEX".equals(keywords.get(2));
        }
        return false;
    }

    /**
     * Returns the leading identifier keywords of the statement (uppercased, at most three),
     * skipping whitespace, comments, and quoted regions. Empty when the statement contains
     * no executable text.
     */
    private static List<String> leadingKeywords(String statement) {
        List<String> keywords = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = '\0';
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < statement.length(); index++) {
            char character = statement.charAt(index);
            char next = index + 1 < statement.length() ? statement.charAt(index + 1) : '\0';
            if (lineComment) {
                if (character == '\n' || character == '\r') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (character == '*' && next == '/') {
                    index++;
                    blockComment = false;
                }
                continue;
            }
            if (quote == '\0' && character == '-' && next == '-') {
                index++;
                lineComment = true;
                endKeyword(keywords, current);
                continue;
            }
            if (quote == '\0' && character == '/' && next == '*') {
                index++;
                blockComment = true;
                endKeyword(keywords, current);
                continue;
            }
            if (quote == '\0' && (character == '\'' || character == '"' || character == '`' || character == '[')) {
                quote = character == '[' ? ']' : character;
                endKeyword(keywords, current);
                continue;
            }
            if (quote != '\0') {
                if (character == quote) {
                    quote = '\0';
                }
                continue;
            }
            if (Character.isLetterOrDigit(character) || character == '_') {
                if (keywords.size() < 3) {
                    current.append(character);
                }
                continue;
            }
            endKeyword(keywords, current);
            if (keywords.size() >= 3) {
                break;
            }
        }
        endKeyword(keywords, current);
        return keywords.stream().map(value -> value.toUpperCase(Locale.ROOT)).toList();
    }

    private static void endKeyword(List<String> keywords, StringBuilder current) {
        if (keywords.size() < 3 && !current.isEmpty()) {
            keywords.add(current.toString());
        }
        current.setLength(0);
    }

    private static SqlTeacherException violation(String message) {
        return new SqlTeacherException("EXERCISE_DATASET_POLICY_VIOLATION", message);
    }
}
