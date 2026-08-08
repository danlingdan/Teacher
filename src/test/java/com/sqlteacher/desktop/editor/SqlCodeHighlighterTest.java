package com.sqlteacher.desktop.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlCodeHighlighterTest {
    @Test
    void shouldClassifySqlWithoutChangingItsLength() {
        String sql = "SELECT score FROM student WHERE name = 'Ada' AND score >= 90; -- safe preview";
        var spans = SqlCodeHighlighter.highlight(sql);

        assertTrue(spans.length() == sql.length());
        assertTrue(spans.styleStream().anyMatch(style -> style.contains("sql-keyword")));
        assertTrue(spans.styleStream().anyMatch(style -> style.contains("sql-string")));
        assertTrue(spans.styleStream().anyMatch(style -> style.contains("sql-comment")));
        assertTrue(spans.styleStream().anyMatch(style -> style.contains("sql-number")));
    }
}
