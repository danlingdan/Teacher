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
public final class QdrantVectorClient implements CloudKnowledgeVectorClient {
    private final URI baseUri;
    private final String collection;
    private final String apiKey;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public QdrantVectorClient(URI baseUri, String collection, String apiKey) {
        this.baseUri = baseUri;
        this.collection = require(collection, "collection");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    }

    public boolean enabled() { return baseUri != null; }

    @Override
    public void validateCollection(int expectedDimension) {
        if (expectedDimension < 1) throw new IllegalArgumentException("expectedDimension must be positive");
        JsonNode result = send("/collections/" + encodedCollection(), "GET", null).path("result");
        JsonNode vectors = result.path("config").path("params").path("vectors");
        if (!"green".equals(result.path("status").asText()) || vectors.path("size").asInt(-1) != expectedDimension
            || !"Cosine".equalsIgnoreCase(vectors.path("distance").asText())) {
            throw new SqlTeacherException("QDRANT_COLLECTION_INVALID",
                "Qdrant collection is unavailable or incompatible with the embedding profile");
        }
    }

    @Override
    public void upsert(List<Point> points) {
        if (!enabled()) throw new IllegalStateException("Qdrant is not configured");
        if (points == null || points.isEmpty()) throw new IllegalArgumentException("points must not be empty");
        send("/collections/" + encodedCollection() + "/points?wait=true", "PUT", Map.of("points", points));
    }

    @Override
    public List<SearchHit> search(float[] vector, String courseId, List<String> requestedVisibility, int limit) {
        if (!enabled()) return List.of();
        if (vector == null || vector.length == 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("invalid Qdrant search request");
        List<String> visibility = requestedVisibility == null ? List.of() : requestedVisibility.stream()
            .map(value -> require(value, "visibility")).distinct().toList();
        if (visibility.isEmpty()) throw new IllegalArgumentException("visibility must not be empty");
        Map<String, Object> filter = Map.of("must", List.of(
            Map.of("key", "courseId", "match", Map.of("value", require(courseId, "courseId"))),
            Map.of("key", "visibility", "match", Map.of("any", visibility))
        ));
        JsonNode root = send("/collections/" + encodedCollection() + "/points/query", "POST",
            Map.of("query", vector, "filter", filter, "limit", limit, "with_payload", true));
        List<SearchHit> hits = new ArrayList<>();
        for (JsonNode item : root.path("result").path("points")) {
            hits.add(new SearchHit(item.path("id").asText(), item.path("score").asDouble(),
                mapper.convertValue(item.path("payload"), Map.class)));
        }
        return List.copyOf(hits);
    }

    @Override
    public boolean ready() {
        if (!enabled()) return false;
        try {
            return request("/readyz", "GET", null).statusCode() == 200;
        } catch (RuntimeException error) {
            return false;
        }
    }

    public String createSnapshot() {
        JsonNode root = send("/collections/" + encodedCollection() + "/snapshots", "POST", Map.of());
        return root.path("result").path("name").asText("");
    }

    private JsonNode send(String path, String method, Object body) {
        HttpResponse<String> response = request(path, method, body);
        try {
            return mapper.readTree(response.body());
        } catch (Exception error) {
            throw new SqlTeacherException("QDRANT_RESPONSE_INVALID", "Qdrant returned invalid JSON", error);
        }
    }

    private HttpResponse<String> request(String path, String method, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json").header("Content-Type", "application/json");
            if (!apiKey.isBlank()) builder.header("api-key", apiKey);
            HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8);
            HttpRequest request = builder.method(method, publisher).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new SqlTeacherException("QDRANT_REQUEST_FAILED", "Qdrant returned HTTP " + response.statusCode());
            }
            return response;
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
        public SearchHit {
            if (id == null || id.isBlank() || !Double.isFinite(score)) throw new IllegalArgumentException("invalid Qdrant search hit");
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }
}
