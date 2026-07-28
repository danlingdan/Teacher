package com.sqlteacher.application.collaboration;

import java.util.List;

public record FeedbackDraft(String text, List<String> evidence, boolean aiGenerated) {
    public FeedbackDraft {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Feedback draft text is required");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
