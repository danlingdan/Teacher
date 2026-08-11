package com.sqlteacher.desktop.editor;

import com.sqlteacher.domain.activity.CodeLanguage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeCompletionCatalogTest {
    @Test
    void shouldCompleteLanguageKeywordsAndIdentifiersAlreadyInTheSource() {
        String java = "int studentCount = 1;\nret";
        assertTrue(CodeCompletionCatalog.suggest(CodeLanguage.JAVA, java, java.length(), 12).contains("return"));

        String python = "student_total = 2\nprint(stu";
        assertTrue(CodeCompletionCatalog.suggest(CodeLanguage.PYTHON, python, python.length(), 12)
            .contains("student_total"));
    }

    @Test
    void shouldReplaceOnlyTheIdentifierPrefixAtTheCaret() {
        String source = "System.out.pri";
        assertEquals("System.out.".length(), CodeCompletionCatalog.prefixStart(source, source.length()));
    }
}
