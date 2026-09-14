package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.application.analytics.AnalyticsFilter;
import com.sqlteacher.application.analytics.LearningAnalyticsService;
import com.sqlteacher.application.collaboration.CloudApiClient;
import com.sqlteacher.application.exercise.ExerciseDraft;
import com.sqlteacher.application.exercise.ExerciseManagementService;
import com.sqlteacher.application.exercise.ExerciseProgressService;
import com.sqlteacher.application.exercise.ExerciseTextDraftingService;
import com.sqlteacher.application.learning.InterventionService;
import com.sqlteacher.application.learning.InterventionStatus;
import com.sqlteacher.domain.exercise.ExerciseDifficulty;
import com.sqlteacher.domain.exercise.ExerciseEvaluationRule;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** v3.4.0 REF-8: teacher workspace, exercise authoring, analytics, and interventions. */
final class TeachingApiSection extends ApiSection {

    TeachingApiSection(ApiSectionHost host) {
        super(host);
    }

    @Override
    public Set<String> supportedMethods() {
        return Set.of(
            "teaching.workspace", "teaching.exercise.toggle", "teaching.exercise.detail",
            "teaching.exercise.save", "teaching.exercise.copy", "teaching.exercise.import",
            "teaching.exercise.parse", "teaching.exercise.draft", "teaching.exercise.export",
            "teaching.exercise.publish", "teaching.exercise.health", "teaching.bank.rollback",
            "teaching.analytics", "teaching.interventions", "teaching.intervention.update"
        );
    }

    @Override
    public JsonNode handle(String method, JsonNode params, CancellationToken cancellation,
                           Consumer<LocalAppEvent> events) throws Exception {
        return switch (method) {
            case "teaching.workspace" -> teachingWorkspace(cancellation);
            case "teaching.exercise.toggle" -> teachingExerciseToggle(params, cancellation);
            case "teaching.exercise.detail" -> teachingExerciseDetail(params, cancellation);
            case "teaching.exercise.save" -> teachingExerciseSave(params, cancellation);
            case "teaching.exercise.copy" -> teachingExerciseCopy(params, cancellation);
            case "teaching.exercise.import" -> teachingExerciseImport(params, cancellation);
            case "teaching.exercise.parse" -> teachingExerciseParse(params, cancellation);
            case "teaching.exercise.draft" -> teachingExerciseDraft(params, cancellation);
            case "teaching.exercise.export" -> teachingExerciseExport(params, cancellation);
            case "teaching.exercise.publish" -> teachingExercisePublish(params, cancellation);
            case "teaching.exercise.health" -> teachingExerciseHealth(cancellation);
            case "teaching.bank.rollback" -> teachingBankRollback(params, cancellation);
            case "teaching.analytics" -> teachingAnalytics(cancellation);
            case "teaching.interventions" -> teachingInterventions(cancellation);
            case "teaching.intervention.update" -> teachingInterventionUpdate(params, cancellation);
            default -> throw new IllegalStateException("Method whitelist and dispatcher are inconsistent");
        };
    }

    private JsonNode teachingWorkspace(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        com.sqlteacher.application.collaboration.DesktopAccessProfile profile = requireTeacher();
        var management = context().getBean(ExerciseManagementService.class);
        var progress = context().getBean(ExerciseProgressService.class);
        ObjectNode result = mapper.createObjectNode();
        result.put("role", webRole(profile));
        result.put("canPublish", true);
        result.set("exercises", mapper.valueToTree(management.listExercises(true)));
        result.set("progressOverview", mapper.valueToTree(progress.overview()));
        result.set("progressItems", mapper.valueToTree(progress.listExerciseProgress()));
        result.set("datasets", mapper.valueToTree(management.listDatasets()));
        result.put("authority", "java-and-cloud-server");
        return result;
    }

    private JsonNode teachingExerciseToggle(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        String exerciseId = requiredText(params, "exerciseId", 128);
        boolean enabled = params.path("enabled").asBoolean();
        int expectedVersion = params.path("expectedVersion").asInt(0);
        if (expectedVersion < 1) throw new IllegalArgumentException("expectedVersion must be positive");
        return mapper.valueToTree(context().getBean(ExerciseManagementService.class)
            .setEnabled(exerciseId, enabled, expectedVersion));
    }

