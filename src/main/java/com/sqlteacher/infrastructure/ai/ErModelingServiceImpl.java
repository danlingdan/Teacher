package com.sqlteacher.infrastructure.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.ai.AiCompletionRequest;
import com.sqlteacher.application.ai.AiCompletionResult;
import com.sqlteacher.application.ai.AiModelProvider;
import com.sqlteacher.application.ai.AiModelSelection;
import com.sqlteacher.application.ai.AiModelSelectionService;
import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.ermodel.ErGenerationStatus;
import com.sqlteacher.application.ermodel.ErModelingRequest;
import com.sqlteacher.application.ermodel.ErModelingResult;
import com.sqlteacher.application.ermodel.ErModelingService;
import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.domain.ermodel.ErAttribute;
import com.sqlteacher.domain.ermodel.ErEntity;
import com.sqlteacher.domain.ermodel.ErHierarchy;
import com.sqlteacher.domain.ermodel.ErModel;
import com.sqlteacher.domain.ermodel.ErModelValidator;
import com.sqlteacher.domain.ermodel.ErRelationship;
import com.sqlteacher.domain.ermodel.ErRelationshipMember;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * v3.11.0 ERM-3: AI-assisted Chen-notation E-R modeling. The AI output is untrusted: it is
 * leniently parsed, normalized (control characters stripped), and fully validated by
 * {@link ErModelValidator} before it may leave this service. PARSE_ERROR and VALIDATION_FAILED
 * allow exactly one repair retry with the aggregated error list fed back into the prompt; every
 * other failure degrades immediately. No failure ever throws across the service boundary, and
 * learning events never carry the requirement text or the model output.
 */
public final class ErModelingServiceImpl implements ErModelingService {

    private static final Logger log = LoggerFactory.getLogger(ErModelingServiceImpl.class);
    public static final String PROMPT_VERSION = "er-modeling-v1";
    static final int MAX_REQUIREMENT_LENGTH = 2_000;
    /** The event feed keys AI records by connection; ER modeling has no database, so a stable
     * feature label is used instead of an empty id (which the event store rejects). */
    static final String EVENT_CONNECTION_ID = "er-modeling";
    private static final String PARSE_ERROR_HINT = "上一次输出不是一个合法的 JSON 对象";

    private final AiModelProvider aiModelProvider;
    private final AiConfiguration aiConfiguration;
    private final AiModelSelectionService modelSelectionService;
    private final LearningEventService learningEventService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ErModelingServiceImpl(
        AiModelProvider aiModelProvider,
        AiConfiguration aiConfiguration,
        LearningEventService learningEventService
    ) {
        this(aiModelProvider, aiConfiguration, AiModelSelectionService.fixed(aiConfiguration.defaultModel()),
            learningEventService);
    }

    public ErModelingServiceImpl(
        AiModelProvider aiModelProvider,
        AiConfiguration aiConfiguration,
        AiModelSelectionService modelSelectionService,
        LearningEventService learningEventService
    ) {
        this.aiModelProvider = Objects.requireNonNull(aiModelProvider, "aiModelProvider must not be null");
        this.aiConfiguration = Objects.requireNonNull(aiConfiguration, "aiConfiguration must not be null");
        this.modelSelectionService = Objects.requireNonNull(
            modelSelectionService,
            "modelSelectionService must not be null"
        );
        this.learningEventService = Objects.requireNonNull(learningEventService, "learningEventService must not be null");
    }

