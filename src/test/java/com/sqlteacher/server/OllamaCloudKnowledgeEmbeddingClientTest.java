package com.sqlteacher.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaCloudKnowledgeEmbeddingClientTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void shouldEmbedBatchAndCheckModelReadiness() throws Exception {
        start("{\"embeddings\":[[1,0,0],[0,1,0]]}");
        OllamaCloudKnowledgeEmbeddingClient client = client(3);

        assertTrue(client.ready());
        CloudKnowledgeEmbeddingClient.EmbeddingBatch batch = client.embed(List.of("WHERE", "GROUP BY"),
            CloudKnowledgeEmbeddingClient.Purpose.PASSAGE);

        assertEquals("ollama", batch.provider());
        assertEquals("embeddinggemma", batch.model());
        assertEquals(2, batch.vectors().size());
        assertEquals(3, batch.vectors().getFirst().length);
    }

    @Test
    void shouldRejectUnexpectedVectorDimensions() throws Exception {
        start("{\"embeddings\":[[1,0]]}");
        assertThrows(RuntimeException.class, () -> client(3).embed(List.of("WHERE"),
            CloudKnowledgeEmbeddingClient.Purpose.QUERY));
    }

    private void start(String embeddingResponse) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> respond(exchange,
            "{\"models\":[{\"name\":\"embeddinggemma:latest\"}]}"));
        server.createContext("/api/embed", exchange -> respond(exchange, embeddingResponse));
        server.start();
    }

    private OllamaCloudKnowledgeEmbeddingClient client(int dimension) {
        return new OllamaCloudKnowledgeEmbeddingClient(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "embeddinggemma", dimension);
    }

    private static void respond(HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