    private JsonNode teachingExerciseDetail(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(ExerciseManagementService.class)
            .findDefinition(requiredText(params, "exerciseId", 128))
            .orElseThrow(() -> new IllegalArgumentException("Exercise does not exist")));
    }

    private JsonNode teachingExerciseSave(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        List<String> keywords = new java.util.ArrayList<>();
        params.path("requiredSqlKeywords").forEach(item -> keywords.add(item.asText()));
        List<String> hints = new java.util.ArrayList<>();
        params.path("hints").forEach(item -> { if (!item.asText().isBlank()) hints.add(item.asText()); });
        Integer expectedRows = params.path("expectedRowCount").isInt()
            ? params.path("expectedRowCount").asInt() : null;
        Integer expectedVersion = params.path("expectedVersion").isInt()
            ? params.path("expectedVersion").asInt() : null;
        ExerciseDraft draft = new ExerciseDraft(params.path("id").asText(""),
            requiredText(params, "title", 240), requiredText(params, "description", 8_000),
            requiredText(params, "knowledgePoint", 240),
            ExerciseDifficulty.valueOf(requiredText(params, "difficulty", 32)),
            requiredText(params, "datasetId", 128), requiredText(params, "referenceSql", 256 * 1024),
            new ExerciseEvaluationRule(params.path("compareColumns").asBoolean(true),
                params.path("compareRows").asBoolean(true), params.path("rowOrderMatters").asBoolean(false),
                expectedRows, keywords, Map.of(), List.of()), hints, expectedVersion, params.path("enabled").asBoolean(true));
        return mapper.valueToTree(context().getBean(ExerciseManagementService.class).save(draft));
    }

    private JsonNode teachingExerciseCopy(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(ExerciseManagementService.class).copy(
            requiredText(params, "exerciseId", 128), requiredText(params, "title", 240)));
    }

    private JsonNode teachingExerciseImport(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(ExerciseManagementService.class)
            .importPackage(requiredText(params, "text", 1_000_000)));
    }

    private JsonNode teachingExerciseParse(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(ExerciseManagementService.class)
            .parsePackage(requiredText(params, "text", 1_000_000)));
    }

    private JsonNode teachingExerciseDraft(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(ExerciseTextDraftingService.class)
            .draft(requiredText(params, "text", 200_000)));
    }

    private JsonNode teachingExerciseExport(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        List<String> ids = new java.util.ArrayList<>();
        params.path("exerciseIds").forEach(item -> ids.add(item.asText()));
        if (ids.isEmpty()) throw new IllegalArgumentException("At least one exercise must be selected");
        return mapper.createObjectNode().put("text",
            context().getBean(ExerciseManagementService.class).exportPackage(ids));
    }

    private JsonNode teachingAnalytics(CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(LearningAnalyticsService.class).analyze(AnalyticsFilter.all()));
    }

    private JsonNode teachingInterventions(CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        return mapper.createObjectNode().set("items",
            mapper.valueToTree(context().getBean(InterventionService.class).refreshAuthorized()));
    }

    private JsonNode teachingInterventionUpdate(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        String id = requiredText(params, "candidateId", 128);
        InterventionStatus status = InterventionStatus.valueOf(requiredText(params, "status", 32));
        context().getBean(InterventionService.class).updateStatus(id, status);
        return mapper.createObjectNode().put("updated", true).put("candidateId", id).put("status", status.name());
    }

    /** Rolls the channel's active bank version back for all clients (admin/teacher). */
    private JsonNode teachingBankRollback(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        String channel = params.path("channel").asText("network");
        int bankVersion = params.path("bankVersion").asInt(0);
        var session = requireCloudSession();
        int applied = context().getBean(CloudApiClient.class)
            .rollbackExerciseBank(session.accessToken(), channel, bankVersion);
        return mapper.createObjectNode().put("bankVersion", applied);
    }

    private JsonNode teachingExerciseHealth(CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var items = context().getBean(ExerciseManagementService.class).healthCheck();
        return mapper.createObjectNode().set("items", mapper.valueToTree(items));
    }

    private JsonNode teachingExercisePublish(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        String text = requiredText(params, "text", 1_000_000);
        // 发布前本地先跑一遍解析与自测；服务端会再次校验，AI 与网络内容一律不可信。
        context().getBean(ExerciseManagementService.class).parsePackage(text);
        var session = requireCloudSession();
        String channel = params.path("channel").asText("network");
        int bankVersion = context().getBean(CloudApiClient.class)
            .publishExerciseBankPackage(session.accessToken(), channel, text);
        return mapper.createObjectNode().put("bankVersion", bankVersion);
    }
}
