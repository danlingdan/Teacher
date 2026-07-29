package com.sqlteacher.application.nl2sql;

import com.sqlteacher.application.ai.AiContextPreview;

/**
 * Application-level use case that generates an AI SQL draft and evaluates it with the
 * same Java-side safety policy used by manual SQL execution.
 *
 * <p>This service only returns a draft and its assessment. It deliberately exposes no
 * SQL execution operation.</p>
 */
public interface Nl2SqlSafetyService {
    Nl2SqlSafetyResult generateAndAssess(Nl2SqlRequest request);

    default AiContextPreview preview(Nl2SqlRequest request) {
        throw new UnsupportedOperationException("AI context preview is unavailable");
    }

    default Nl2SqlSafetyResult reviseAndAssess(Nl2SqlRequest request, Nl2SqlPlan previous, String instruction) {
        throw new UnsupportedOperationException("AI SQL draft revision is unavailable");
    }

    default AiContextPreview previewRevision(Nl2SqlRequest request, Nl2SqlPlan previous, String instruction) {
        throw new UnsupportedOperationException("AI SQL draft revision preview is unavailable");
    }
}