    @Override
    public ErModelingResult generate(ErModelingRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String requirement = request.requirement() == null ? "" : request.requirement().strip();
        if (requirement.isEmpty() || requirement.length() > MAX_REQUIREMENT_LENGTH) {
            throw new IllegalArgumentException(
                "requirement must contain 1 to " + MAX_REQUIREMENT_LENGTH + " characters");
        }

        String prompt = preparePrompt(requirement, "");
        if (prompt == null) {
            String model = aiConfiguration.defaultModel();
            recordEvent(false, model, ErGenerationStatus.PROMPT_ERROR.name());
            return ErModelingResult.failure(
                ErGenerationStatus.PROMPT_ERROR, model, PROMPT_VERSION,
                "提示词准备失败，请稍后重试。", List.of());
        }

        String selectedModel = resolveSelectedModel();
        if (selectedModel.isBlank()) {
            String model = aiConfiguration.defaultModel();
            recordEvent(false, model, ErGenerationStatus.MODEL_UNAVAILABLE.name());
            return ErModelingResult.failure(
                ErGenerationStatus.MODEL_UNAVAILABLE, model, PROMPT_VERSION,
                "未检测到可用的 AI 模型，请先在设置中选择模型后再生成。", List.of());
        }

        Attempt attempt = requestGeneration(selectedModel, prompt);
        if (attempt.retryable()) {
            String repairPrompt = preparePrompt(requirement, repairHint(attempt));
            if (repairPrompt != null) {
                attempt = requestGeneration(selectedModel, repairPrompt);
            }
        }

        if (attempt.status() == ErGenerationStatus.OK) {
            recordEvent(true, selectedModel, null);
            ErModel erModel = attempt.erModel();
            String message = "已生成 E-R 图：" + erModel.entities().size() + " 个实体、"
                + erModel.relationships().size() + " 个联系、"
                + erModel.hierarchies().size() + " 个层级。";
            return ErModelingResult.ok(selectedModel, PROMPT_VERSION, erModel, message);
        }
        recordEvent(false, selectedModel, attempt.status().name());
        return ErModelingResult.failure(
            attempt.status(), selectedModel, PROMPT_VERSION, attempt.message(), attempt.errors());
    }

    /** Returns null when the template cannot be rendered (caller degrades to PROMPT_ERROR). */
    private String preparePrompt(String requirement, String validationErrors) {
        try {
            return PromptTemplateLoader.render("/prompts/er-modeling-v1.txt", Map.of(
                "requirement", requirement,
                "validationErrors", validationErrors
            ));
        } catch (RuntimeException error) {
            log.warn("Failed to prepare the ER modeling prompt", error);
            return null;
        }
    }

    private String repairHint(Attempt attempt) {
        if (attempt.errors().isEmpty()) {
            return PARSE_ERROR_HINT;
        }
        return String.join("\n", attempt.errors());
    }

    private Attempt requestGeneration(String model, String prompt) {
        AiCompletionResult result;
        try {
            result = aiModelProvider.complete(
                new AiCompletionRequest(model, prompt, aiConfiguration.generateTimeout()));
        } catch (RuntimeException error) {
            log.warn("ER modeling provider call failed", error);
            return Attempt.providerFailure("AI 服务调用失败，请检查模型服务是否可用。");
        }
        if (!result.success()) {
            log.warn("ER modeling provider returned a failure: {}", result.errorMessage());
            return Attempt.providerFailure("AI 服务调用失败：" + redact(result.errorMessage()));
        }

        JsonNode root;
        try {
            root = parseJsonObject(result.content());
        } catch (RuntimeException error) {
            log.warn("Failed to parse the ER modeling response", error);
            return Attempt.parseFailure();
        }
        ErModel erModel = toDomainModel(root);
        List<String> errors = ErModelValidator.validate(erModel);
        if (!errors.isEmpty()) {
            return new Attempt(ErGenerationStatus.VALIDATION_FAILED, null, errors,
                "AI 生成的 E-R 模型未通过校验，请调整需求后重试。");
        }
        return new Attempt(ErGenerationStatus.OK, erModel, List.of(), "");
    }

