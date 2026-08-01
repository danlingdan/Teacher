package com.sqlteacher.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QdrantVectorClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void shouldUseCurrentQueryApiAndAuthenticatedPayloadFilters() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<JsonNode> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            assertEquals("secret", exchange.getRequestHeaders().getFirst("api-key"));
            path.set(exchange.getRequestURI().getPath());
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "ready");
                return;
            }
            body.set(mapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200, """
                {"result":{"points":[{"id":"chunk-1","score":0.91,"payload":{"courseId":"course-1"}}]},"status":"ok"}
                """);
        });
        server.start();
        QdrantVectorClient client = client();

        assertTrue(client.ready());
        List<QdrantVectorClient.SearchHit> hits = client.search(new float[]{1, 0, 0}, "course-1",
            List.of("PUBLISHED"), 10);

        assertEquals("/collections/knowledge/points/query", path.get());
        assertEquals("course-1", body.get().at("/filter/must/0/match/value").asText());
        assertEquals("PUBLISHED", body.get().at("/filter/must/1/match/any/0").asText());
        assertEquals(3, body.get().path("query").size());
        assertEquals("chunk-1", hits.getFirst().id());
    }

    @Test
    void shouldValidateCollectionAndUpsertWithoutLeakingPayloadContent() throws Exception {
        AtomicReference<JsonNode> upsert = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collections/knowledge", exchange -> respond(exchange, 200, """
            {"result":{"status":"green","config":{"params":{"vectors":{"size":3,"distance":"Cosine"}}}},"status":"ok"}
            """));
        server.createContext("/collections/knowledge/points", exchange -> {
            upsert.set(mapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200, "{\"result\":{},\"status\":\"ok\"}");
        });
        server.start();
        QdrantVectorClient client = client();

        client.validateCollection(3);
        client.upsert(List.of(new QdrantVectorClient.Point("chunk-1", new float[]{1, 0, 0},
            Map.of("courseId", "course-1", "visibility", "PUBLISHED"))));

        assertEquals("chunk-1", upsert.get().at("/points/0/id").asText());
        assertTrue(upsert.get().at("/points/0/payload/content").isMissingNode());
    }

    private QdrantVectorClient client() {
        return new QdrantVectorClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            "knowledge", "secret");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
