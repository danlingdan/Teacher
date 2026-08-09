package com.sqlteacher.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class V110CloudApiIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;
    private SqlTeacherCloudServer server;

    @AfterEach void stop() { if (server != null) server.stop(); }

    @Test void advertisesCapabilitiesAndAcceptsBoundedAnonymousReports() throws Exception {
        server = new SqlTeacherCloudServer(directory.resolve("cloud.db"), 0); server.start();
        HttpClient client = HttpClient.newHttpClient(); URI base = URI.create("http://127.0.0.1:" + server.port());
        JsonNode capabilities = JSON.readTree(client.send(HttpRequest.newBuilder(base.resolve("/api/v1/app/capabilities")).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
        assertEquals("2.0", capabilities.get("apiVersion").asText());
        assertTrue(capabilities.get("capabilities").toString().contains("SIGNED_UPDATES"));
        assertTrue(capabilities.get("capabilities").toString().contains("ARTIFACT_SYNC_V2"));

        Map<String, Object> report = Map.of("idempotencyKey", "api-draft", "installId", "test-install", "type", "BUG",
            "severity", "PARTIAL_FAILURE", "summary", "Cannot update", "description", "The signed download did not start",
            "application", Map.of("version", "1.10.0"), "diagnostics", Map.of("environment", Map.of("os", "Windows")));
        HttpResponse<String> submitted = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/support/reports"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(report))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, submitted.statusCode());
        JsonNode receipt = JSON.readTree(submitted.body());
        assertTrue(receipt.get("reportId").asText().startsWith("FB-")); assertFalse(receipt.get("queryToken").asText().isBlank());

        URI status = base.resolve("/api/v1/support/reports/" + receipt.get("reportId").asText() + "?queryToken=" + receipt.get("queryToken").asText());
        assertEquals(200, client.send(HttpRequest.newBuilder(status).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        String oversized = "x".repeat(70 * 1024);
        assertEquals(413, client.send(HttpRequest.newBuilder(base.resolve("/api/v1/support/reports"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(oversized)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
