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
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end coverage for the v1.11 account lifecycle and feedback endpoints:
 * registration -> sessions -> account export -> deletion apply/cancel, plus a
 * screenshot report that is withdrawn and exported by its query token.
 */
@Tag("integration")
class V111CloudApiIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;
    private SqlTeacherCloudServer server;

    @AfterEach void stop() { if (server != null) server.stop(); }

    private HttpClient client() { return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(); }

    @Test void accountLifecycleEndToEnd() throws Exception {
        server = new SqlTeacherCloudServer(directory.resolve("cloud.db"), 0); server.start();
        HttpClient client = client(); URI base = URI.create("http://127.0.0.1:" + server.port());

        // register
        HttpResponse<String> registered = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/auth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
                Map.of("email", "lifecycle@example.com", "displayName", "Student", "password", "correct-horse-123"))))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, registered.statusCode());
        JsonNode session = JSON.readTree(registered.body());
        String token = session.get("accessToken").asText();

        // sessions list contains the new session
        JsonNode sessions = JSON.readTree(client.send(HttpRequest.newBuilder(base.resolve("/api/v1/sessions"))
            .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
        assertEquals(1, sessions.get("sessions").size());
        String sessionId = sessions.get("sessions").get(0).get("id").asText();

        // revoking the current session is rejected
        HttpResponse<String> selfRevoke = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/sessions/" + sessionId + "/revoke"))
            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, selfRevoke.statusCode());

        // account export (sync READY in this implementation)
        HttpResponse<String> export = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/account/export"))
            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, export.statusCode());
        JsonNode exportTask = JSON.readTree(export.body());
        String exportId = exportTask.get("taskId").asText();
        HttpResponse<String> exportPayload = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/account/export/" + exportId))
            .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, exportPayload.statusCode());
        assertTrue(exportPayload.body().contains("exportedAt"));

        // deletion apply then cancel
        HttpResponse<String> delete = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/account/delete"))
            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, delete.statusCode());
        assertEquals("PENDING", JSON.readTree(delete.body()).get("status").asText());
        HttpResponse<String> cancelled = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/account/delete"))
            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .method("DELETE", HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, cancelled.statusCode());
        assertEquals("CANCELLED", JSON.readTree(cancelled.body()).get("status").asText());
    }

    @Test void screenshotReportCanBeWithdrawnAndExported() throws Exception {
        server = new SqlTeacherCloudServer(directory.resolve("cloud.db"), 0); server.start();
        HttpClient client = client(); URI base = URI.create("http://127.0.0.1:" + server.port());

        Map<String, Object> report = Map.of("idempotencyKey", "shot-draft", "installId", "install-x", "type", "USABILITY",
            "severity", "MINOR", "summary", "Button is misaligned", "description", "On the settings page",
            "application", Map.of("version", "1.11.0"), "diagnostics", Map.of("environment", Map.of("os", "Windows")),
            "screenshot", Map.of("filename", "shot.png", "mimeType", "image/png", "data", Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})));
        HttpResponse<String> submitted = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/support/reports"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(report))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, submitted.statusCode());
        JsonNode receipt = JSON.readTree(submitted.body());
        String id = receipt.get("reportId").asText();
        String token = receipt.get("queryToken").asText();

        // export metadata
        HttpResponse<String> exported = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/support/reports/" + id + "/export?queryToken=" + token))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, exported.statusCode());
        assertTrue(exported.body().contains("Button is misaligned"));

        // withdraw then status flips
        HttpResponse<String> withdrawn = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/support/reports/" + id + "/withdraw?queryToken=" + token))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, withdrawn.statusCode());
        assertEquals("WITHDRAWN", JSON.readTree(withdrawn.body()).get("status").asText());

        // wrong token cannot withdraw or export
        HttpResponse<String> denied = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/support/reports/" + id + "/withdraw?queryToken=wrong"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, denied.statusCode());
    }
}
