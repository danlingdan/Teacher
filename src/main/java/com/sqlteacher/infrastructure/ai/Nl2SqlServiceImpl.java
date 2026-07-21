package com.sqlteacher.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.ai.AiCompletionRequest;
import com.sqlteacher.application.ai.AiCompletionResult;
import com.sqlteacher.application.ai.AiModelProvider;
import com.sqlteacher.application.ai.AiModelSelection;
import com.sqlteacher.application.ai.AiModelSelectionService;
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
import java.util.Objects;

public final class Nl2SqlServiceImpl implements Nl2SqlService {
    private static final Logger log = LoggerFactory.getLogger(Nl2SqlServiceImpl.class);
    private static final String PROMPT_VERSION = "v3";
    private final AiModelProvider aiModelProvider;
    private final AiConfiguration aiConfiguration;
    private final AiModelSelectionService modelSelectionService;
    private final DatabaseMetadataService databaseMetadataService;
    private final LearningEventService learningEventService;
    private final ObjectMapper objectMapper;
    private final ConnectionManagementService connectionManagementService;

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
            null
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
            null
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
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Nl2SqlPlan generate(Nl2SqlRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.naturalLanguage() == null || request.naturalLanguage().isBlank()) {
            throw new IllegalArgumentException("naturalLanguage must not be blank");
        }
        if (request.connectionId() == null || request.connectionId().isBlank()) {
            throw new IllegalArgumentException("connectionId must not be blank");
        }

        String prompt;
        try {
            prompt = buildPrompt(request.naturalLanguage(), request.connectionId());
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
        AiCompletionRequest aiRequest = new AiCompletionRequest(
            selectedModel,
            prompt,
            aiConfiguration.generateTimeout()
        );

        AiCompletionResult aiResult = aiModelProvider.complete(aiRequest);

        if (!aiResult.success()) {
            recordAiGeneration(request.connectionId(), false, aiResult.model(), "AI_PROVIDER_FAILED");
            return new Nl2SqlPlan(
                "",
                "",
                aiResult.errorMessage(),
                aiResult.model(),
                PROMPT_VERSION
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
                    PROMPT_VERSION
                );
            }

            recordAiGeneration(request.connectionId(), true, aiResult.model(), null);
            return new Nl2SqlPlan(
                response.sqlDraft(),
                response.intent(),
                response.explanation(),
                aiResult.model(),
                PROMPT_VERSION
            );
        } catch (Exception ex) {
            log.warn("Failed to parse AI response", ex);
            recordAiGeneration(request.connectionId(), false, aiResult.model(), "PARSE_ERROR");
            return new Nl2SqlPlan(
                "",
                "",
                "Failed to parse AI response: " + ex.getClass().getSimpleName(),
                aiResult.model(),
                PROMPT_VERSION
            );
        }
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

        if (!"QUERY".equalsIgnoreCase(response.intent())) {
            return "AI generated invalid intent: " + response.intent();
        }

        if (response.explanation() == null || response.explanation().isBlank()) {
            return "AI generated empty explanation";
        }

        return null;
    }

    private String buildPrompt(String naturalLanguage, String connectionId) {
        StringBuilder sb = new StringBuilder();
        String databaseName = databaseDialect(connectionId).name();
        sb.append("You are a SQL teacher assistant. Convert the following natural language query into a valid ")
            .append(databaseName).append(" SELECT statement.\n");
        sb.append("\n");
        sb.append("Database: ").append(databaseName).append("\n");
        sb.append("Available tables:\n");
        sb.append(buildTableSchema(connectionId));
        sb.append("\n");
        sb.append("IMPORTANT RULES:\n");
        sb.append("- ONLY generate SELECT statements\n");
        sb.append("- NO INSERT, UPDATE, DELETE, CREATE, DROP, ALTER, or any modifying statements\n");
        sb.append("- NO multiple statements separated by semicolons\n");
        sb.append("- Limit the result to at most 500 rows using a LIMIT clause\n");
        sb.append("- If the user requests a smaller number of rows, preserve that smaller LIMIT\n");
        sb.append("- Return ONLY a valid JSON object\n");
        sb.append("\n");
        sb.append("Natural language: ").append(naturalLanguage).append("\n");
        sb.append("\n");
        sb.append("Return ONLY a valid JSON object with these fields:\n");
        sb.append("- sqlDraft: the generated SELECT SQL statement\n");
        sb.append("- intent: must be \"QUERY\"\n");
        sb.append("- explanation: detailed teaching explanation including:\n");
        sb.append("  1. What the query does (purpose)\n");
        sb.append("  2. Which tables are involved\n");
        sb.append("  3. What conditions are used and why\n");
        sb.append("  4. What columns are selected\n");
        sb.append("  5. Expected result format\n");
        sb.append("\n");
        sb.append("Example output:\n");
        sb.append("{\"sqlDraft\": \"SELECT name, score FROM student WHERE score >= 60 LIMIT 500\", \"intent\": \"QUERY\", \"explanation\": \"该查询从student表中选取成绩大于等于60的学生记录，返回姓名和分数两列，限制最多500条结果。WHERE子句用于过滤符合条件的行。\"}");
        return sb.toString();
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

        String prompt = buildErrorExplanationPrompt(sql, errorMessage, connectionId);
        String selectedModel = resolveSelectedModel();
        if (selectedModel.isEmpty()) {
            return SqlErrorExplanation.failure(
                "No local Ollama model is installed",
                aiConfiguration.defaultModel()
            );
        }
        AiCompletionRequest aiRequest = new AiCompletionRequest(
            selectedModel,
            prompt,
            aiConfiguration.generateTimeout()
        );

        AiCompletionResult aiResult = aiModelProvider.complete(aiRequest);

        if (!aiResult.success()) {
            recordAiGeneration(connectionId, false, aiResult.model(), "AI_PROVIDER_FAILED");
            return SqlErrorExplanation.failure(aiResult.errorMessage(), aiResult.model());
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
        String preferred = aiModelProvider.preferredModel();
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

    private String buildErrorExplanationPrompt(String sql, String errorMessage, String connectionId) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a SQL teacher assistant. Explain the following SQL error and provide a corrected SQL statement.\n");
        sb.append("\n");
        sb.append("Database: ").append(databaseDialect(connectionId).name()).append("\n");
        sb.append("Available tables:\n");
        sb.append(buildTableSchema(connectionId));
        sb.append("\n");
        sb.append("SQL statement that caused the error:\n");
        sb.append(sql).append("\n");
        sb.append("\n");
        sb.append("Error message:\n");
        sb.append(errorMessage).append("\n");
        sb.append("\n");
        sb.append("Return ONLY a valid JSON object with these fields:\n");
        sb.append("- errorCause: explanation of what caused the error\n");
        sb.append("- correctionSuggestion: suggestion for fixing the error\n");
        sb.append("- correctedSql: the corrected SQL statement\n");
        sb.append("\n");
        sb.append("Example output:\n");
        sb.append("{\"errorCause\": \"Unknown column 'nam' in 'field list'\", \"correctionSuggestion\": \"The column 'nam' does not exist. Did you mean 'name'?\", \"correctedSql\": \"SELECT name FROM student LIMIT 500\"}");
        return sb.toString();
    }
}
