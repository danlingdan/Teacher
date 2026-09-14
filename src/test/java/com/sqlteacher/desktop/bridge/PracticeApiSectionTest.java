package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.collaboration.CloudBankApi;
import com.sqlteacher.application.exercise.ExerciseCatalogItem;
import com.sqlteacher.application.exercise.ExerciseCatalogPage;
import com.sqlteacher.application.exercise.ExerciseCatalogService;
import com.sqlteacher.application.exercise.ExercisePracticeService;
import com.sqlteacher.domain.exercise.ExerciseDifficulty;
import com.sqlteacher.domain.exercise.ExerciseType;
import com.sqlteacher.infrastructure.database.ExerciseBankSyncService;
import com.sqlteacher.infrastructure.system.ExerciseBankPreferencesStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.fake;
import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.hostWithBeans;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.4.0 REF-9: the practice section clamps catalog paging in Java, delegates session
 * lifecycle calls with the parsed ids, and the bank surface degrades quietly when the
 * cloud server is unreachable so local practice never blocks.
 */
class PracticeApiSectionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDirectory;

    @Test
    void practiceCatalogClampsPagingParameters() throws Exception {
        List<Object[]> pages = new ArrayList<>();
        ExerciseCatalogService catalog = fake(ExerciseCatalogService.class, Map.of(
            "listExercises", args -> {
                pages.add(args);
                return new ExerciseCatalogPage(List.of(), 0, (Integer) args[0], (Integer) args[1]);
            }));
        try (var host = hostWithBeans(catalog)) {
            PracticeApiSection section = new PracticeApiSection(host);

            JsonNode result = section.handle("practice.catalog", mapper.createObjectNode()
                .put("pageSize", 5_000)
                .put("page", -3), () -> false, ignored -> { });

            assertEquals(0, result.path("page").asInt());
            assertEquals(500, result.path("pageSize").asInt());
        }
        assertEquals(1, pages.size());
        assertEquals(0, pages.get(0)[0]);
        assertEquals(500, pages.get(0)[1]);
        assertEquals("", pages.get(0)[2]);
        assertEquals("", pages.get(0)[3]);
        assertEquals("", pages.get(0)[4]);
    }

    @Test
    void practiceCatalogWithoutPagingReturnsTheAvailableItems() throws Exception {
        ExerciseCatalogService catalog = fake(ExerciseCatalogService.class, Map.of(
            "listAvailableExercises", args -> List.of(new ExerciseCatalogItem(
                "ex-1", "查询全部学生", "SELECT 基础", ExerciseDifficulty.BEGINNER,
                ExerciseType.QUERY, 1, 0, false, "", null))));
        try (var host = hostWithBeans(catalog)) {
            PracticeApiSection section = new PracticeApiSection(host);

            JsonNode result = section.handle("practice.catalog", mapper.createObjectNode(), () -> false, ignored -> { });

            assertEquals(1, result.path("items").size());
            assertEquals("ex-1", result.path("items").get(0).path("id").asText());
        }
    }

    @Test
    void practicePreviewRejectsUnavailableExercises() {
        ExerciseCatalogService catalog = fake(ExerciseCatalogService.class,
            Map.of("findAvailableExercise", args -> Optional.empty()));
        try (var host = hostWithBeans(catalog)) {
            PracticeApiSection section = new PracticeApiSection(host);

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> section.handle("practice.preview", mapper.createObjectNode()
                    .put("exerciseId", "missing"), () -> false, ignored -> { }));
            assertEquals("Exercise is not available", error.getMessage());
        }
    }

    @Test
    void practiceStartRejectsBlankExerciseId() {
        // practice.start 先解析 Spring core 再解析参数（receiver 求值顺序），所以用带 stub 端口的 host；
        // requiredText 仍然在调用 start() 之前拒绝空 id。
        try (var host = hostWithBeans(fake(ExercisePracticeService.class, Map.of()))) {
            PracticeApiSection section = new PracticeApiSection(host);

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> section.handle("practice.start", mapper.createObjectNode()
                    .put("exerciseId", "  "), () -> false, ignored -> { }));
            assertEquals("exerciseId must contain at most 128 characters", error.getMessage());
        }
    }

    @Test
    void practiceCloseDelegatesTheSessionId() throws Exception {
        List<String> closed = new ArrayList<>();
        ExercisePracticeService practice = fake(ExercisePracticeService.class, Map.of(
            "close", args -> {
                closed.add((String) args[0]);
                return null;
            }));
        try (var host = hostWithBeans(practice)) {
            PracticeApiSection section = new PracticeApiSection(host);

            JsonNode result = section.handle("practice.close", mapper.createObjectNode()
                .put("sessionId", "s-1"), () -> false, ignored -> { });

            assertTrue(result.path("closed").asBoolean());
            assertEquals("s-1", result.path("sessionId").asText());
        }
        assertEquals(List.of("s-1"), closed);
    }

    @Test
    void practiceBankNoticeStartsEmptyForFreshPreferences() throws Exception {
        var store = new ExerciseBankPreferencesStore(tempDirectory);
        try (var host = hostWithBeans(store)) {
            PracticeApiSection section = new PracticeApiSection(host);

            JsonNode result = section.handle("practice.bank.notice", mapper.createObjectNode(), () -> false, ignored -> { });

            assertTrue(result.has("notice"));
            assertTrue(result.path("notice").isNull());
        }
    }

    @Test
    void practiceBankCheckDegradesQuietlyWhenTheServerIsUnavailable() throws Exception {
        // 未 stub 的 CloudBankApi 对任何调用抛错，等价于离线服务器；同步服务必须静默降级。
        var sync = new ExerciseBankSyncService(
            fake(CloudBankApi.class, Map.of()), tempDirectory.resolve("app.db").toString());
        var store = new ExerciseBankPreferencesStore(tempDirectory);
        try (var host = hostWithBeans(sync, store)) {
            PracticeApiSection section = new PracticeApiSection(host);

            JsonNode result = section.handle("practice.bank.check", mapper.createObjectNode(), () -> false, ignored -> { });

            assertEquals(0, result.path("appliedVersion").asInt());
            assertEquals(0, result.path("serverVersion").asInt());
            assertEquals(0, result.path("pendingBlocks").asInt());
            assertTrue(result.path("upToDate").asBoolean());
            assertEquals("题库已是最新。", result.path("message").asText());
        }
    }
}
