package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.application.ai.AiCompletionRequest;
import com.sqlteacher.application.ai.AiModelProvider;
import com.sqlteacher.application.collaboration.FeedbackDraft;
import com.sqlteacher.application.collaboration.FeedbackDraftEnhancer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Uses a configured network provider only; failures deterministically fall back to the server draft. */
public final class SafeAiFeedbackDraftEnhancer implements FeedbackDraftEnhancer {
    private static final int MAX_OUTPUT = 2_000;
    private final AiModelProvider provider;

    public SafeAiFeedbackDraftEnhancer(AiModelProvider provider) {
        this.provider = Objects.requireNonNull(provider);
    }

    @Override
    public FeedbackDraft enhance(FeedbackDraft deterministicDraft) {
        Objects.requireNonNull(deterministicDraft, "deterministicDraft must not be null");
        String model = provider.preferredModel();
        if (model == null || model.isBlank()) return deterministicDraft;
        String prompt = """
            你是 SQL 课程教师的反馈草稿助手。只根据下面的确定性证据改写一段简洁、友善、可操作的中文反馈。
            不得改变通过/未通过结论，不得给分，不得编造学生身份、SQL、数据库结构或答案，不得包含 Markdown。
            输出不超过 300 个汉字，教师会人工确认后再发布。

            证据：%s
            基础草稿：%s
            """.formatted(String.join("；", deterministicDraft.evidence()), deterministicDraft.text());
        try {
            var result = provider.complete(new AiCompletionRequest(model, prompt, Duration.ofSeconds(20)));
            if (!result.success() || result.content() == null || result.content().isBlank()) return deterministicDraft;
            String content = result.content().strip();
            if (content.length() > MAX_OUTPUT) content = content.substring(0, MAX_OUTPUT);
            List<String> evidence = new ArrayList<>(deterministicDraft.evidence());
            evidence.add("AI model: " + result.model());
            return new FeedbackDraft(content, evidence, true);
        } catch (RuntimeException error) {
            return deterministicDraft;
        }
    }
}
