package com.sqlteacher.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.domain.SqlTeacherException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Optional server-side Qdrant boundary. Desktop clients never connect to Qdrant directly. */
public final class QdrantVectorClient {
    private final URI baseUri;
    private final String collection;
    private final String apiKey;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public QdrantVectorClient(URI baseUri, String collection, String apiKey) {
        this.baseUri = baseUri;
        this.collection = require(collection, "collection");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public boolean enabled() { return baseUri != null; }

    public void upsert(List<Point> points) {
        if (!enabled()) throw new IllegalStateException("Qdrant is not configured");
        if (points == null || points.isEmpty()) throw new IllegalArgumentException("points must not be empty");
        send("/collections/" + encodedCollection() + "/points?wait=true", "PUT", Map.of("points", points));
    }

    public List<SearchHit> search(float[] vector, String courseId, String visibility, int limit) {
        if (!enabled()) return List.of();
        if (vector == null || vector.length == 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("invalid Qdrant search request");
        Map<String, Object> filter = Map.of("must", List.of(
            Map.of("key", "courseId", "match", Map.of("value", require(courseId, "courseId"))),
            Map.of("key", "visibility", "match", Map.of("value", require(visibility, "visibility")))
        ));
        JsonNode root = send("/collections/" + encodedCollection() + "/points/search", "POST",
            Map.of("vector", vector, "filter", filter, "limit", limit, "with_payload", true));
        List<SearchHit> hits = new ArrayList<>();
        for (JsonNode item : root.path("result")) {
            hits.add(new SearchHit(item.path("id").asText(), item.path("score").asDouble(),
                mapper.convertValue(item.path("payload"), Map.class)));
        }
        return List.copyOf(hits);
    }

    public String createSnapshot() {
        JsonNode root = send("/collections/" + encodedCollection() + "/snapshots", "POST", Map.of());
        return root.path("result").path("name").asText("");
    }

    private JsonNode send(String path, String method, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json").header("Content-Type", "application/json");
            if (!apiKey.isBlank()) builder.header("api-key", apiKey);
            HttpRequest request = builder.method(method,
                HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new SqlTeacherException("QDRANT_REQUEST_FAILED", "Qdrant returned HTTP " + response.statusCode());
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new SqlTeacherException("QDRANT_REQUEST_INTERRUPTED", "Qdrant request was interrupted", error);
        } catch (SqlTeacherException error) { throw error; }
        catch (Exception error) { throw new SqlTeacherException("QDRANT_REQUEST_FAILED", "Qdrant is unavailable", error); }
    }

    private String encodedCollection() { return URLEncoder.encode(collection, StandardCharsets.UTF_8); }
    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    public record Point(String id, float[] vector, Map<String, Object> payload) {
        public Point {
            if (id == null || id.isBlank() || vector == null || vector.length == 0) throw new IllegalArgumentException("invalid Qdrant point");
            vector = vector.clone(); payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }
    public record SearchHit(String id, double score, Map<String, Object> payload) {
        public SearchHit { payload = payload == null ? Map.of() : Map.copyOf(payload); }
    }
}
