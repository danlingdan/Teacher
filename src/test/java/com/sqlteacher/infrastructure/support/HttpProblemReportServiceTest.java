package com.sqlteacher.infrastructure.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.support.ProblemReportDraft;
import com.sqlteacher.application.support.ProblemReportReceipt;
import com.sqlteacher.application.system.GeneralSoftwareService;
import com.sqlteacher.application.system.GeneralSoftwareSettings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.4.2 LEG-10: the HTTP problem-report client talks to the existing cloud endpoint, sends the
 * Bearer header only when a signed-in access token is supplied, and keeps a stable installation
 * identity so retried submissions dedupe server-side.
 */
class HttpProblemReportServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RECEIPT_JSON = """
        {"reportId":"report-1","queryToken":"query-token-1","status":"RECEIVED",\
        "submittedAt":"2026-09-15T08:00:00Z"}
        """;
    private static final String EXPORT_JSON = """
        {"reportId":"report-1","type":"BUG","status":"RECEIVED","summary":"练习提交失败",\
        "submittedAt":"2026-09-15T08:00:00Z","updatedAt":"2026-09-15T08:00:00Z","history":[]}
        """;

    private HttpServer server;
    private HttpProblemReportService service;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<JsonNode> body = new AtomicReference<>();

    @TempDir
    Path dataDirectory;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/support/reports", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().toString());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            if (bytes.length > 0) body.set(JSON.readTree(bytes));
            String response = exchange.getRequestURI().getPath().endsWith("/export")
                ? EXPORT_JSON
                : RECEIPT_JSON;
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        service = new HttpProblemReportService(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()), dataDirectory, system());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** Only settings() is consulted (proxy policy); every other method fails loudly if touched. */
    private static GeneralSoftwareService system() {
        Object proxy = Proxy.newProxyInstance(GeneralSoftwareService.class.getClassLoader(),
            new Class<?>[] { GeneralSoftwareService.class }, (instance, called, args) -> {
                if ("settings".equals(called.getName())) {
                    return new GeneralSoftwareSettings(1, true, "",
                        GeneralSoftwareSettings.ProxyMode.DIRECT, "", 0, false, false, false, 0, false,
                        "zh", false, false, "system", "modern", "comfortable");
                }
                throw new UnsupportedOperationException(
                    called.getName() + " must not be used to build the report HTTP client");
            });
        return (GeneralSoftwareService) proxy;
    }

    private static ProblemReportDraft draft() {
        return new ProblemReportDraft("idem-1", ProblemReportDraft.Type.BUG,
            ProblemReportDraft.Severity.MINOR, "练习提交失败", "点击提交后五分钟无响应",
            "打开练习，点击提交", "出现结果页", "一直转圈", "student@example.com",
            new com.sqlteacher.application.support.DiagnosticSelection(true, false, false, false),
            null);
    }

    @Test
    void submitSendsTheDraftAndDiagnosticsWithoutABearerHeaderWhenAnonymous() {
        ProblemReportReceipt receipt = service.submit(draft(), Map.of("javaVersion", "25"), null);

        assertEquals("report-1", receipt.reportId());
        assertEquals("POST", method.get());
        assertEquals("/api/v1/support/reports", path.get());
        assertNull(authorization.get(), "an anonymous submission must not send an Authorization header");
        assertEquals("idem-1", body.get().path("idempotencyKey").asText());
        assertFalse(body.get().path("installId").asText().isEmpty(), "a stable install id is required");
        assertEquals("BUG", body.get().path("type").asText());
        assertEquals("25", body.get().path("diagnostics").path("javaVersion").asText());
    }

    @Test
    void submitWithAnAccessTokenSendsTheBearerHeaderAndReusesTheInstallIdentity() {
        service.submit(draft(), Map.of(), "token-teacher-1");

        assertEquals("Bearer token-teacher-1", authorization.get());
        String firstInstallId = body.get().path("installId").asText();

        service.submit(draft(), Map.of(), "token-teacher-1");
        assertEquals(firstInstallId, body.get().path("installId").asText(),
            "the installation identity is stable across submissions");
    }

    @Test
    void statusWithdrawAndExportUseTheQueryTokenChannel() {
        service.status("report-1", "query-token-1");
        assertEquals("/api/v1/support/reports/report-1?queryToken=query-token-1", path.get());
        assertEquals("GET", method.get());
        assertNull(authorization.get());

        service.withdraw("report-1", "query-token-1");
        assertEquals("/api/v1/support/reports/report-1/withdraw?queryToken=query-token-1", path.get());
        assertEquals("POST", method.get());

        service.export("report-1", "query-token-1");
        assertEquals("/api/v1/support/reports/report-1/export?queryToken=query-token-1", path.get());
        assertEquals("GET", method.get());
    }

    @Test
    void serverErrorsBecomeReadableFailuresInsteadOfRawHttpProse() {
        server.stop(0);
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/support/reports", exchange -> {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            });
            server.start();
            service = new HttpProblemReportService(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), dataDirectory, system());

            IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.status("report-1", "query-token-1"));
            assertEquals("问题反馈服务暂时不可用", error.getMessage());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        } finally {
            server.stop(0);
        }
    }
}
