package com.sqlteacher.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.ai.AiCompletionRequest;
import com.sqlteacher.application.ai.AiCompletionResult;
import com.sqlteacher.application.ai.AiModelProvider;
import com.sqlteacher.application.ai.AiModelSelection;
import com.sqlteacher.application.ai.AiModelSelectionService;
import com.sqlteacher.application.ai.AiContextCategory;
import com.sqlteacher.application.ai.AiContextItem;
import com.sqlteacher.application.ai.AiContextPolicy;
import com.sqlteacher.application.ai.AiContextPreview;
import com.sqlteacher.application.ai.AiPreparedContext;
import com.sqlteacher.application.ai.AiTaskRequest;
import com.sqlteacher.application.ai.AiTaskResult;
import com.sqlteacher.application.ai.AiTaskService;
import com.sqlteacher.application.ai.AiTaskType;
import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.connection.DatabaseDialect;
import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.metadata.DatabaseColumn;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.metadata.DatabaseTable;
import com.sqlteacher.application.nl2sql.Nl2SqlPlan;
import com.sqlteacher.application.nl2sql.Nl2SqlRequest;
import com.sqlteacher.application.nl2sql.Nl2SqlService;
import com.sqlteacher.application.nl2sql.SqlErrorExplanation;
import com.sqlteacher.infrastructure.ai.dto.OllamaNl2SqlResponse;
import com.sqlteacher.infrastructure.ai.dto.OllamaSqlErrorResponse;
import com.sqlteacher.domain.SqlTeacherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class Nl2SqlServiceImpl implements Nl2SqlService {
    private static final Logger log = LoggerFactory.getLogger(Nl2SqlServiceImpl.class);
    private static final String PROMPT_VERSION = "v5";
    private static final Set<String> VALID_INTENTS = Set.of("QUERY", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER");
    private final AiModelProvider aiModelProvider;
    private final AiConfiguration aiConfiguration;
    private final AiModelSelectionService modelSelectionService;
    private final DatabaseMetadataService databaseMetadataService;
    private final LearningEventService learningEventService;
    private final ObjectMapper objectMapper;
    private final ConnectionManagementService connectionManagementService;
    private final AiTaskService aiTaskService;
    private final AiContextPolicy contextPolicy;

    public Nl2SqlServiceImpl(
        AiModelProvider aiModelProvider,
        AiConfiguration aiConfiguration,
        DatabaseMetadataService databaseMetadataService,
        LearningEventService learningEventService
    ) {
        this(
            aiModelProvider,
            aiConfiguration,
            AiModelSelectionService.fixed(aiConfiguration.defaultModel()),
            databaseMetadataService,
            learningEventService,
            null,
            null,
            new DefaultAiContextPolicy()
        );
    }

    public Nl2SqlServiceImpl(
        AiModelProvider aiModelProvider,
        AiConfiguration aiConfiguration,
        AiModelSelectionService modelSelectionService,
        DatabaseMetadataService databaseMetadataService,
        LearningEventService learningEventService
    ) {
        this(
            aiModelProvider,
            aiConfiguration,
            modelSelectionService,
            databaseMetadataService,
            learningEventService,
            null,
            null,
            new DefaultAiContextPolicy()
        );
    }

    public Nl2SqlServiceImpl(
        AiModelProvider aiModelProvider,
        AiConfiguration aiConfiguration,
        AiModelSelectionService modelSelectionService,
        DatabaseMetadataService databaseMetadataService,
        LearningEventService learningEventService,
        ConnectionManagementService connectionManagementService
    ) {
        this(
            aiModelProvider,
            aiConfiguration,
            modelSelectionService,
            databaseMetadataService,
            learningEventService,
            connectionManagementService,
            null,
            new DefaultAiContextPolicy()
        );
    }

    public Nl2SqlServiceImpl(
        AiModelProvider aiModelProvider,
        AiConfiguration aiConfiguration,
        AiModelSelectionService modelSelectionService,
        DatabaseMetadataService databaseMetadataService,
        LearningEventService learningEventService,
        ConnectionManagementService connectionManagementService,
        AiTaskService aiTaskService,
        AiContextPolicy contextPolicy
    ) {
        this.aiModelProvider = Objects.requireNonNull(aiModelProvider, "aiModelProvider must not be null");
        this.aiConfiguration = Objects.requireNonNull(aiConfiguration, "aiConfiguration must not be null");
        this.modelSelectionService = Objects.requireNonNull(
            modelSelectionService,
            "modelSelectionService must not be null"
        );
        this.databaseMetadataService = Objects.requireNonNull(
            databaseMetadataService,
            "databaseMetadataService must not be null"
        );
        this.learningEventService = Objects.requireNonNull(
            learningEventService,
            "learningEventService must not be null"
        );
        this.connectionManagementService = connectionManagementService;
        this.aiTaskService = aiTaskService;
        this.contextPolicy = Objects.requireNonNull(contextPolicy, "contextPolicy must not be null");
        this.objectMapper = new ObjectMapper();
    }

    public Nl2SqlServiceImpl(
        AiTaskService aiTaskService,
        AiConfiguration aiConfiguration,
        AiModelSelectionService modelSelectionService,
        DatabaseMetadataService databaseMetadataService,
        LearningEventService learningEventService,
        ConnectionManagementService connectionManagementService,
        AiContextPolicy contextPolicy
    ) {
        this.aiModelProvider = null;
        this.aiTaskService = Objects.requireNonNull(aiTaskService);
        this.aiConfiguration = Objects.requireNonNull(aiConfiguration);
        this.modelSelectionService = Objects.requireNonNull(modelSelectionService);
        this.databaseMetadataService = Objects.requireNonNull(databaseMetadataService);
        this.learningEventService = Objects.requireNonNull(learningEventService);
        this.connectionManagementService = connectionManagementService;
        this.contextPolicy = Objects.requireNonNull(contextPolicy);
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Nl2SqlPlan generate(Nl2SqlRequest request) {
        return generateInternal(request, null, null);
    }

    @Override
    public Nl2SqlPlan revise(Nl2SqlRequest request, Nl2SqlPlan previous, String instruction) {
        Objects.requireNonNull(previous, "previous must not be null");
        if (instruction == null || instruction.isBlank()) throw new IllegalArgumentException("instruction must not be blank");
        return generateInternal(request, previous, instruction.strip());
    }

    @Override
    public AiContextPreview preview(Nl2SqlRequest request) {
        validateRequest(request);
        return preparePrompt(request, null, null).preview();
    }

    @Override
    public AiContextPreview previewRevision(Nl2SqlRequest request, Nl2SqlPlan previous, String instruction) {
        validateRequest(request);
        Objects.requireNonNull(previous, "previous must not be null");
        if (instruction == null || instruction.isBlank()) throw new IllegalArgumentException("instruction must not be blank");
        return preparePrompt(request, previous, instruction.strip()).preview();
    }

    private Nl2SqlPlan generateInternal(Nl2SqlRequest request, Nl2SqlPlan previous, String instruction) {
        Objects.requireNonNull(request, "request must not be null");
        validateRequest(request);

        PreparedPrompt prepared;
        try {
            prepared = preparePrompt(request, previous, instruction);
        } catch (SqlTeacherException error) {
            recordAiGeneration(request.connectionId(), false, aiConfiguration.defaultModel(), error.errorCode());
            return new Nl2SqlPlan(
                "",
                "",
                error.getMessage(),
                aiConfiguration.defaultModel(),
                PROMPT_VERSION
            );
        }
        String selectedModel = resolveSelectedModel();
        if (selectedModel.isEmpty()) {
            return unavailableModelPlan(request.connectionId());
        }
        AiTaskResult aiResult = complete(AiTaskType.NL2SQL, selectedModel, prepared);

        if (!aiResult.success()) {
            recordAiGeneration(request.connectionId(), false, aiResult.model(), aiResult.errorCode().name());
            return new Nl2SqlPlan(
                "",
                "",
                aiResult.message(),
                aiResult.model(),
                prepared.version()
            );
        }

        try {
            OllamaNl2SqlResponse response = objectMapper.readValue(aiResult.content(), OllamaNl2SqlResponse.class);

            String validationError = validateAiResponse(response);
            if (validationError != null) {
                log.warn("AI response validation failed: {}", validationError);
                recordAiGeneration(request.connectionId(), false, aiResult.model(), "VALIDATION_FAILED");
                return new Nl2SqlPlan(
                    "",
                    "",
                    validationError,
                    aiResult.model(),
                    prepared.version()
                );
            }

            recordAiGeneration(request.connectionId(), true, aiResult.model(), null);
            return new Nl2SqlPlan(
                response.sqlDraft(),
                response.intent(),
                response.explanation(),
                aiResult.model(),
                prepared.version()
            );
        } catch (Exception ex) {
            log.warn("Failed to parse AI response", ex);
            recordAiGeneration(request.connectionId(), false, aiResult.model(), "PARSE_ERROR");
            return new Nl2SqlPlan(
                "",
                "",
                "Failed to parse AI response: " + ex.getClass().getSimpleName(),
                aiResult.model(),
                prepared.version()
            );
        }
    }

    private void validateRequest(Nl2SqlRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.naturalLanguage() == null || request.naturalLanguage().isBlank()) throw new IllegalArgumentException("naturalLanguage must not be blank");
        if (request.connectionId() == null || request.connectionId().isBlank()) throw new IllegalArgumentException("connectionId must not be blank");
    }

    private void recordAiGeneration(String connectionId, boolean successful, String model, String errorCode) {
        try {
            learningEventService.recordAiGeneration(connectionId, successful, model, PROMPT_VERSION, errorCode);
        } catch (Exception ex) {
            log.warn("Failed to record AI generation event", ex);
        }
    }

    private String validateAiResponse(OllamaNl2SqlResponse response) {
        if (response.sqlDraft() == null || response.sqlDraft().isBlank()) {
            return "AI generated empty SQL draft";
        }

        String intent = response.intent() == null ? "" : response.intent().trim().toUpperCase(java.util.Locale.ROOT);
        if (!VALID_INTENTS.contains(intent)) {
            return "AI generated invalid intent: " + response.intent();
        }

        if (response.explanation() == null || response.explanation().isBlank()) {
            return "AI generated empty explanation";
        }

        return null;
    }

    private PreparedPrompt preparePrompt(Nl2SqlRequest request, Nl2SqlPlan previous, String instruction) {
        List<AiContextItem> items = new java.util.ArrayList<>();
        items.add(new AiContextItem(AiContextCategory.USER_REQUEST, "用户当前请求", request.naturalLanguage()));
        if (instruction != null) items.add(new AiContextItem(AiContextCategory.USER_REQUEST, "本轮修订要求", instruction));
        items.add(new AiContextItem(AiContextCategory.DATABASE_SCHEMA, "所选数据库结构", buildTableSchema(request.connectionId())));
        if (previous != null) items.add(new AiContextItem(AiContextCategory.SQL_DRAFT, "上一版 SQL 草稿", previous.sqlDraft()));
        AiPreparedContext context = contextPolicy.prepare(AiTaskType.NL2SQL, items);
        Map<AiContextCategory, List<String>> grouped = context.items().stream().collect(java.util.stream.Collectors.groupingBy(
            AiContextItem::category, java.util.LinkedHashMap::new, java.util.stream.Collectors.mapping(AiContextItem::content, java.util.stream.Collectors.toList())));
        String databaseName = databaseDialect(request.connectionId()).name();
        if (previous == null) return new PreparedPrompt(PromptTemplateLoader.render("/prompts/nl2sql-v4.txt", Map.of(
            "database", databaseName,
            "schema", first(grouped, AiContextCategory.DATABASE_SCHEMA),
            "naturalLanguage", first(grouped, AiContextCategory.USER_REQUEST)
        )), context.preview(), PROMPT_VERSION);
        return new PreparedPrompt(PromptTemplateLoader.render("/prompts/nl2sql-revision-v1.txt", Map.of(
            "database", databaseName,
            "schema", first(grouped, AiContextCategory.DATABASE_SCHEMA),
            "naturalLanguage", grouped.getOrDefault(AiContextCategory.USER_REQUEST, List.of()).get(0),
            "previousSql", first(grouped, AiContextCategory.SQL_DRAFT),
            "instruction", nth(grouped, AiContextCategory.USER_REQUEST, 1)
        )), context.preview(), "revision-v1");
    }

    private String buildTableSchema(String connectionId) {
        try {
            List<DatabaseTable> tables = databaseMetadataService.listTables(connectionId);
            if (tables == null || tables.isEmpty()) {
                return getDefaultTableSchema();
            }
            StringBuilder sb = new StringBuilder();
            for (DatabaseTable table : tables) {
                sb.append("  - ").append(table.name());
                sb.append(" (");
                List<String> columnNames = table.columns().stream()
                    .map(DatabaseColumn::name)
                    .toList();
                sb.append(String.join(", ", columnNames));
                sb.append(")\n");
            }
            return sb.toString().trim();
        } catch (SqlTeacherException error) {
            throw error;
        } catch (Exception ex) {
            return getDefaultTableSchema();
        }
    }

    private DatabaseDialect databaseDialect(String connectionId) {
        if (connectionManagementService == null) {
            return DatabaseDialect.SQLITE;
        }
        return connectionManagementService.findProfile(connectionId)
            .map(profile -> profile.dialect())
            .orElseThrow(() -> new SqlTeacherException(
                "DATABASE_CONNECTION_NOT_FOUND",
                "找不到所选数据库连接，请在设置页重新选择。"
            ));
    }

    private String getDefaultTableSchema() {
        return "  - student (id, name, score, class_id)\n  - class (id, name, teacher)";
    }

    @Override
    public SqlErrorExplanation explainSqlError(String connectionId, String sql, String errorMessage) {
        Objects.requireNonNull(connectionId, "connectionId must not be null");
        Objects.requireNonNull(sql, "sql must not be null");
        Objects.requireNonNull(errorMessage, "errorMessage must not be null");
        if (connectionId.isBlank()) {
            throw new IllegalArgumentException("connectionId must not be blank");
        }
        if (sql.isBlank()) {
            throw new IllegalArgumentException("sql must not be blank");
        }
        if (errorMessage.isBlank()) {
            throw new IllegalArgumentException("errorMessage must not be blank");
        }

        PreparedPrompt prepared = buildErrorExplanationPrompt(sql, errorMessage, connectionId);
        String selectedModel = resolveSelectedModel();
        if (selectedModel.isEmpty()) {
            return SqlErrorExplanation.failure(
                "No local Ollama model is installed",
                aiConfiguration.defaultModel()
            );
        }
        AiTaskResult aiResult = complete(AiTaskType.SQL_ERROR_EXPLANATION, selectedModel, prepared);

        if (!aiResult.success()) {
            recordAiGeneration(connectionId, false, aiResult.model(), "AI_PROVIDER_FAILED");
            return SqlErrorExplanation.failure(aiResult.message(), aiResult.model());
        }

        try {
            OllamaSqlErrorResponse response = objectMapper.readValue(aiResult.content(), OllamaSqlErrorResponse.class);

            String validationError = validateErrorExplanationResponse(response);
            if (validationError != null) {
                log.warn("AI error explanation validation failed: {}", validationError);
                recordAiGeneration(connectionId, false, aiResult.model(), "VALIDATION_FAILED");
                return SqlErrorExplanation.failure(validationError, aiResult.model());
            }

            recordAiGeneration(connectionId, true, aiResult.model(), null);
            return SqlErrorExplanation.success(
                response.errorCause(),
                response.correctionSuggestion(),
                response.correctedSql(),
                aiResult.model()
            );
        } catch (Exception ex) {
            log.warn("Failed to parse AI error explanation response", ex);
            recordAiGeneration(connectionId, false, aiResult.model(), "PARSE_ERROR");
            return SqlErrorExplanation.failure("Failed to parse AI error explanation: " + ex.getClass().getSimpleName(), aiResult.model());
        }
    }

    private String resolveSelectedModel() {
        String preferred = aiTaskService == null ? aiModelProvider.preferredModel() : aiTaskService.preferredModel();
        if (!preferred.isBlank()) {
            return preferred;
        }
        AiModelSelection selection = modelSelectionService.current();
        if (!selection.hasSelection()) {
            selection = modelSelectionService.refresh();
        }
        return selection.selectedModel();
    }

    private Nl2SqlPlan unavailableModelPlan(String connectionId) {
        String model = aiConfiguration.defaultModel();
        recordAiGeneration(connectionId, false, model, "MODEL_UNAVAILABLE");
        return new Nl2SqlPlan(
            "",
            "",
            "No local Ollama model is installed. Install a model or refresh the model list.",
            model,
            PROMPT_VERSION
        );
    }

    private String validateErrorExplanationResponse(OllamaSqlErrorResponse response) {
        if (response.errorCause().isBlank()) {
            return "AI generated empty error cause";
        }
        if (response.correctionSuggestion().isBlank()) {
            return "AI generated empty correction suggestion";
        }
        if (response.correctedSql().isBlank()) {
            return "AI generated empty corrected SQL draft";
        }
        return null;
    }

    private PreparedPrompt buildErrorExplanationPrompt(String sql, String errorMessage, String connectionId) {
        AiPreparedContext context = contextPolicy.prepare(AiTaskType.SQL_ERROR_EXPLANATION, List.of(
            new AiContextItem(AiContextCategory.DATABASE_SCHEMA, "所选数据库结构", buildTableSchema(connectionId)),
            new AiContextItem(AiContextCategory.SQL_DRAFT, "失败 SQL 草稿", sql),
            new AiContextItem(AiContextCategory.SQL_ERROR, "脱敏后的数据库错误", errorMessage)
        ));
        Map<AiContextCategory, String> values = context.items().stream().collect(java.util.stream.Collectors.toMap(
            AiContextItem::category, AiContextItem::content, (left, right) -> left, java.util.LinkedHashMap::new));
        return new PreparedPrompt(PromptTemplateLoader.render("/prompts/sql-error-explanation-v1.txt", Map.of(
            "database", databaseDialect(connectionId).name(),
            "schema", values.getOrDefault(AiContextCategory.DATABASE_SCHEMA, ""),
            "sql", values.getOrDefault(AiContextCategory.SQL_DRAFT, ""),
            "errorMessage", values.getOrDefault(AiContextCategory.SQL_ERROR, "")
        )), context.preview(), "sql-error-v1");
    }

    private AiTaskResult complete(AiTaskType type, String model, PreparedPrompt prepared) {
        if (aiTaskService != null) return aiTaskService.execute(new AiTaskRequest(type, model, prepared.prompt(), prepared.version(), prepared.preview()));
        AiCompletionResult legacy = aiModelProvider.complete(new AiCompletionRequest(model, prepared.prompt(), aiConfiguration.generateTimeout()));
        return legacy.success() ? AiTaskResult.success(legacy.content(), legacy.model())
            : AiTaskResult.failure(com.sqlteacher.application.ai.AiTaskErrorCode.PROVIDER_UNAVAILABLE, legacy.errorMessage(), legacy.model());
    }

    private static String first(Map<AiContextCategory, List<String>> values, AiContextCategory category) {
        return values.getOrDefault(category, List.of("")).get(0);
    }

    private static String nth(Map<AiContextCategory, List<String>> values, AiContextCategory category, int index) {
        List<String> items = values.getOrDefault(category, List.of());
        return index < items.size() ? items.get(index) : "";
    }

    private record PreparedPrompt(String prompt, AiContextPreview preview, String version) { }
}
