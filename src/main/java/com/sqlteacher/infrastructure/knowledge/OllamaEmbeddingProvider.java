package com.sqlteacher.infrastructure.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.knowledge.EmbeddingProvider;
import com.sqlteacher.domain.SqlTeacherException;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class OllamaEmbeddingProvider implements EmbeddingProvider {
    private static final int MAX_BATCH = 64;
    private final AiConfiguration configuration;
    private final String model;
    private final HttpClient client;
    private final ObjectMapper mapper;

    public OllamaEmbeddingProvider(AiConfiguration configuration, String model) {
        this(configuration, model, HttpClient.newBuilder().connectTimeout(configuration.healthTimeout()).build(), new ObjectMapper());
    }

    OllamaEmbeddingProvider(AiConfiguration configuration, String model, HttpClient client, ObjectMapper mapper) {
        this.configuration = Objects.requireNonNull(configuration);
        this.model = requireText(model, "model");
        this.client = Objects.requireNonNull(client);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public EmbeddingBatch embed(List<String> requestedTexts) {
        List<String> texts = requestedTexts == null ? List.of() : requestedTexts.stream().map(String::trim).toList();
        if (texts.isEmpty() || texts.size() > MAX_BATCH || texts.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("embedding batch must contain 1 to 64 non-blank texts");
        }
        try {
            String body = mapper.writeValueAsString(Map.of("model", model, "input", texts, "truncate", true));
            HttpRequest request = HttpRequest.newBuilder(configuration.ollamaBaseUrl().resolve("/api/embed"))
                .timeout(Duration.ofSeconds(Math.max(30, configuration.generateTimeout().toSeconds())))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new SqlTeacherException("KNOWLEDGE_EMBEDDING_UNAVAILABLE", "Ollama embedding returned HTTP " + response.statusCode());
            }
            JsonNode values = mapper.readTree(response.body()).path("embeddings");
            if (!values.isArray() || values.size() != texts.size()) {
                throw new SqlTeacherException("KNOWLEDGE_EMBEDDING_INVALID", "Ollama returned an invalid embedding batch");
            }
            List<float[]> vectors = new ArrayList<>();
            int dimensions = -1;
            for (JsonNode value : values) {
                float[] vector = new float[value.size()];
                for (int index = 0; index < value.size(); index++) vector[index] = (float) value.get(index).asDouble();
                if (vector.length == 0 || (dimensions > 0 && vector.length != dimensions)) {
                    throw new SqlTeacherException("KNOWLEDGE_EMBEDDING_INVALID", "Ollama returned inconsistent embedding dimensions");
                }
                dimensions = vector.length;
                vectors.add(vector);
            }
            return new EmbeddingBatch("ollama", model, vectors);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new SqlTeacherException("KNOWLEDGE_EMBEDDING_INTERRUPTED", "Embedding request was interrupted", error);
        } catch (SqlTeacherException error) {
            throw error;
        } catch (Exception error) {
            throw new SqlTeacherException("KNOWLEDGE_EMBEDDING_UNAVAILABLE", "Local embedding service is unavailable", error);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
