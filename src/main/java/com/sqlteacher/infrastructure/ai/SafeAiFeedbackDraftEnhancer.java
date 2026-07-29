package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.application.ai.*;
import com.sqlteacher.application.collaboration.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** AI may rewrite wording only; deterministic evidence and result remain authoritative. */
public final class SafeAiFeedbackDraftEnhancer implements FeedbackDraftEnhancer {
    private final AiTaskService taskService;
    private final AiModelProvider legacyProvider;
    private final AiContextPolicy contextPolicy;

    public SafeAiFeedbackDraftEnhancer(AiModelProvider provider) {
        this.legacyProvider = Objects.requireNonNull(provider);
        this.taskService = null;
        this.contextPolicy = new DefaultAiContextPolicy();
    }

    public SafeAiFeedbackDraftEnhancer(AiTaskService taskService, AiContextPolicy contextPolicy) {
        this.taskService = Objects.requireNonNull(taskService);
        this.contextPolicy = Objects.requireNonNull(contextPolicy);
        this.legacyProvider = null;
    }

    @Override public FeedbackDraft enhance(FeedbackDraft draft) { return enhance(draft, FeedbackDraftStyle.CONCISE); }

    @Override public AiContextPreview preview(FeedbackDraft draft) { return prepare(draft).preview(); }

    @Override public FeedbackDraft enhance(FeedbackDraft draft, FeedbackDraftStyle style) {
        Objects.requireNonNull(draft, "deterministicDraft must not be null");
        FeedbackDraftStyle selected = style == null ? FeedbackDraftStyle.CONCISE : style;
        AiPreparedContext context = prepare(draft);
        if (context.items().isEmpty()) return draft;
        String prompt = PromptTemplateLoader.render("/prompts/feedback-draft-v1.txt", Map.of(
            "style", selected.displayName(), "evidence", context.items().get(0).content()
        ));
        try {
            AiTaskResult result;
            if (taskService != null) {
                result = taskService.execute(new AiTaskRequest(AiTaskType.FEEDBACK_DRAFT, "", prompt,
                    "feedback-v1", context.preview()));
            } else {
                String model = legacyProvider.preferredModel();
                if (model == null || model.isBlank()) return draft;
                AiCompletionResult legacy = legacyProvider.complete(new AiCompletionRequest(model, prompt, java.time.Duration.ofSeconds(20)));
                result = legacy.success() ? AiTaskResult.success(legacy.content(), legacy.model())
                    : AiTaskResult.failure(AiTaskErrorCode.PROVIDER_UNAVAILABLE, legacy.errorMessage(), legacy.model());
            }
            if (!result.success() || result.content().isBlank()) return draft;
            List<String> evidence = new ArrayList<>(draft.evidence());
            evidence.add("AI model: " + result.model());
            evidence.add("AI wording draft: " + result.model() + " / feedback-v1 / " + selected.name());
            return new FeedbackDraft(result.content().strip(), evidence, true);
        } catch (RuntimeException error) {
            return draft;
        }
    }

    private AiPreparedContext prepare(FeedbackDraft draft) {
        Objects.requireNonNull(draft, "deterministicDraft must not be null");
        return contextPolicy.prepare(AiTaskType.FEEDBACK_DRAFT, List.of(
            new AiContextItem(AiContextCategory.DETERMINISTIC_RESULT, "确定性评测证据",
                String.join("；", draft.evidence()) + "\n基础草稿：" + draft.text())
        ));
    }
}
