package com.sqlteacher.infrastructure.database;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a SQL script into single statements on unquoted, uncommented semicolons.
 * Trigger-aware: the semicolons inside a CREATE TRIGGER ... BEGIN ... END body belong to
 * the trigger statement, so the split only closes the statement after the body's
 * terminating END. This mirrors how SQLite tokenizes trigger definitions and keeps
 * multi-statement trigger bodies intact for risk analysis and execution.
 */
final class SqlScriptSplitter {
    private SqlScriptSplitter() {
    }

    static List<String> split(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = '\0';
        boolean lineComment = false;
        boolean blockComment = false;
        // CREATE TRIGGER tracking, reset once the statement terminates.
        boolean expectTrigger = false;
        boolean inTriggerHeader = false;
        boolean inTriggerBody = false;
        boolean bodyEndSeen = false;
        StringBuilder word = new StringBuilder();

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
                endWord(word);
                continue;
            }
            if (quote == '\0' && character == '/' && next == '*') {
                current.append(character).append(next);
                index++;
                blockComment = true;
                endWord(word);
                continue;
            }
            if (quote == '\0' && (character == '\'' || character == '"' || character == '`')) {
                quote = character;
                current.append(character);
                endWord(word);
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
            if (quote == '\0' && (Character.isLetterOrDigit(character) || character == '_')) {
                current.append(character);
                word.append(Character.toLowerCase(character));
                continue;
            }
            // Any other delimiter ends the current word and may end a statement.
            if (quote == '\0' && character == ';') {
                String pending = endWord(word);
                if ("end".equals(pending) && inTriggerBody) {
                    bodyEndSeen = true;
                }
                boolean triggerTerminator = inTriggerBody && bodyEndSeen;
                if (inTriggerBody && !triggerTerminator) {
                    current.append(character);
                    continue;
                }
                expectTrigger = false;
                inTriggerHeader = false;
                inTriggerBody = false;
                bodyEndSeen = false;
                addStatement(statements, current);
                continue;
            }
            if (quote == '\0' && !Character.isWhitespace(character)) {
                endWord(word);
            }
            current.append(character);
            if (quote == '\0') {
                String token = endWord(word);
                if (token != null) {
                    switch (token) {
                        case "create" -> expectTrigger = true;
                        case "temp", "temporary" -> {
                            if (!expectTrigger) {
                                expectTrigger = false;
                            }
                        }
                        case "trigger" -> {
                            if (expectTrigger) {
                                inTriggerHeader = true;
                            }
                            expectTrigger = false;
                        }
                        case "begin" -> {
                            if (inTriggerHeader) {
                                inTriggerHeader = false;
                                inTriggerBody = true;
                            }
                        }
                        case "end" -> bodyEndSeen = inTriggerBody;
                        default -> {
                            expectTrigger = false;
                            if (inTriggerBody && !"end".equals(token)) {
                                bodyEndSeen = false;
                            }
                        }
                    }
                }
            }
        }
        endWord(word);
        addStatement(statements, current);
        return List.copyOf(statements);
    }

    private static String endWord(StringBuilder word) {
        if (word.isEmpty()) {
            return null;
        }
        String token = word.toString();
        word.setLength(0);
        return token;
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        current.setLength(0);
    }
}
