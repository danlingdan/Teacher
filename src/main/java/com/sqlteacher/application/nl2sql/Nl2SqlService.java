package com.sqlteacher.application.nl2sql;

import com.sqlteacher.application.ai.AiContextPreview;

@FunctionalInterface
public interface Nl2SqlService {
    Nl2SqlPlan generate(Nl2SqlRequest request);

    default AiContextPreview preview(Nl2SqlRequest request) {
        throw new UnsupportedOperationException("AI context preview is unavailable");
    }

    default Nl2SqlPlan revise(Nl2SqlRequest request, Nl2SqlPlan previous, String instruction) {
        throw new UnsupportedOperationException("AI SQL draft revision is unavailable");
    }

    default AiContextPreview previewRevision(Nl2SqlRequest request, Nl2SqlPlan previous, String instruction) {
        throw new UnsupportedOperationException("AI SQL draft revision preview is unavailable");
    }

    default SqlErrorExplanation explainSqlError(String connectionId, String sql, String errorMessage) {
        return SqlErrorExplanation.failure("SQL error explanation is unavailable", "");
    }
}
