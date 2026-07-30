package com.sqlteacher.infrastructure.database;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ExerciseDatasetSchemaSummary {
    private static final Pattern CREATE_TABLE = Pattern.compile(
        "(?is)\\bcreate\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([`\"\\[]?[a-zA-Z_][\\w$]*[`\"\\]]?)\\s*\\((.*?)\\)\\s*;"
    );
    private static final Pattern TABLE_CONSTRAINT = Pattern.compile(
        "(?i)^(constraint|primary|foreign|unique|check)\\b"
    );

    private ExerciseDatasetSchemaSummary() {
    }

    static String fromSetupSql(String setupSql) {
        if (setupSql == null || setupSql.isBlank()) return "暂无数据集字段说明";
        List<String> tables = new ArrayList<>();
        Matcher matcher = CREATE_TABLE.matcher(setupSql);
        while (matcher.find()) {
            String table = unquote(matcher.group(1));
            List<String> columns = splitColumns(matcher.group(2)).stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty() && !TABLE_CONSTRAINT.matcher(value).find())
                .map(ExerciseDatasetSchemaSummary::firstToken)
                .filter(value -> !value.isEmpty())
                .toList();
            if (!columns.isEmpty()) tables.add(table + "（" + String.join("、", columns) + "）");
        }
        return tables.isEmpty() ? "暂无数据集字段说明" : String.join("；", tables);
    }

    private static List<String> splitColumns(String definition) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        char quote = '\0';
        for (int index = 0; index < definition.length(); index++) {
            char current = definition.charAt(index);
            if (quote != '\0') {
                if (current == quote) quote = '\0';
            } else if (current == '\'' || current == '"' || current == '`') {
                quote = current;
            } else if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
            } else if (current == ',' && depth == 0) {
                parts.add(definition.substring(start, index));
                start = index + 1;
            }
        }
        parts.add(definition.substring(start));
        return parts;
    }

    private static String firstToken(String definition) {
        String value = definition.stripLeading();
        if (value.isEmpty()) return "";
        if (value.charAt(0) == '[') {
            int end = value.indexOf(']');
            return end > 0 ? value.substring(1, end) : "";
        }
        if (value.charAt(0) == '`' || value.charAt(0) == '"') {
            int end = value.indexOf(value.charAt(0), 1);
            return end > 0 ? value.substring(1, end) : "";
        }
        int end = 0;
        while (end < value.length() && !Character.isWhitespace(value.charAt(end))) end++;
        return unquote(value.substring(0, end));
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("`") && value.endsWith("`"))
            || (value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("[") && value.endsWith("]")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
