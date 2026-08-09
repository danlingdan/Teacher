package com.sqlteacher.application.databaselearning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TeachingPromptComposerTest {
    private final TeachingPromptComposer composer = new TeachingPromptComposer();

    @Test
    void guidedModeAddsTeachingSequenceButNeverClaimsExecution() {
        String prompt = composer.compose(TeachingAssistanceMode.GUIDED, "查询选课人数");
        assertTrue(prompt.contains("检查步骤"));
        assertTrue(prompt.contains("不要声称已经执行"));
        assertTrue(prompt.endsWith("查询选课人数"));
    }

    @Test
    void directModePreservesStudentQuestion() {
        assertEquals("查询选课人数", composer.compose(TeachingAssistanceMode.DIRECT, " 查询选课人数 "));
    }
}
