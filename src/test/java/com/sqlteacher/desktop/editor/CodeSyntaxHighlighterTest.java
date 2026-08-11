package com.sqlteacher.desktop.editor;

import com.sqlteacher.domain.activity.CodeLanguage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeSyntaxHighlighterTest {
    @Test
    void shouldHighlightJavaPythonAndNativeCodeWithoutChangingLength() {
        assertStyles(CodeLanguage.JAVA,
            "public class Main { // note\n String value = \"x\"; int count = 2; }",
            "code-keyword", "code-comment", "code-string", "code-number");
        assertStyles(CodeLanguage.PYTHON,
            "def total(values): # note\n    return sum(values) + 2",
            "code-keyword", "code-comment", "code-number");
        assertStyles(CodeLanguage.CPP,
            "#include <iostream>\nint main() { return 0; }",
            "code-directive", "code-keyword", "code-number");
    }

    private static void assertStyles(CodeLanguage language, String source, String... expected) {
        var spans = CodeSyntaxHighlighter.highlight(language, source);
        assertEquals(source.length(), spans.length());
        for (String style : expected) {
            assertTrue(spans.styleStream().anyMatch(classes -> classes.contains(style)),
                () -> language + " did not produce " + style);
        }
    }
}
