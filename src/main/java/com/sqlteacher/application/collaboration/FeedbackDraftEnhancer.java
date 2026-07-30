package com.sqlteacher.application.collaboration;

import com.sqlteacher.application.ai.AiContextPreview;

@FunctionalInterface
public interface FeedbackDraftEnhancer {
    FeedbackDraft enhance(FeedbackDraft deterministicDraft);

    default FeedbackDraft enhance(FeedbackDraft deterministicDraft, FeedbackDraftStyle style) {
        return enhance(deterministicDraft);
    }

    default AiContextPreview preview(FeedbackDraft deterministicDraft) {
        return new AiContextPreview(com.sqlteacher.application.ai.AiTaskType.FEEDBACK_DRAFT,
            java.util.Set.of(), java.util.List.of(), 0, java.util.List.of("当前增强器不发送上下文"));
    }
}
