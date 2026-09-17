package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.application.ai.AiProviderKind;
import com.sqlteacher.application.ai.AiProviderProfile;
import com.sqlteacher.application.ai.AiProviderProfileDraft;
import com.sqlteacher.application.ai.AiProviderProfileService;
import com.sqlteacher.application.ai.AiProviderProbeService;
import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.exercise.ExerciseExplainRequest;
import com.sqlteacher.application.exercise.ExerciseTextDraftingService;
import com.sqlteacher.application.knowledge.CourseKnowledgeSearchFilter;
import com.sqlteacher.application.knowledge.GroundedKnowledgeExplanationService;
import com.sqlteacher.application.nl2sql.Nl2SqlRequest;
import com.sqlteacher.application.nl2sql.Nl2SqlSafetyService;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** v3.4.0 REF-8: AI assistance methods. AI only drafts; deterministic services stay authoritative. */
final class AiApiSection extends ApiSection {

    AiApiSection(ApiSectionHost host) {
        super(host);
    }

    @Override
    public Set<String> supportedMethods() {
        return Set.of("ai.knowledge.ask", "ai.sql.preview", "ai.sql.generate", "ai.exercise.explain",
            "ai.provider.list", "ai.provider.save", "ai.provider.activate", "ai.provider.deactivate",
            "ai.provider.remove", "ai.provider.test");
    }

    @Override
    public JsonNode handle(String method, JsonNode params, CancellationToken cancellation,
                           Consumer<LocalAppEvent> events) throws Exception {
        return switch (method) {
            case "ai.knowledge.ask" -> aiKnowledgeAsk(params, cancellation, events);
            case "ai.sql.preview" -> aiSqlPreview(params, cancellation);
            case "ai.sql.generate" -> aiSqlGenerate(params, cancellation, events);
            case "ai.exercise.explain" -> aiExerciseExplain(params, cancellation);
            case "ai.provider.list" -> aiProviderList(cancellation);
            case "ai.provider.save" -> aiProviderSave(params, cancellation);
            case "ai.provider.activate" -> aiProviderActivate(params, cancellation);
            case "ai.provider.deactivate" -> aiProviderDeactivate(cancellation);
            case "ai.provider.remove" -> aiProviderRemove(params, cancellation);
            case "ai.provider.test" -> aiProviderTest(params, cancellation);
            default -> throw new IllegalStateException("Method whitelist and dispatcher are inconsistent");
        };
    }

    private JsonNode aiKnowledgeAsk(JsonNode params, CancellationToken cancellation,
                                    Consumer<LocalAppEvent> events) {
        cancellation.throwIfCancelled();
        String question = requiredText(params, "question", 2_000);
        var answer = context().getBean(GroundedKnowledgeExplanationService.class)
            .explain(question, knowledgeAskFilter(params.path("context")));
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

    /**
     * v3.6.0 KBF-1: 阅读上下文只经结构化字段进入检索过滤，不再拼接进问题文本（前缀会被
     * FTS 硬 AND 查询整体吞掉命中）。课程与章节均缺省时保持全库检索语义，旧前端兼容。
     */
    private static CourseKnowledgeSearchFilter knowledgeAskFilter(JsonNode context) {
        if (context == null || !context.isObject()) {
            return CourseKnowledgeSearchFilter.allLocal();
        }
        String courseTitle = optionalContextText(context, "courseTitle");
        String sectionTitle = optionalContextText(context, "sectionTitle");
        if (courseTitle.isEmpty() && sectionTitle.isEmpty()) {
            return CourseKnowledgeSearchFilter.allLocal();
        }
        return new CourseKnowledgeSearchFilter(courseTitle, sectionTitle, "", true);
    }

    private static String optionalContextText(JsonNode context, String field) {
        String value = context.path(field).asText("").trim();
        if (value.length() > 240) {
            throw new IllegalArgumentException(field + " must contain at most 240 characters");
        }
        return value;
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


    // ---- v3.4.4 AIS：本地/网络 AI 模型配置。凭据只写不读（DPAPI 加密存储在 Java 侧），
    // 列表与测试响应不含任何密钥材料；端点校验沿用 AiProviderProfile 的 HTTPS/回环规则。 ----

    private JsonNode aiProviderList(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return providerPayload();
    }

    private JsonNode aiProviderSave(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        AiProviderProfileDraft draft = providerDraft(params);
        char[] credential = params.path("credential").asText("").toCharArray();
        try {
            context().getBean(AiProviderProfileService.class).save(draft, credential);
        } finally {
            Arrays.fill(credential, '\u0000');
        }
        cancellation.throwIfCancelled();
        return providerPayload();
    }

    private JsonNode aiProviderActivate(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        context().getBean(AiProviderProfileService.class).activate(requiredText(params, "id", 64));
        return providerPayload();
    }

    private JsonNode aiProviderDeactivate(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        context().getBean(AiProviderProfileService.class).deactivate();
        return providerPayload();
    }

    private JsonNode aiProviderRemove(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        context().getBean(AiProviderProfileService.class).remove(requiredText(params, "id", 64));
        return providerPayload();
    }

    private JsonNode aiProviderTest(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        AiProviderProfileDraft draft = providerDraft(params);
        char[] credential = params.path("credential").asText("").toCharArray();
        try {
            var result = context().getBean(AiProviderProbeService.class).probe(draft, credential);
            ObjectNode node = mapper.createObjectNode();
            node.put("success", result.success());
            node.put("message", result.message());
            node.set("models", mapper.valueToTree(result.models()));
            return node;
        } finally {
            Arrays.fill(credential, '\u0000');
        }
    }

    private AiProviderProfileDraft providerDraft(JsonNode params) {
        String id = params.path("id").asText("").trim();
        if (id.isEmpty()) {
            id = "network-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return new AiProviderProfileDraft(
            id,
            requiredText(params, "displayName", 120),
            AiProviderKind.valueOf(requiredText(params, "kind", 32)),
            URI.create(requiredText(params, "endpoint", 512)),
            requiredText(params, "model", 120),
            params.path("enabled").asBoolean(true));
    }

    private JsonNode providerPayload() {
        AiProviderProfileService service = context().getBean(AiProviderProfileService.class);
        String activeId = service.activeProfile().map(AiProviderProfile::id).orElse("");
        ObjectNode payload = mapper.createObjectNode();
        ArrayNode items = payload.putArray("items");
        service.profiles().forEach(profile -> {
            ObjectNode item = items.addObject();
            item.put("id", profile.id());
            item.put("displayName", profile.displayName());
            item.put("kind", profile.kind().name());
            item.put("endpoint", profile.endpoint().toString());
            item.put("model", profile.model());
            item.put("enabled", profile.enabled());
            item.put("active", profile.id().equals(activeId));
        });
        payload.put("activeProfileId", activeId);
        return payload;
    }
}
