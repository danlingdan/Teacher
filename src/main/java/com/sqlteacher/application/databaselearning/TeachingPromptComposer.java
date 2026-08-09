package com.sqlteacher.application.databaselearning;

import java.util.Objects;

public final class TeachingPromptComposer {
    public String compose(TeachingAssistanceMode mode, String question) {
        Objects.requireNonNull(mode);
        if (question == null || question.isBlank()) throw new IllegalArgumentException("question must not be blank");
        if (mode == TeachingAssistanceMode.DIRECT) return question.strip();
        return "教学引导模式：先解释需求、列出检查步骤，再给出一个可审查的 SQL 草稿；不要声称已经执行。学生问题：" + question.strip();
    }
}
