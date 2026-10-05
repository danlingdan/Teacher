package com.sqlteacher.application.nl2sql;

/**
 * AI-drafted SQL plan. {@code modelUnavailable} marks the deterministic "no usable AI engine"
 * outcome (no locally selected model and no active network profile): the draft is empty, the
 * explanation is boilerplate, and the UI should guide the user to configure the AI engine
 * instead of presenting a failed generation.
 */
public record Nl2SqlPlan(
    String sqlDraft,
    String intent,
    String explanation,
    String model,
    String promptVersion,
    boolean modelUnavailable
) {
    public Nl2SqlPlan {
        if (sqlDraft == null) sqlDraft = "";
        if (intent == null) intent = "";
        if (explanation == null) explanation = "";
        if (model == null) model = "";
        if (promptVersion == null) promptVersion = "";
    }

    /** Five-field shape for normal generation attempts; never carries the model-unavailable marker. */
    public Nl2SqlPlan(
        String sqlDraft,
        String intent,
        String explanation,
        String model,
        String promptVersion
    ) {
        this(sqlDraft, intent, explanation, model, promptVersion, false);
    }
}
