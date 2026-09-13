package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.application.activity.ActivityLearningService;
import com.sqlteacher.domain.activity.CodeActivityArtifact;
import com.sqlteacher.domain.activity.CodeLanguage;
import com.sqlteacher.domain.activity.LabActivityArtifact;
import com.sqlteacher.domain.activity.ProjectActivityArtifact;
import com.sqlteacher.domain.activity.QuizActivityArtifact;
import com.sqlteacher.domain.activity.ReadingActivityArtifact;
import com.sqlteacher.domain.activity.SimulationActivityArtifact;
import com.sqlteacher.domain.activity.SqlActivityArtifact;
import com.sqlteacher.domain.activity.TraceActivityArtifact;

import java.util.Set;
import java.util.function.Consumer;

/** v3.4.0 REF-8: activity learning flow and the local learning portfolio. */
final class ActivityApiSection extends ApiSection {

    ActivityApiSection(ApiSectionHost host) {
        super(host);
    }

    @Override
    public Set<String> supportedMethods() {
        return Set.of(
            "activity.definition", "activity.submit",
            "learning.portfolio", "learning.portfolio.export"
        );
    }

    @Override
    public JsonNode handle(String method, JsonNode params, CancellationToken cancellation,
                           Consumer<LocalAppEvent> events) throws Exception {
        return switch (method) {
            case "activity.definition" -> activityDefinition(params, cancellation);
            case "activity.submit" -> activitySubmit(params, cancellation);
            case "learning.portfolio" -> learningPortfolio(cancellation);
            case "learning.portfolio.export" -> learningPortfolioExport(params, cancellation);
            default -> throw new IllegalStateException("Method whitelist and dispatcher are inconsistent");
        };
    }

    private JsonNode activityDefinition(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String activityId = requiredText(params, "activityId", 128);
        ActivityLearningService service = context().getBean(ActivityLearningService.class);
        var definition = service.loadDefinition(activityId);
        ObjectNode result = mapper.valueToTree(definition);
        ObjectNode safeSpecification = mapper.valueToTree(definition.specification());
        switch (definition.type()) {
            case QUIZ -> safeSpecification.path("questions").forEach(question -> {
                if (question instanceof ObjectNode object) {
                    object.remove("correctOptionId");
                    object.remove("explanation");
                }
            });
            case TRACE -> safeSpecification.remove("expectedNodeIds");
            case CODE -> safeSpecification.remove("tests");
            case READING -> safeSpecification.path("checks").forEach(check -> {
                if (check instanceof ObjectNode object) {
                    object.remove("expectedAnswer");
                    object.remove("explanation");
                }
            });
            case SIMULATION -> safeSpecification.remove("checkpoints");
            case SQL -> {
                safeSpecification.remove("referenceSql");
                safeSpecification.remove("evaluationRule");
            }
            case PROJECT, LAB -> { }
        }
        result.set("specification", safeSpecification);
        result.put("type", definition.type().name());
        result.put("nextSubmissionVersion", service.nextSubmissionVersion(activityId));
        service.latestFeedback(activityId).ifPresent(feedback -> result.set("latestFeedback", mapper.valueToTree(feedback)));
        return result;
    }

    private JsonNode activitySubmit(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String activityId = requiredText(params, "activityId", 128);
        String type = requiredText(params, "type", 32).toUpperCase();
        JsonNode artifact = params.path("artifact");
        var submitted = switch (type) {
            case "QUIZ" -> new QuizActivityArtifact(textMap(artifact.path("selectedOptionIds"), 100, 256));
            case "TRACE" -> new TraceActivityArtifact(textList(artifact.path("visitedNodeIds"), 256, 128));
            case "SIMULATION" -> new SimulationActivityArtifact(textList(artifact.path("actionIds"), 256, 128));
            case "CODE" -> new CodeActivityArtifact(
                CodeLanguage.valueOf(requiredText(artifact, "language", 16).toUpperCase()),
                requiredText(artifact, "sourceCode", 256 * 1024));
            case "PROJECT" -> new ProjectActivityArtifact(
                artifact.path("submissionVersion").asInt(1),
                textList(artifact.path("completedMilestoneIds"), 20, 128),
                artifact.path("evidenceSummary").asText(""), artifact.path("reflection").asText(""));
            case "LAB" -> new LabActivityArtifact(
                textList(artifact.path("completedStepIds"), 50, 128),
                textMap(artifact.path("observations"), 50, 4_000), artifact.path("conclusion").asText(""));
            case "READING" -> new ReadingActivityArtifact(
                artifact.path("readToEnd").asBoolean(false), textMap(artifact.path("answers"), 30, 2_000));
            case "SQL" -> new SqlActivityArtifact(requiredText(artifact, "submittedSql", 256 * 1024));
            default -> throw new IllegalArgumentException("Unsupported activity type: " + type);
        };
        var submission = context().getBean(ActivityLearningService.class)
            .submit(activityId, submitted, cancellation::cancelled);
        cancellation.throwIfCancelled();
        return mapper.valueToTree(submission);
    }

    private JsonNode learningPortfolio(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return mapper.createObjectNode().set("items", mapper.valueToTree(context().getBean(
            com.sqlteacher.application.activity.ProjectPortfolioService.class).listOwnEntries()));
    }

    private JsonNode learningPortfolioExport(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        if (!params.path("confirmed").asBoolean(false)) {
            throw new SecurityException("Portfolio export requires explicit confirmation");
        }
        return mapper.createObjectNode().put("content", context().getBean(
            com.sqlteacher.application.activity.ProjectPortfolioService.class).exportOwnPortfolio(true));
    }
}
