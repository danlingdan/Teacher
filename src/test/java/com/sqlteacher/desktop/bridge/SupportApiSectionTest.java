package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.UserRole;
import com.sqlteacher.application.support.ProblemReportDraft;
import com.sqlteacher.application.support.ProblemReportReceipt;
import com.sqlteacher.application.support.ProblemReportService;
import com.sqlteacher.application.system.GeneralSoftwareService;
import com.sqlteacher.application.system.TaskSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.fake;
import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.hostWithBeans;
import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.4.2 LEG-10: the support section keeps preview and submit on one diagnostic construction
 * path (what the user sees is exactly what is sent), passes the cloud access token only for
 * signed-in users, and redacts task titles before they leave the process.
 */
class SupportApiSectionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private static ProblemReportReceipt receipt() {
        return new ProblemReportReceipt("report-1", "query-token-1",
            ProblemReportReceipt.Status.RECEIVED, Instant.parse("2026-09-15T08:00:00Z"));
    }

    @Test
    void previewWithEveryDiagnosticOffReturnsAnEmptyMap() throws Exception {
        GeneralSoftwareService system = fake(GeneralSoftwareService.class, Map.of(
            "tasks", args -> { throw new AssertionError("tasks must not be read"); },
            "settings", args -> { throw new AssertionError("settings must not be read"); }));
        try (var host = hostWithBeans(system)) {
            SupportApiSection section = new SupportApiSection(host);

            JsonNode result = section.handle("support.report.preview",
                paramsWithDiagnostics(false, false, false, false), () -> false, ignored -> { });

            assertTrue(result.isObject());
            assertEquals(0, result.size(), "no diagnostic block may be built when all are off");
        }
    }

    @Test
    void previewEnvironmentBlockReportsJvmAndOsOnly() throws Exception {
        try (var host = hostWithBeans()) {
            SupportApiSection section = new SupportApiSection(host);

            JsonNode result = section.handle("support.report.preview",
                paramsWithDiagnostics(true, false, false, false), () -> false, ignored -> { });

            assertEquals(2, result.size());
            assertEquals(System.getProperty("java.version"), result.path("javaVersion").asText());
            assertTrue(result.path("os").asText().startsWith(System.getProperty("os.name")));
        }
    }

    @Test
    void recentErrorsAreRedactedLimitedToFiveAndExcludeHealthyTasks() throws Exception {
        List<TaskSnapshot> tasks = new ArrayList<>();
        for (int index = 1; index <= 6; index++) {
            tasks.add(new TaskSnapshot("t-" + index, "backup.create",
                "备份失败 password=secret-value-" + index, TaskSnapshot.Status.FAILED,
                1.0, false, true, index == 1 ? null : "E-" + index,
                Instant.now(), Instant.now()));
        }
        tasks.add(new TaskSnapshot("ok-1", "backup.create", "备份完成",
            TaskSnapshot.Status.SUCCEEDED, 1.0, false, false, null, Instant.now(), Instant.now()));
        GeneralSoftwareService system = fake(GeneralSoftwareService.class, Map.of("tasks", args -> tasks));
        try (var host = hostWithBeans(system)) {
            SupportApiSection section = new SupportApiSection(host);

            JsonNode result = section.handle("support.report.preview",
                paramsWithDiagnostics(false, true, false, false), () -> false, ignored -> { });

            JsonNode errors = result.path("recentErrors");
            assertTrue(errors.isArray());
            assertEquals(5, errors.size(), "at most five failed tasks leave the process");
            String firstTitle = errors.get(0).path("title").asText();
            assertTrue(firstTitle.contains("password="));
            assertFalse(firstTitle.contains("secret-value-1"), "the secret value must be redacted");
            assertEquals("", errors.get(0).path("code").asText(), "a missing error code stays empty");
            assertEquals("E-2", errors.get(1).path("code").asText());
        }
    }

    @Test
    void submitAsAGuestSendsNoAccessTokenAndReusesThePreviewedDiagnostics() throws Exception {
        AtomicReference<String> submittedToken = new AtomicReference<>("sentinel");
        AtomicReference<Map<String, Object>> submittedDiagnostics = new AtomicReference<>();
        AtomicReference<ProblemReportDraft> submittedDraft = new AtomicReference<>();
        ProblemReportService reports = fake(ProblemReportService.class, Map.of(
            "submit", args -> {
                submittedDraft.set((ProblemReportDraft) args[0]);
                @SuppressWarnings("unchecked")
                Map<String, Object> diagnostics = (Map<String, Object>) args[1];
                submittedDiagnostics.set(diagnostics);
                submittedToken.set((String) args[2]);
                return receipt();
            }));
        CloudSessionService sessions = new ApiSectionTestSupport.FakeCloudSessions();
        try (var host = hostWithBeans(reports, sessions)) {
            SupportApiSection section = new SupportApiSection(host);

            JsonNode result = section.handle("support.report.submit", fullSubmitParams(),
                () -> false, ignored -> { });

            assertEquals("report-1", result.path("reportId").asText());
            assertEquals("query-token-1", result.path("queryToken").asText());
            assertNull(submittedToken.get(), "a guest submits through the anonymous channel");
            assertEquals("BUG", submittedDraft.get().type().name());
            assertEquals("MINOR", submittedDraft.get().severity().name());
            assertEquals("练习提交失败", submittedDraft.get().summary());
            assertEquals("diagnostic-1", submittedDraft.get().idempotencyKey());
            assertEquals(0, submittedDiagnostics.get().size(), "all diagnostics off sends an empty map");
        }
    }

    @Test
    void submitWithASignedInSessionPassesTheAccessToken() throws Exception {
        AtomicReference<String> submittedToken = new AtomicReference<>();
        ProblemReportService reports = fake(ProblemReportService.class, Map.of(
            "submit", args -> {
                submittedToken.set((String) args[2]);
                return receipt();
            }));
        CloudSessionService sessions = new ApiSectionTestSupport.FakeCloudSessions();
        sessions.signIn(session("teacher-1", "王老师", UserRole.TEACHER));
        try (var host = hostWithBeans(reports, sessions)) {
            SupportApiSection section = new SupportApiSection(host);

            section.handle("support.report.submit", fullSubmitParams(), () -> false, ignored -> { });
        }
        assertEquals("token-teacher-1", submittedToken.get());
    }

    @Test
    void statusWithdrawAndExportRequireReportIdAndQueryToken() throws Exception {
        ProblemReportService reports = fake(ProblemReportService.class, Map.of(
            "status", args -> receipt(),
            "export", args -> new com.sqlteacher.application.support.ProblemReportExport(
                "report-1", "BUG", "RECEIVED", "练习提交失败",
                Instant.parse("2026-09-15T08:00:00Z"), Instant.parse("2026-09-15T08:00:00Z"), List.of()),
            "withdraw", args -> null));
        CloudSessionService sessions = new ApiSectionTestSupport.FakeCloudSessions();
        try (var host = hostWithBeans(reports, sessions)) {
            SupportApiSection section = new SupportApiSection(host);

            ObjectNode params = mapper.createObjectNode().put("reportId", "  ").put("queryToken", "tok");
            assertThrows(IllegalArgumentException.class,
                () -> section.handle("support.report.status", params, () -> false, ignored -> { }));

            JsonNode status = section.handle("support.report.status", mapper.createObjectNode()
                .put("reportId", "report-1").put("queryToken", "tok"), () -> false, ignored -> { });
            assertEquals("report-1", status.path("reportId").asText());

            JsonNode withdrawn = section.handle("support.report.withdraw", mapper.createObjectNode()
                .put("reportId", "report-1").put("queryToken", "tok"), () -> false, ignored -> { });
            assertTrue(withdrawn.path("withdrawn").asBoolean());

            JsonNode export = section.handle("support.report.export", mapper.createObjectNode()
                .put("reportId", "report-1").put("queryToken", "tok"), () -> false, ignored -> { });
            assertEquals("练习提交失败", export.path("summary").asText());
        }
    }

    @Test
    void submitRejectsAnUnknownReportTypeInsteadOfDefaulting() {
        ProblemReportService reports = fake(ProblemReportService.class, Map.of());
        CloudSessionService sessions = new ApiSectionTestSupport.FakeCloudSessions();
        try (var host = hostWithBeans(reports, sessions)) {
            SupportApiSection section = new SupportApiSection(host);
            ObjectNode params = fullSubmitParams().put("type", "not-a-type");

            assertThrows(IllegalArgumentException.class,
                () -> section.handle("support.report.submit", params, () -> false, ignored -> { }));
        }
    }

    private ObjectNode fullSubmitParams() {
        ObjectNode params = mapper.createObjectNode()
            .put("idempotencyKey", "diagnostic-1")
            .put("type", "bug")
            .put("severity", "minor")
            .put("summary", "练习提交失败")
            .put("description", "点击提交后五分钟无响应")
            .put("reproductionSteps", "打开练习，点击提交")
            .put("expectedResult", "出现结果页")
            .put("actualResult", "一直转圈")
            .put("contact", "student@example.com");
        params.putObject("diagnostics");
        return params;
    }

    private ObjectNode paramsWithDiagnostics(boolean environment, boolean recentErrors,
                                             boolean networkSummary, boolean updateState) {
        ObjectNode params = mapper.createObjectNode();
        ObjectNode diagnostics = params.putObject("diagnostics");
        diagnostics.put("environment", environment);
        diagnostics.put("recentErrors", recentErrors);
        diagnostics.put("networkSummary", networkSummary);
        diagnostics.put("updateState", updateState);
        return params;
    }
}
