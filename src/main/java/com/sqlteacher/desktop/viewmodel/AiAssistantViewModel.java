package com.sqlteacher.desktop.viewmodel;

import com.sqlteacher.application.nl2sql.Nl2SqlPlan;
import com.sqlteacher.application.nl2sql.Nl2SqlRequest;

/**
 * ViewModel for the AI assistant (NL2SQL draft) page.
 *
 * <p>Adapts {@link Nl2SqlRequest} and the repackaged {@link Nl2SqlPlan}, which now returns a
 * runnable {@code sqlDraft} plus {@code intent}, {@code explanation} and the model / prompt
 * provenance ({@code model}, {@code promptVersion}). This ViewModel carries exactly those UI
 * display fields plus the current {@link AiStatusViewModel} used to show whether the model is
 * available. {@code draftAvailable} is derived from the presence of {@code sqlDraft} and lets the
 * UI decide whether the SQL preview area should be shown.
 */
public record AiAssistantViewModel(
    String connectionId,
    String naturalLanguage,
    String sqlDraft,
    String intent,
    String explanation,
    String model,
    String promptVersion,
    boolean draftAvailable,
    AiStatusViewModel aiStatus
) {
    public AiAssistantViewModel {
        connectionId = connectionId == null || connectionId.isBlank() ? DesktopConnections.DEMO : connectionId;
        naturalLanguage = naturalLanguage == null ? "" : naturalLanguage;
        sqlDraft = sqlDraft == null ? "" : sqlDraft;
        intent = intent == null ? "" : intent;
        explanation = explanation == null ? "" : explanation;
        model = model == null ? "" : model;
        promptVersion = promptVersion == null ? "" : promptVersion;
        aiStatus = aiStatus == null ? AiStatusViewModel.unknown() : aiStatus;
    }

    public static AiAssistantViewModel from(Nl2SqlRequest request, Nl2SqlPlan plan, AiStatusViewModel aiStatus) {
        String sqlDraft = plan == null ? "" : plan.sqlDraft();
        String intent = plan == null ? "" : plan.intent();
        String explanation = plan == null ? "" : plan.explanation();
        String model = plan == null ? "" : plan.model();
        String promptVersion = plan == null ? "" : plan.promptVersion();
        boolean draftAvailable = isPresent(sqlDraft);
        return new AiAssistantViewModel(
            request == null ? DesktopConnections.DEMO : request.connectionId(),
            request == null ? "" : request.naturalLanguage(),
            sqlDraft,
            intent,
            explanation,
            model,
            promptVersion,
            draftAvailable,
            aiStatus
        );
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
