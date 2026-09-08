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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end coverage for the W3 cloud hardening behavior: login lockout, register and
 * password-reset quotas, sync body and per-item payload limits, and the anonymous report
 * rate-limit dimensions. Uses only synthetic credentials on a throwaway database.
 */
@Tag("integration")
class CloudServerHardeningIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-123";

    @TempDir Path directory;
    private SqlTeacherCloudServer server;

    @AfterEach void stop() { if (server != null) server.stop(); }

    private void start() throws Exception {
        server = new SqlTeacherCloudServer(directory.resolve("cloud.db"), 0);
        server.start();
    }

    @Test void loginLocksAfterFiveFailuresAndRateLimitsFurtherAttempts() throws Exception {
        start();
        register("lockout@example.com", "Lockout User");

        // Unknown accounts must fail with the same shape as wrong passwords (no enumeration).
        HttpResponse<String> unknown = login("nobody@example.com", "whatever-passphrase-1");
        assertEquals(401, unknown.statusCode());
        assertEquals("LOGIN_FAILED", JSON.readTree(unknown.body()).get("code").asText());

        for (int index = 0; index < 5; index++) {
            assertEquals(401, login("lockout@example.com", "wrong-passphrase-" + index).statusCode(),
                "failure " + (index + 1) + " must stay a normal 401");
        }
        HttpResponse<String> sixth = login("lockout@example.com", "wrong-passphrase-5");
        assertEquals(429, sixth.statusCode());
        assertEquals("RATE_LIMITED", JSON.readTree(sixth.body()).get("code").asText());
        assertFalse(sixth.headers().firstValue("Retry-After").orElse("").isBlank());

        // Even the correct password is refused while the lockout is active.
        assertEquals(429, login("lockout@example.com", PASSWORD).statusCode());
    }

    @Test void successfulLoginClearsTheFailureCounter() throws Exception {
        start();
        register("reset-counter@example.com", "Counter User");

        for (int index = 0; index < 4; index++) {
            assertEquals(401, login("reset-counter@example.com", "wrong-passphrase-" + index).statusCode());
        }
        HttpResponse<String> success = login("reset-counter@example.com", PASSWORD);
        assertEquals(200, success.statusCode());

        // Without the reset these would cross the five-failure threshold immediately.
        for (int index = 0; index < 5; index++) {
            assertEquals(401, login("reset-counter@example.com", "later-passphrase-" + index).statusCode(),
                "counter must restart after a successful login");
        }
        assertEquals(429, login("reset-counter@example.com", "later-passphrase-5").statusCode());
    }

    @Test void registerAttemptsAreQuotaLimitedPerEmail() throws Exception {
        start();
        String email = "quota-register@example.com";
        assertEquals(201, register(email, "Quota User").statusCode());
        for (int index = 1; index < 10; index++) {
            HttpResponse<String> repeated = register(email, "Quota User");
            assertEquals(409, repeated.statusCode(), "duplicate must keep the ACCOUNT_EXISTS semantics");
            assertEquals("ACCOUNT_EXISTS", JSON.readTree(repeated.body()).get("code").asText());
        }
        HttpResponse<String> eleventh = register(email, "Quota User");
        assertEquals(429, eleventh.statusCode());
        assertEquals("RATE_LIMITED", JSON.readTree(eleventh.body()).get("code").asText());
    }

    @Test void passwordResetMailsAreQuotaLimitedPerEmail() throws Exception {
        start();
        register("quota-reset@example.com", "Reset User");
        for (int index = 0; index < 3; index++) {
            assertEquals(200, resetRequest("quota-reset@example.com").statusCode(),
                "the first three requests stay uniform regardless of account existence");
        }
        HttpResponse<String> fourth = resetRequest("quota-reset@example.com");
        assertEquals(429, fourth.statusCode());
        assertEquals("RATE_LIMITED", JSON.readTree(fourth.body()).get("code").asText());
    }

    @Test void syncEventBodiesAboveOneMiBAreRejectedWith413() throws Exception {
        start();
        String token = accessToken(register("sync-limit@example.com", "Sync User"));
        HttpResponse<String> oversized = post("sync/events", token, "a".repeat(1024 * 1024 + 1), null);
        assertEquals(413, oversized.statusCode());
        assertEquals("REQUEST_TOO_LARGE", JSON.readTree(oversized.body()).get("code").asText());
    }

    @Test void syncEventItemPayloadAboveLimitIsRejectedWith400() throws Exception {
        start();
        String token = accessToken(register("sync-item@example.com", "Sync Item User"));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "event-1");
        item.put("type", "PRACTICE_COMPLETED");
        item.put("payloadJson", "{\"successful\":true,\"note\":\"" + "x".repeat(17_000) + "\"}");
        item.put("occurredAt", "2026-01-01T00:00:00Z");
        item.put("version", 1);
        HttpResponse<String> rejected = post("sync/events", token,
            JSON.writeValueAsString(Map.of("items", List.of(item))), null);
        assertEquals(400, rejected.statusCode());
        JsonNode error = JSON.readTree(rejected.body());
        assertEquals("PAYLOAD_TOO_LARGE", error.get("code").asText());
        assertTrue(error.get("message").asText().contains("1"), "message must identify the offending item");
    }

    @Test void bankPublishBodiesAboveTwoMiBAreRejectedWith413() throws Exception {
        start();
        String token = accessToken(register("bank-limit@example.com", "Bank User"));
        HttpResponse<String> oversized = post("bank/publish", token, "b".repeat(2 * 1024 * 1024 + 1), null);
        assertEquals(413, oversized.statusCode());
        assertEquals("REQUEST_TOO_LARGE", JSON.readTree(oversized.body()).get("code").asText());
    }

    @Test void anonymousReportsFromLoopbackGroupByForwardedForPrincipal() throws Exception {
        start();
        for (int index = 0; index < 5; index++) {
            assertEquals(201, report(reportBody("xff-" + index, "install-xff"), "203.0.113.9, 198.51.100.23").statusCode(),
                "report " + (index + 1) + " must be accepted");
        }
        HttpResponse<String> sixth = report(reportBody("xff-5", "install-xff"), "203.0.113.9, 198.51.100.23");
        assertEquals(429, sixth.statusCode());
        assertEquals("REPORT_RATE_LIMITED", JSON.readTree(sixth.body()).get("code").asText());
    }

    @Test void anonymousReportsAreCappedPerInstallIdEvenWithRotatingAddresses() throws Exception {
        start();
        for (int index = 0; index < 5; index++) {
            assertEquals(201, report(reportBody("install-" + index, "install-fixed"), "198.51.100." + index).statusCode(),
                "rotating the forwarded address must not defeat the install dimension");
        }
        HttpResponse<String> sixth = report(reportBody("install-5", "install-fixed"), "198.51.100.5");
        assertEquals(429, sixth.statusCode());
        assertEquals("REPORT_RATE_LIMITED", JSON.readTree(sixth.body()).get("code").asText());
    }

    private HttpResponse<String> login(String email, String password) throws Exception {
        return post("auth/login", null, JSON.writeValueAsString(Map.of("email", email, "password", password)), null);
    }

    private HttpResponse<String> register(String email, String displayName) throws Exception {
        return post("auth/register", null,
            JSON.writeValueAsString(Map.of("email", email, "displayName", displayName, "password", PASSWORD)), null);
    }

    private HttpResponse<String> resetRequest(String email) throws Exception {
        return post("auth/request-password-reset", null, JSON.writeValueAsString(Map.of("email", email)), null);
    }

    private Map<String, Object> reportBody(String idempotencyKey, String installId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotencyKey", idempotencyKey);
        body.put("installId", installId);
        body.put("type", "BUG");
        body.put("severity", "MINOR");
        body.put("summary", "Rate limit probe " + idempotencyKey);
        body.put("description", "Synthetic report for rate limit verification");
        body.put("application", Map.of("version", "3.2.0"));
        body.put("diagnostics", Map.of("environment", Map.of("os", "Windows")));
        return body;
    }

    private HttpResponse<String> report(Map<String, Object> body, String forwardedFor) throws Exception {
        return post("support/reports", null, JSON.writeValueAsString(body), forwardedFor);
    }

    private String accessToken(HttpResponse<String> registration) throws Exception {
        assertEquals(201, registration.statusCode());
        return JSON.readTree(registration.body()).get("accessToken").asText();
    }

    private HttpResponse<String> post(String path, String token, String body, String forwardedFor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.port() + "/api/v1/" + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (forwardedFor != null) request.header("X-Forwarded-For", forwardedFor);
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
            .send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
