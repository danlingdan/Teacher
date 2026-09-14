package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.activity.ActivityResourceUsage;
import com.sqlteacher.application.runner.CodeRunRequest;
import com.sqlteacher.application.runner.CodeRunResult;
import com.sqlteacher.application.runner.LocalCodeRunner;
import com.sqlteacher.application.runner.RunnerCapability;
import com.sqlteacher.application.runner.RunnerFailureReason;
import com.sqlteacher.domain.activity.CodeExecutionLimits;
import com.sqlteacher.domain.activity.CodeLanguage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.fake;
import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.hostWithBeans;
import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.hostWithoutCore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.4.0 REF-9: the system section stays unit-testable on its own: health and editor languages
 * need no Spring core, the guest session shape comes from the access-profile mapping, and the
 * code runner path validates parameters in Java before delegating to the runner port.
 */
class SystemApiSectionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void healthReportsContractVersionWithoutTouchingTheCore() throws Exception {
        SystemApiSection section = new SystemApiSection(hostWithoutCore());

        JsonNode result = section.handle("system.health", mapper.createObjectNode(), () -> false, ignored -> { });

        assertEquals("ready", result.path("status").asText());
        assertEquals(LocalAppContract.VERSION, result.path("contractVersion").asText());
        assertFalse(result.path("applicationVersion").asText().isBlank());
        assertFalse(result.path("coreInitialized").asBoolean());
        assertFalse(result.path("timestamp").asText().isBlank());
    }

    @Test
    void editorLanguagesStaysDeterministicWithoutTheCore() throws Exception {
        SystemApiSection section = new SystemApiSection(hostWithoutCore());

        JsonNode result = section.handle("editor.languages", mapper.createObjectNode(), () -> false, ignored -> { });

        JsonNode languages = result.path("languages");
        assertEquals(2, languages.size());
        assertEquals("sql", languages.get(0).path("id").asText());
        assertEquals("deterministic-catalog", languages.get(0).path("completionSource").asText());
        assertEquals("java", languages.get(1).path("id").asText());
        assertEquals(1_048_576, result.path("maxModelBytes").asInt());
    }

    @Test
    void sessionCurrentFallsBackToTheGuestProfileWithoutCloudSession() throws Exception {
        try (var host = hostWithBeans(new ApiSectionTestSupport.FakeCloudSessions())) {
            SystemApiSection section = new SystemApiSection(host);

            JsonNode result = section.handle("session.current", mapper.createObjectNode(), () -> false, ignored -> { });

            assertEquals("guest", result.path("subjectId").asText());
            assertEquals("访客", result.path("displayName").asText());
            assertEquals("STUDENT", result.path("role").asText());
            assertEquals("访客", result.path("roleLabel").asText());
            assertFalse(result.path("authenticated").asBoolean());
            List<String> permissions = new ArrayList<>();
            result.path("permissions").forEach(item -> permissions.add(item.asText()));
            assertTrue(permissions.contains("HOME"));
            assertFalse(permissions.contains("KNOWLEDGE_CENTER"));
        }
    }

    @Test
    void runnerRunRejectsUnsupportedLanguageBeforeAnyCoreAccess() {
        SystemApiSection section = new SystemApiSection(hostWithoutCore());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> section.handle("runner.run", mapper.createObjectNode()
                .put("language", "cobol").put("sourceCode", "x"), () -> false, ignored -> { }));
        assertEquals("Unsupported code language", error.getMessage());
    }

    @Test
    void runnerRunRejectsOversizedStandardInput() {
        SystemApiSection section = new SystemApiSection(hostWithoutCore());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> section.handle("runner.run", mapper.createObjectNode()
                .put("language", "java")
                .put("sourceCode", "public class Main { }")
                .put("standardInput", "i".repeat(64 * 1024 + 1)), () -> false, ignored -> { }));
        assertEquals("standardInput exceeds 64 KiB", error.getMessage());
    }

    @Test
    void runnerRunMapsTheRequestAndEmitsProgressEvents() throws Exception {
        List<CodeRunRequest> requests = new ArrayList<>();
        LocalCodeRunner runner = fake(LocalCodeRunner.class, Map.of(
            "capabilities", args -> List.of(new RunnerCapability(CodeLanguage.JAVA, true, "")),
            "run", args -> {
                requests.add((CodeRunRequest) args[0]);
                return new CodeRunResult(RunnerFailureReason.NONE, 0, "hello", "",
                    ActivityResourceUsage.evaluationOnly(Duration.ofMillis(2)));
            }));
        List<LocalAppEvent> events = new ArrayList<>();
        try (var host = hostWithBeans(runner)) {
            SystemApiSection section = new SystemApiSection(host);

            JsonNode result = section.handle("runner.run", mapper.createObjectNode()
                .put("language", "java")
                .put("sourceCode", "public class Main { }")
                .put("standardInput", "in"), () -> false, events::add);

            assertEquals(0, result.path("exitCode").asInt());
            assertEquals("hello", result.path("standardOutput").asText());
        }

        assertEquals(1, requests.size());
        assertEquals(CodeLanguage.JAVA, requests.get(0).language());
        assertEquals("public class Main { }", requests.get(0).sourceCode());
        assertEquals("in", requests.get(0).standardInput());
        assertEquals(CodeExecutionLimits.defaults(), requests.get(0).limits());

        assertEquals(2, events.size());
        assertEquals("runner.progress", events.get(0).type());
        assertEquals("starting", events.get(0).payload().path("phase").asText());
        assertEquals("runner.progress", events.get(1).type());
        assertEquals("completed", events.get(1).payload().path("phase").asText());
    }
}
