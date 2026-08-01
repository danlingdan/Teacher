package com.sqlteacher.application.knowledge;

import java.util.List;

public record GroundedKnowledgeAnswer(
    boolean aiGenerated,
    String answer,
    String model,
    List<Citation> citations,
    String message
) {
    public GroundedKnowledgeAnswer {
        answer = answer == null ? "" : answer.trim();
        model = model == null ? "" : model.trim();
        citations = citations == null ? List.of() : List.copyOf(citations);
        message = message == null ? "" : message.trim();
    }

    public record Citation(
        int number,
        String documentId,
        String articleTitle,
        int revision,
        int chunkIndex,
        String snippet
    ) {
        public Citation {
            if (number < 1 || documentId == null || documentId.isBlank()
                || articleTitle == null || articleTitle.isBlank() || revision < 1 || chunkIndex < 0
                || snippet == null || snippet.isBlank()) {
                throw new IllegalArgumentException("knowledge citation values are invalid");
            }
        }
    }
}
