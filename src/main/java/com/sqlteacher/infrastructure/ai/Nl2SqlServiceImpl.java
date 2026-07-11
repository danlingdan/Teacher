package com.sqlteacher.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.ai.AiCompletionRequest;
import com.sqlteacher.application.ai.AiCompletionResult;
import com.sqlteacher.application.ai.AiModelProvider;
import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.nl2sql.Nl2SqlPlan;
import com.sqlteacher.application.nl2sql.Nl2SqlRequest;
import com.sqlteacher.application.nl2sql.Nl2SqlService;
import com.sqlteacher.infrastructure.ai.dto.OllamaNl2SqlResponse;

import java.util.Objects;

public final class Nl2SqlServiceImpl implements Nl2SqlService {
    private static final String PROMPT_VERSION = "v1";

    private final AiModelProvider aiModelProvider;
    private final AiConfiguration aiConfiguration;
    private final ObjectMapper objectMapper;

    public Nl2SqlServiceImpl(AiModelProvider aiModelProvider, AiConfiguration aiConfiguration) {
        this.aiModelProvider = aiModelProvider;
        this.aiConfiguration = aiConfiguration;
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

        String prompt = buildPrompt(request.naturalLanguage());
        AiCompletionRequest aiRequest = new AiCompletionRequest(
            aiConfiguration.defaultModel(),
            prompt,
            aiConfiguration.generateTimeout()
        );

        AiCompletionResult aiResult = aiModelProvider.complete(aiRequest);

        if (!aiResult.success()) {
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
            return new Nl2SqlPlan(
                response.sqlDraft(),
                response.intent(),
                response.explanation(),
                aiResult.model(),
                PROMPT_VERSION
            );
        } catch (Exception ex) {
            return new Nl2SqlPlan(
                "",
                "",
                "Failed to parse AI response: " + ex.getClass().getSimpleName(),
                aiResult.model(),
                PROMPT_VERSION
            );
        }
    }

    private String buildPrompt(String naturalLanguage) {
        return """
            You are a SQL teacher assistant. Convert the following natural language query into a structured JSON response.
            
            Database: SQLite
            Available tables: student (id, name, score, class_id), class (id, name, teacher)
            
            Natural language: %s
            
            Return ONLY a valid JSON object with these fields:
            - sqlDraft: the generated SQL statement
            - intent: QUERY, INSERT, UPDATE, DELETE, or CREATE
            - explanation: brief explanation of what the SQL does
            
            Example output:
            {"sqlDraft": "SELECT name, score FROM student WHERE score >= 60", "intent": "QUERY", "explanation": "查询成绩大于等于60的学生姓名和分数"}
            """.formatted(naturalLanguage);
    }
}