    /** Accepts a bare JSON object plus prose/fence-wrapped variants; otherwise signals PARSE_ERROR. */
    private JsonNode parseJsonObject(String content) {
        String text = content == null ? "" : content.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("AI response is empty");
        }
        JsonNode direct = tryReadTree(text);
        if (direct != null && direct.isObject()) {
            return direct;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("AI response contains no JSON object");
        }
        JsonNode embedded = tryReadTree(text.substring(start, end + 1));
        if (embedded == null || !embedded.isObject()) {
            throw new IllegalArgumentException("AI response contains no JSON object");
        }
        return embedded;
    }

    /** Returns null when the text is not valid JSON so the prose-wrapped fallback can run. */
    private JsonNode tryReadTree(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (JsonProcessingException error) {
            return null;
        }
    }

    /** Lenient mapping (plan §4): missing booleans are false, missing arrays are empty, control
     * characters are stripped from every text field before validation. */
    private static ErModel toDomainModel(JsonNode root) {
        List<ErEntity> entities = new ArrayList<>();
        for (JsonNode node : arrayNodes(root.path("entities"))) {
            List<ErAttribute> attributes = new ArrayList<>();
            for (JsonNode attributeNode : arrayNodes(node.path("attributes"))) {
                attributes.add(new ErAttribute(
                    cleanText(attributeNode.path("name")),
                    attributeNode.path("key").asBoolean(false),
                    cleanTextList(attributeNode.path("composite")),
                    attributeNode.path("multivalued").asBoolean(false),
                    attributeNode.path("derived").asBoolean(false)
                ));
            }
            entities.add(new ErEntity(
                cleanText(node.path("id")),
                cleanText(node.path("name")),
                node.path("weak").asBoolean(false),
                attributes
            ));
        }

        List<ErRelationship> relationships = new ArrayList<>();
        for (JsonNode node : arrayNodes(root.path("relationships"))) {
            List<ErRelationshipMember> members = new ArrayList<>();
            for (JsonNode memberNode : arrayNodes(node.path("members"))) {
                members.add(new ErRelationshipMember(
                    cleanText(memberNode.path("entity")),
                    cleanText(memberNode.path("cardinality")),
                    memberNode.path("total").asBoolean(false)
                ));
            }
            List<ErAttribute> attributes = new ArrayList<>();
            for (JsonNode attributeNode : arrayNodes(node.path("attributes"))) {
                attributes.add(new ErAttribute(
                    cleanText(attributeNode.path("name")),
                    attributeNode.path("key").asBoolean(false),
                    cleanTextList(attributeNode.path("composite")),
                    attributeNode.path("multivalued").asBoolean(false),
                    attributeNode.path("derived").asBoolean(false)
                ));
            }
            relationships.add(new ErRelationship(
                cleanText(node.path("id")),
                cleanText(node.path("name")),
                node.path("identifying").asBoolean(false),
                members,
                attributes
            ));
        }

        List<ErHierarchy> hierarchies = new ArrayList<>();
        for (JsonNode node : arrayNodes(root.path("hierarchies"))) {
            hierarchies.add(new ErHierarchy(
                cleanText(node.path("id")),
                cleanText(node.path("name")),
                cleanText(node.path("superclass")),
                cleanTextList(node.path("subclasses"))
            ));
        }

        return new ErModel(
            cleanText(root.path("title")),
            cleanText(root.path("explanation")),
            entities,
            relationships,
            hierarchies
        );
    }

    private static Iterable<JsonNode> arrayNodes(JsonNode node) {
        return node.isArray() ? node : List.<JsonNode>of();
    }

    private static List<String> cleanTextList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(cleanText(item)));
        return List.copyOf(values);
    }

    private static String cleanText(JsonNode node) {
        return cleanString(node.asText(""));
    }

    private static String cleanString(String raw) {
        StringBuilder cleaned = new StringBuilder(raw.length());
        raw.chars()
            .filter(codePoint -> !Character.isISOControl(codePoint))
            .forEach(cleaned::appendCodePoint);
        return cleaned.toString().strip();
    }

    /** Provider messages may embed endpoint or IO details; only a bounded class-level hint crosses out. */
    private static String redact(String providerMessage) {
        String cleaned = cleanString(providerMessage == null ? "" : providerMessage);
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }

    private String resolveSelectedModel() {
        String preferred = aiModelProvider.preferredModel();
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        AiModelSelection selection = modelSelectionService.current();
        if (!selection.hasSelection()) {
            selection = modelSelectionService.refresh();
        }
        return selection.selectedModel();
    }

    private void recordEvent(boolean successful, String model, String errorCode) {
        try {
            learningEventService.recordAiGeneration(
                EVENT_CONNECTION_ID, successful, model, PROMPT_VERSION, errorCode);
        } catch (RuntimeException error) {
            log.warn("Failed to record the ER modeling learning event", error);
        }
    }

    /** One provider round trip: a terminal status plus its (already redacted) user-facing pieces. */
    private record Attempt(
        ErGenerationStatus status,
        ErModel erModel,
        List<String> errors,
        String message
    ) {
        static Attempt providerFailure(String message) {
            return new Attempt(ErGenerationStatus.PROVIDER_ERROR, null, List.of(), message);
        }

        static Attempt parseFailure() {
            return new Attempt(ErGenerationStatus.PARSE_ERROR, null, List.of(),
                "AI 输出无法解析为有效的 JSON，已重试仍失败，请重新生成。");
        }

        boolean retryable() {
            return status == ErGenerationStatus.PARSE_ERROR || status == ErGenerationStatus.VALIDATION_FAILED;
        }
    }
}
