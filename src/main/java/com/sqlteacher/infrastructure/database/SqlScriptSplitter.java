package com.sqlteacher.infrastructure.database;

import java.util.ArrayList;
import java.util.List;

final class SqlScriptSplitter {
    private SqlScriptSplitter() {
    }

    static List<String> split(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = '\0';
        boolean lineComment = false;
        boolean blockComment = false;

        for (int index = 0; index < script.length(); index++) {
            char character = script.charAt(index);
            char next = index + 1 < script.length() ? script.charAt(index + 1) : '\0';
            if (lineComment) {
                current.append(character);
                if (character == '\n' || character == '\r') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                current.append(character);
                if (character == '*' && next == '/') {
                    current.append(next);
                    index++;
                    blockComment = false;
                }
                continue;
            }
            if (quote == '\0' && character == '-' && next == '-') {
                current.append(character).append(next);
                index++;
                lineComment = true;
                continue;
            }
            if (quote == '\0' && character == '/' && next == '*') {
                current.append(character).append(next);
                index++;
                blockComment = true;
                continue;
            }
            if (quote == '\0' && (character == '\'' || character == '"' || character == '`')) {
                quote = character;
                current.append(character);
                continue;
            }
            if (quote != '\0' && character == quote) {
                current.append(character);
                if (next == quote) {
                    current.append(next);
                    index++;
                } else {
                    quote = '\0';
                }
                continue;
            }
            if (quote == '\0' && character == ';') {
                addStatement(statements, current);
            } else {
                current.append(character);
            }
        }
        addStatement(statements, current);
        return List.copyOf(statements);
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        current.setLength(0);
    }
}
