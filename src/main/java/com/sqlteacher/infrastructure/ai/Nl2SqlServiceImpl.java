package com.sqlteacher.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.ai.AiCompletionRequest;
import com.sqlteacher.application.ai.AiCompletionResult;
import com.sqlteacher.application.ai.AiModelProvider;
import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.metadata.DatabaseColumn;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.metadata.DatabaseTable;
import com.sqlteacher.application.nl2sql.Nl2SqlPlan;
import com.sqlteacher.application.nl2sql.Nl2SqlRequest;
import com.sqlteacher.application.nl2sql.Nl2SqlService;
import com.sqlteacher.infrastructure.ai.dto.OllamaNl2SqlResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

public final class Nl2SqlServiceImpl implements Nl2SqlService {
    private static final Logger log = LoggerFactory.getLogger(Nl2SqlServiceImpl.class);
    private static final String PROMPT_VERSION = "v2";
    private final AiModelProvider aiModelProvider;
    private final AiConfiguration aiConfiguration;
    private final DatabaseMetadataService databaseMetadataService;
    private final LearningEventService learningEventService;
    private final ObjectMapper objectMapper;

    public Nl2SqlServiceImpl(
        AiModelProvider aiModelProvider,
        AiConfiguration aiConfiguration,
        DatabaseMetadataService databaseMetadataService,
        LearningEventService learningEventService
    ) {
        this.aiModelProvider = aiModelProvider;
        this.aiConfiguration = aiConfiguration;
        this.databaseMetadataService = databaseMetadataService;
        this.learningEventService = learningEventService;
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

        String prompt = buildPrompt(request.naturalLanguage(), request.connectionId());
        AiCompletionRequest aiRequest = new AiCompletionRequest(
            aiConfiguration.defaultModel(),
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
        sb.append("You are a SQL teacher assistant. Convert the following natural language query into a valid SQLite SELECT statement.\n");
        sb.append("\n");
        sb.append("Database: SQLite\n");
        sb.append("Available tables:\n");
        sb.append(buildTableSchema(connectionId));
        sb.append("\n");
        sb.append("IMPORTANT RULES:\n");
        sb.append("- ONLY generate SELECT statements\n");
        sb.append("- NO INSERT, UPDATE, DELETE, CREATE, DROP, ALTER, or any modifying statements\n");
        sb.append("- NO multiple statements separated by semicolons\n");
        sb.append("- Limit the result to 500 rows using LIMIT clause\n");
        sb.append("- Return ONLY a valid JSON object\n");
        sb.append("\n");
        sb.append("Natural language: ").append(naturalLanguage).append("\n");
        sb.append("\n");
        sb.append("Return ONLY a valid JSON object with these fields:\n");
        sb.append("- sqlDraft: the generated SELECT SQL statement\n");
        sb.append("- intent: must be \"QUERY\"\n");
        sb.append("- explanation: brief explanation of what the SQL does\n");
        sb.append("\n");
        sb.append("Example output:\n");
        sb.append("{\"sqlDraft\": \"SELECT name, score FROM student WHERE score >= 60 LIMIT 500\", \"intent\": \"QUERY\", \"explanation\": \"查询成绩大于等于60的学生姓名和分数\"}");
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
        } catch (Exception ex) {
            return getDefaultTableSchema();
        }
    }

    private String getDefaultTableSchema() {
        return "  - student (id, name, score, class_id)\n  - class (id, name, teacher)";
    }
}
