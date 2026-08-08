package com.sqlteacher.desktop.editor;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Visual-only SQL highlighting. It never parses, validates, builds, or executes SQL. */
public final class SqlCodeHighlighter {
    private static final String[] KEYWORDS = {
        "SELECT", "FROM", "WHERE", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON",
        "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "AS", "DISTINCT", "UNION",
        "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "ALTER", "DROP",
        "TABLE", "VIEW", "INDEX", "AND", "OR", "NOT", "NULL", "IS", "IN", "LIKE", "BETWEEN"
    };
    private static final Pattern TOKENS = Pattern.compile(
        "(?<COMMENT>--[^\\n]*|/\\*(?:.|\\R)*?\\*/)|" +
            "(?<STRING>'(?:''|[^'])*')|" +
            "(?<KEYWORD>\\b(?:" + String.join("|", KEYWORDS) + ")\\b)|" +
            "(?<NUMBER>\\b\\d+(?:\\.\\d+)?\\b)|" +
            "(?<OPERATOR><>|!=|<=|>=|[-+*/%=<>])",
        Pattern.CASE_INSENSITIVE
    );

    private SqlCodeHighlighter() {
    }

    public static StyleSpans<Collection<String>> highlight(String sql) {
        String source = sql == null ? "" : sql;
        Matcher matcher = TOKENS.matcher(source);
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        int last = 0;
        while (matcher.find()) {
            spans.add(Collections.emptyList(), matcher.start() - last);
            String style = matcher.group("COMMENT") != null ? "sql-comment"
                : matcher.group("STRING") != null ? "sql-string"
                : matcher.group("KEYWORD") != null ? "sql-keyword"
                : matcher.group("NUMBER") != null ? "sql-number"
                : "sql-operator";
            spans.add(Collections.singleton(style), matcher.end() - matcher.start());
            last = matcher.end();
        }
        spans.add(Collections.emptyList(), source.length() - last);
        return spans.create();
    }
}
