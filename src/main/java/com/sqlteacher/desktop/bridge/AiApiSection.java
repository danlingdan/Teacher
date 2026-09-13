package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.exercise.ExerciseExplainRequest;
import com.sqlteacher.application.exercise.ExerciseTextDraftingService;
import com.sqlteacher.application.knowledge.CourseKnowledgeSearchFilter;
import com.sqlteacher.application.knowledge.GroundedKnowledgeExplanationService;
import com.sqlteacher.application.nl2sql.Nl2SqlRequest;
import com.sqlteacher.application.nl2sql.Nl2SqlSafetyService;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** v3.4.0 REF-8: AI assistance methods. AI only drafts; deterministic services stay authoritative. */
final class AiApiSection extends ApiSection {

    AiApiSection(ApiSectionHost host) {
        super(host);
    }

    @Override
    public Set<String> supportedMethods() {
        return Set.of("ai.knowledge.ask", "ai.sql.preview", "ai.sql.generate", "ai.exercise.explain");
    }

    @Override
    public JsonNode handle(String method, JsonNode params, CancellationToken cancellation,
                           Consumer<LocalAppEvent> events) throws Exception {
        return switch (method) {
            case "ai.knowledge.ask" -> aiKnowledgeAsk(params, cancellation, events);
            case "ai.sql.preview" -> aiSqlPreview(params, cancellation);
            case "ai.sql.generate" -> aiSqlGenerate(params, cancellation, events);
            case "ai.exercise.explain" -> aiExerciseExplain(params, cancellation);
            default -> throw new IllegalStateException("Method whitelist and dispatcher are inconsistent");
        };
    }

    private JsonNode aiKnowledgeAsk(JsonNode params, CancellationToken cancellation,
                                    Consumer<LocalAppEvent> events) {
        cancellation.throwIfCancelled();
        String question = requiredText(params, "question", 2_000);
        var answer = context().getBean(GroundedKnowledgeExplanationService.class)
            .explain(question, CourseKnowledgeSearchFilter.allLocal());
        cancellation.throwIfCancelled();
        String content = answer.answer();
        for (int offset = 0; offset < content.length(); offset += 240) {
            cancellation.throwIfCancelled();
            ObjectNode payload = mapper.createObjectNode();
            payload.put("delta", content.substring(offset, Math.min(offset + 240, content.length())));
            events.accept(new LocalAppEvent("ai.delta", payload));
        }
        return mapper.valueToTree(answer);
    }

    private JsonNode aiSqlPreview(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        Nl2SqlRequest request = nl2SqlRequest(params);
        return mapper.valueToTree(context().getBean(Nl2SqlSafetyService.class).preview(request));
    }

    private JsonNode aiSqlGenerate(JsonNode params, CancellationToken cancellation,
                                   Consumer<LocalAppEvent> events) {
        cancellation.throwIfCancelled();
        emit(events, "ai.delta", "delta", "正在生成并执行 Java 安全评估…");
        var result = context().getBean(Nl2SqlSafetyService.class).generateAndAssess(nl2SqlRequest(params));
        cancellation.throwIfCancelled();
        return mapper.valueToTree(result);
    }

    private Nl2SqlRequest nl2SqlRequest(JsonNode params) {
        String connectionId = requiredText(params, "connectionId", 64);
        var profile = context().getBean(ConnectionManagementService.class).findProfile(connectionId)
            .orElseThrow(() -> new IllegalArgumentException("Database connection was not found"));
        if (!profile.enabled()) throw new IllegalArgumentException("Database connection is disabled");
        return new Nl2SqlRequest(requiredText(params, "question", 2_000), profile.id(), profile.dialect());
    }

    private JsonNode aiExerciseExplain(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        ArrayNode feedback = mapper.createArrayNode();
        params.withArray("feedback").forEach(item -> feedback.add(item.asText("")));
        var request = new ExerciseExplainRequest(
            requiredText(params, "title", 240),
            params.path("description").asText(""),
            params.path("knowledgePoint").asText(""),
            params.path("exerciseType").asText("QUERY"),
            requiredText(params, "answer", 256 * 1024),
            mapper.convertValue(feedback, mapper.getTypeFactory().constructCollectionType(List.class, String.class))
        );
        // 展示型讲解草稿：只返回文本，不写入任何学习状态。
        return mapper.valueToTree(context().getBean(ExerciseTextDraftingService.class).explainFailure(request));
    }
}
