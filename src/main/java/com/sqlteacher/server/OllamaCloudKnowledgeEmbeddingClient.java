package com.sqlteacher.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.domain.SqlTeacherException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class OllamaCloudKnowledgeEmbeddingClient implements CloudKnowledgeEmbeddingClient {
    private static final int MAX_BATCH = 64;
    private final URI baseUri;
    private final String model;
    private final String provider;
    private final int expectedDimension;
    private final HttpClient client;
    private final ObjectMapper mapper;

    OllamaCloudKnowledgeEmbeddingClient(URI baseUri, String model, int expectedDimension) {
        this(baseUri, model, expectedDimension, "ollama");
    }

    OllamaCloudKnowledgeEmbeddingClient(URI baseUri, String model, int expectedDimension, String provider) {
        this(baseUri, model, expectedDimension, provider,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), new ObjectMapper());
    }

    OllamaCloudKnowledgeEmbeddingClient(URI baseUri, String model, int expectedDimension, String provider,
                                        HttpClient client, ObjectMapper mapper) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri must not be null");
        this.model = require(model, "model");
        this.provider = require(provider, "provider");
        if (expectedDimension < 1) throw new IllegalArgumentException("expectedDimension must be positive");
        this.expectedDimension = expectedDimension;
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public EmbeddingBatch embed(List<String> requestedTexts, Purpose purpose) {
        Objects.requireNonNull(purpose, "purpose must not be null");
        List<String> texts = requestedTexts == null ? List.of() : requestedTexts.stream()
            .map(value -> value == null ? "" : value.trim()).toList();
        if (texts.isEmpty() || texts.size() > MAX_BATCH || texts.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("embedding batch must contain 1 to 64 non-blank texts");
        }
        try {
            String body = mapper.writeValueAsString(Map.of("model", model, "input", texts, "truncate", true,
                "task", purpose.name().toLowerCase(java.util.Locale.ROOT)));
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/embed"))
                .timeout(Duration.ofSeconds(90)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new SqlTeacherException("CLOUD_EMBEDDING_UNAVAILABLE",
                    "Embedding service returned HTTP " + response.statusCode());
            }
            JsonNode values = mapper.readTree(response.body()).path("embeddings");
            if (!values.isArray() || values.size() != texts.size()) {
                throw new SqlTeacherException("CLOUD_EMBEDDING_INVALID", "Embedding service returned an invalid batch");
            }
            List<float[]> vectors = new ArrayList<>();
            for (JsonNode value : values) {
                if (!value.isArray() || value.size() != expectedDimension) {
                    throw new SqlTeacherException("CLOUD_EMBEDDING_DIMENSION_MISMATCH",
                        "Embedding dimension does not match the Qdrant collection");
                }
                float[] vector = new float[expectedDimension];
                for (int index = 0; index < vector.length; index++) {
                    double number = value.get(index).asDouble(Double.NaN);
                    if (!Double.isFinite(number)) {
                        throw new SqlTeacherException("CLOUD_EMBEDDING_INVALID", "Embedding contains a non-finite value");
                    }
                    vector[index] = (float) number;
                }
                vectors.add(vector);
            }
            return new EmbeddingBatch(provider, model, vectors);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new SqlTeacherException("CLOUD_EMBEDDING_INTERRUPTED", "Embedding request was interrupted", error);
        } catch (SqlTeacherException error) {
            throw error;
        } catch (Exception error) {
            throw new SqlTeacherException("CLOUD_EMBEDDING_UNAVAILABLE", "Embedding service is unavailable", error);
        }
    }

    @Override
    public boolean ready() {
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/tags"))
                .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return false;
            for (JsonNode item : mapper.readTree(response.body()).path("models")) {
                String name = item.path("name").asText("");
                if (name.equals(model) || name.equals(model + ":latest") || name.startsWith(model + ":")) return true;
            }
            return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception error) {
            return false;
        }
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
