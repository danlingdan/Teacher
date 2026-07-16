package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.application.risk.SqlRiskLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DefaultSqlRiskAnalysisService implements SqlRiskAnalysisService {
    @Override
    public SqlRiskAnalysis analyze(String sql) {

        if (sql == null || sql.isBlank()) {
            return forbidden("UNKNOWN", false, "SQL must not be blank");
        }

        String normalized = sql.strip();

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

        // Explicitly block DROP DATABASE as it's extremely dangerous
        if (normalized.toUpperCase().contains("DROP DATABASE")) {
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
                    false,
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

    private boolean hasMultipleStatements(String sql) {
        // First check for semicolons (original logic)
        String value = sql;
        if (value.endsWith(";")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.contains(";")) {
            return true;
        }

        // Enhanced detection: check for multiple SQL keywords without semicolons
        String normalized = sql.toUpperCase(Locale.ROOT).strip();
        
        // Get the first keyword (length determined by firstKeyword logic)
        int firstKeywordEnd = 0;
        while (firstKeywordEnd < normalized.length() && Character.isLetter(normalized.charAt(firstKeywordEnd))) {
            firstKeywordEnd++;
        }
        
        if (firstKeywordEnd == 0) {
            return false;
        }
        
        // Skip whitespace after first keyword
        int searchStart = firstKeywordEnd;
        while (searchStart < normalized.length() && Character.isWhitespace(normalized.charAt(searchStart))) {
            searchStart++;
        }
        
        if (searchStart >= normalized.length()) {
            return false;
        }
        
        // Look for additional SQL keywords in the remaining text
        String remainingText = normalized.substring(searchStart);
        
        // List of SQL keywords that could indicate a new statement
        String[] sqlKeywords = {"INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP", "TRUNCATE", "GRANT", "REVOKE"};
        
        for (String keyword : sqlKeywords) {
            // Check if the keyword appears as a word boundary (not part of another word)
            if (containsWordBoundary(remainingText, keyword)) {
                return true;
            }
        }
        
        return false;
    }
    
    private boolean containsWordBoundary(String text, String word) {
        int index = 0;
        while (index < text.length()) {
            int found = text.indexOf(word, index);
            if (found == -1) {
                return false;
            }
            
            // Check if it's a word boundary (preceded by whitespace or start, followed by whitespace or end)
            boolean precededByBoundary = (found == 0) || Character.isWhitespace(text.charAt(found - 1));
            boolean followedByBoundary = (found + word.length() >= text.length()) || 
                                       Character.isWhitespace(text.charAt(found + word.length()));
            
            if (precededByBoundary && followedByBoundary) {
                return true;
            }
            
            index = found + 1;
        }
        return false;
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
}
