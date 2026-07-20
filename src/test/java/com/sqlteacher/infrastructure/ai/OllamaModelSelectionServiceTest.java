package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.application.ai.AiModelSelection;
import com.sqlteacher.application.config.AiConfiguration;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaModelSelectionServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldDetectInstalledModelsAndChooseAnExistingDefault() throws Exception {
        HttpServer server = modelServer("""
            {"models":[{"name":"qwen3.5:9b"},{"name":"llama3.2:3b"}]}
            """);
        try {
            OllamaModelSelectionService service = service(server, "missing:latest");

            AiModelSelection result = service.refresh();

            assertEquals(2, result.installedModels().size());
            assertEquals("qwen3.5:9b", result.selectedModel());
            assertTrue(result.hasSelection());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldPersistAUserSelectedInstalledModel() throws Exception {
        HttpServer server = modelServer("""
            {"models":[{"name":"qwen3.5:9b"},{"name":"llama3.2:3b"}]}
            """);
        try {
            OllamaModelSelectionService first = service(server, "qwen3.5:9b");
            first.refresh();
            first.select("llama3.2:3b");

            OllamaModelSelectionService restarted = service(server, "qwen3.5:9b");
            AiModelSelection restored = restarted.refresh();

            assertEquals("llama3.2:3b", restored.selectedModel());
        } finally {
            server.stop(0);
        }
    }

    private OllamaModelSelectionService service(HttpServer server, String configuredModel) {
        AiConfiguration configuration = new AiConfiguration(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            Duration.ofSeconds(2),
            Duration.ofSeconds(30),
            configuredModel
        );
        return new OllamaModelSelectionService(
            configuration,
            tempDir.resolve("selected-ai-model.txt")
        );
    }

    private static HttpServer modelServer(String responseBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }
}
