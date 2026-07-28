package com.sqlteacher.application.collaboration;

@FunctionalInterface
public interface FeedbackDraftEnhancer {
    FeedbackDraft enhance(FeedbackDraft deterministicDraft);
}
