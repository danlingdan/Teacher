package com.sqlteacher.infrastructure.database;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Comment- and literal-aware matching of required SQL structure keywords, shared by the
 * exercise evaluator and the package self-test so both enforce identical rules.
 */
final class SqlStructureMatcher {
    /** Compiled keyword patterns are cached because evaluators loop over keyword lists. */
    private static final ConcurrentMap<String, Pattern> KEYWORD_PATTERNS = new ConcurrentHashMap<>();

    private SqlStructureMatcher() {
    }

    static String normalize(String sql) {
        return maskCommentsAndLiterals(sql).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    static boolean containsKeyword(String normalizedSql, String keyword) {
        String phrase = keyword.toUpperCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
        return KEYWORD_PATTERNS
            .computeIfAbsent(phrase, SqlStructureMatcher::compileKeywordPattern)
            .matcher(normalizedSql)
            .find();
    }

    private static Pattern compileKeywordPattern(String phrase) {
        return Pattern.compile("(?<![A-Z0-9_])" + Pattern.quote(phrase) + "(?![A-Z0-9_])");
    }

    private static String maskCommentsAndLiterals(String sql) {
        StringBuilder result = new StringBuilder(sql.length());
        char quote = '\0';
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                    result.append(' ');
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    index++;
                    blockComment = false;
                    result.append(' ');
                }
                continue;
            }
            if (quote == '\0' && current == '-' && next == '-') {
                index++;
                lineComment = true;
                result.append(' ');
            } else if (quote == '\0' && current == '/' && next == '*') {
                index++;
                blockComment = true;
                result.append(' ');
            } else if (quote == '\0' && (current == '\'' || current == '"' || current == '`')) {
                quote = current;
                result.append(' ');
            } else if (quote != '\0') {
                result.append(' ');
                if (current == quote && next == quote) {
                    result.append(' ');
                    index++;
                } else if (current == quote) {
                    quote = '\0';
                }
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }
}
