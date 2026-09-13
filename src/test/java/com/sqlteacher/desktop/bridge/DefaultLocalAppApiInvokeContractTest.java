package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.4.0 REF-8/REF-9: pins the frozen {@code 3.0-v1} IPC behavior of methods that are moved from
 * the DefaultLocalAppApi switch into business-domain sections. Written against the pre-split
 * facade and must pass unchanged after the registry refactor.
 */
class DefaultLocalAppApiInvokeContractTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @TempDir
    Path tempDirectory;

    @Test
    void editorLanguagesMustStayPureJavaWithoutCoreInitialization() throws Exception {
        try (var api = new DefaultLocalAppApi(mapper)) {
            var result = api.invoke("editor.languages", mapper.createObjectNode(), () -> false, ignored -> { });

            var languages = result.path("languages");
            assertTrue(languages.isArray());
            assertEquals(2, languages.size());
            assertEquals("sql", languages.get(0).path("id").asText());
            assertEquals("deterministic-catalog", languages.get(0).path("completionSource").asText());
            assertEquals("java", languages.get(1).path("id").asText());
            assertEquals(1_048_576, result.path("maxModelBytes").asInt());
        }
    }

    @Test
    void unknownMethodMustFailWithWhitelistMessageBeforeAnyDispatch() {
        try (var api = new DefaultLocalAppApi(mapper)) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> api.invoke("system.not.a.method", mapper.createObjectNode(), () -> false, ignored -> { }));
            assertEquals("Unknown local application method: system.not.a.method", error.getMessage());
        }
    }

    @Test
    @ResourceLock("sqlteacher.data.dir")
    void homeSummaryMustExposeDeterministicLocalDashboardShape() throws Exception {
        System.setProperty("sqlteacher.data.dir", tempDirectory.resolve("home-data").toString());
        try (var api = new DefaultLocalAppApi(mapper)) {
            var result = api.invoke("home.summary", mapper.createObjectNode(), () -> false, ignored -> { });

            assertFalse(result.path("ownerId").asText().isBlank());
            assertFalse(result.path("policyVersion").asText().isBlank());
            assertTrue(result.path("knowledgePointCount").asInt() >= 0);
            assertFalse(result.path("cloudAvailable").asBoolean());
            assertTrue(result.path("calculationMillis").asLong() >= 0);
            assertTrue(result.path("actions").isArray());
            result.path("actions").forEach(action -> {
                assertFalse(action.path("id").asText().isBlank());
                assertTrue(action.path("priority").asInt() >= 0);
                assertFalse(action.path("reason").asText().isBlank());
            });

            var dismissed = api.invoke("home.action.dismiss", mapper.createObjectNode()
                .put("actionId", "missing-action"), () -> false, ignored -> { });
            assertTrue(dismissed.path("dismissed").asBoolean());
            assertEquals("missing-action", dismissed.path("actionId").asText());
        } finally {
            System.clearProperty("sqlteacher.data.dir");
        }
    }

    @Test
    @ResourceLock("sqlteacher.data.dir")
    void sqlExecutePagingAndResultPageMustKeepTheirFrozenResponseShape() throws Exception {
        System.setProperty("sqlteacher.data.dir", tempDirectory.resolve("sql-data").toString());
        try (var api = new DefaultLocalAppApi(mapper)) {
            var executed = api.invoke("sql.execute", mapper.createObjectNode()
                .put("connectionId", "demo")
                .put("sql", "SELECT name FROM sqlite_master ORDER BY name")
                .put("maxRows", 500)
                .put("pageSize", 1), () -> false, ignored -> { });

            String resultId = executed.path("resultId").asText();
            assertFalse(resultId.isBlank());
            assertTrue(executed.path("totalRows").asInt() >= 1);
            assertTrue(executed.path("rows").size() <= 1);
            assertEquals(executed.path("totalRows").asInt() > 1, executed.path("hasMore").asBoolean());
            assertTrue(executed.path("auditRecorded").asBoolean());

            int totalPages = (executed.path("totalRows").asInt() + 0) / 1;
            var lastPage = api.invoke("sql.result.page", mapper.createObjectNode()
                .put("resultId", resultId)
                .put("page", totalPages - 1)
                .put("pageSize", 1), () -> false, ignored -> { });
            assertFalse(lastPage.path("hasMore").asBoolean());

            var expired = assertThrows(IllegalArgumentException.class, () -> api.invoke("sql.result.page",
                mapper.createObjectNode().put("resultId", "no-such-result"), () -> false, ignored -> { }));
            assertEquals("SQL result page has expired", expired.getMessage());
        } finally {
            System.clearProperty("sqlteacher.data.dir");
        }
    }

    @Test
    @ResourceLock("sqlteacher.data.dir")
    void riskySqlMustRequireConfirmationAndRejectMismatchedTokenConsumption() throws Exception {
        System.setProperty("sqlteacher.data.dir", tempDirectory.resolve("confirm-data").toString());
        try (var api = new DefaultLocalAppApi(mapper)) {
            var analysis = api.invoke("sql.analyze", mapper.createObjectNode()
                .put("connectionId", "demo")
                .put("sql", "DELETE FROM learning_events"), () -> false, ignored -> { });

            // 默认教学模式下写操作必须确认（fail-closed），令牌必须由 Java 签发。
            assertTrue(analysis.path("confirmationRequired").asBoolean());
            String token = analysis.path("confirmationToken").asText();
            assertFalse(token.isBlank());
            assertEquals("java", analysis.path("enforcedBy").asText());

            // 用同一令牌提交不同 SQL：哈希不匹配，必须拒绝且不得执行。
            var mismatched = assertThrows(IllegalArgumentException.class, () -> api.invoke("sql.execute",
                mapper.createObjectNode()
                    .put("connectionId", "demo")
                    .put("sql", "DELETE FROM learning_event_attributes")
                    .put("confirmationToken", token), () -> false, ignored -> { }));
            assertEquals("A current confirmation token is required", mismatched.getMessage());
        } finally {
            System.clearProperty("sqlteacher.data.dir");
        }
    }

    @Test
    @ResourceLock("sqlteacher.data.dir")
    void bankPreferenceSaveMustPersistAndNoticeMustStartEmpty() throws Exception {
        System.setProperty("sqlteacher.data.dir", tempDirectory.resolve("bank-data").toString());
        try (var api = new DefaultLocalAppApi(mapper)) {
            var saved = api.invoke("settings.bank.update", mapper.createObjectNode()
                .put("autoCheckEnabled", true)
                .set("subscribedChannels", mapper.createArrayNode().add("network")),
                () -> false, ignored -> { });
            assertTrue(saved.path("saved").asBoolean());

            var preferences = api.invoke("settings.preferences", mapper.createObjectNode(),
                () -> false, ignored -> { });
            assertTrue(preferences.path("bank").path("autoCheckEnabled").asBoolean());
            assertEquals("network", preferences.path("bank").path("subscribedChannels").get(0).asText());

            var notice = api.invoke("practice.bank.notice", mapper.createObjectNode(),
                () -> false, ignored -> { });
            assertTrue(notice.has("notice"));
            assertTrue(notice.path("notice").isNull());
            assertNotNull(notice);
        } finally {
            System.clearProperty("sqlteacher.data.dir");
        }
    }
}
