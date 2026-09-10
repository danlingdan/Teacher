package com.sqlteacher.application.exercise;

public interface ExerciseTextDraftingService {
    ExerciseTextDraft draft(String freeText);

    /**
     * Drafts a display-only explanation for a failed submission. Implementations must
     * fail safely without touching learning state; the caller labels the output as AI.
     */
    ExerciseExplanation explainFailure(ExerciseExplainRequest request);
}
