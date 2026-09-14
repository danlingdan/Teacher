package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.knowledge.CourseKnowledgeArticle;
import com.sqlteacher.application.knowledge.CourseKnowledgeSearchFilter;
import com.sqlteacher.application.knowledge.CourseKnowledgeService;
import com.sqlteacher.application.knowledge.KnowledgeIndexService;
import com.sqlteacher.application.knowledge.KnowledgeReadStateService;
import com.sqlteacher.application.knowledge.KnowledgeSearchResult;
import com.sqlteacher.application.knowledge.KnowledgeVisibility;
import com.sqlteacher.application.collaboration.UserRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.fake;
import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.hostWithBeans;
import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.4.0 REF-9: the knowledge section clamps search and read-state bounds in Java, maps
 * document hits back to article ids, and keeps index rebuilds behind the teacher role guard.
 */
class KnowledgeApiSectionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private static CourseKnowledgeArticle article(String id, String documentId) {
        return new CourseKnowledgeArticle(id, documentId, "数据库课程", "第一章", "索引入门",
            KnowledgeVisibility.PUBLISHED, 1, List.of("索引"), "hash-1", Instant.now());
    }

    @Test
    void knowledgeArticleRejectsBlankArticleId() {
        // knowledge.article 先解析 Spring core 再解析参数（receiver 求值顺序），所以用带 stub 端口的 host；
        // requiredText 仍然在调用服务之前拒绝空 id。
        try (var host = hostWithBeans(fake(CourseKnowledgeService.class, Map.of()))) {
            KnowledgeApiSection section = new KnowledgeApiSection(host);

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> section.handle("knowledge.article", mapper.createObjectNode(), () -> false, ignored -> { }));
            assertEquals("articleId must contain at most 128 characters", error.getMessage());
        }
    }

    @Test
    void knowledgeReadMarkClampsRevisionAndProgress() throws Exception {
        List<Object[]> saves = new ArrayList<>();
        KnowledgeReadStateService readState = fake(KnowledgeReadStateService.class, Map.of(
            "save", args -> {
                saves.add(args);
                return new KnowledgeReadStateService.ReadState(
                    (String) args[0], (Integer) args[1], (Integer) args[2], Instant.now());
            }));
        try (var host = hostWithBeans(readState)) {
            KnowledgeApiSection section = new KnowledgeApiSection(host);

            JsonNode result = section.handle("knowledge.read.mark", mapper.createObjectNode()
                .put("articleId", "a-1")
                .put("revision", 0)
                .put("progressPercent", 250), () -> false, ignored -> { });

            assertEquals("a-1", result.path("articleId").asText());
            assertEquals(1, result.path("revision").asInt());
            assertEquals(100, result.path("progressPercent").asInt());
            assertFalse(result.path("lastReadAt").asText().isBlank());
        }
        assertEquals(1, saves.size());
        assertEquals(1, saves.get(0)[1]);
        assertEquals(100, saves.get(0)[2]);
    }

    @Test
    void knowledgeSearchMapsDocumentIdsToArticleIdsAndClampsTheLimit() throws Exception {
        List<Object[]> searches = new ArrayList<>();
        CourseKnowledgeService knowledge = fake(CourseKnowledgeService.class, Map.of(
            "listArticles", args -> List.of(article("a-1", "d-1")),
            "search", args -> {
                searches.add(args);
                return List.of(
                    new KnowledgeSearchResult("d-1", "索引入门", "数据库课程", 0, "B+ 树…", 0.91),
                    new KnowledgeSearchResult("unknown-doc", "其他文档", "数据库课程", 1, "哈希…", 0.5));
            }));
        try (var host = hostWithBeans(knowledge)) {
            KnowledgeApiSection section = new KnowledgeApiSection(host);

            JsonNode result = section.handle("knowledge.search", mapper.createObjectNode()
                .put("query", "索引")
                .put("limit", 5_000), () -> false, ignored -> { });

            JsonNode items = result.path("items");
            assertEquals(2, items.size());
            assertEquals("a-1", items.get(0).path("articleId").asText());
            assertEquals("d-1", items.get(0).path("documentId").asText());
            assertEquals(0.91, items.get(0).path("relevance").asDouble(), 1e-9);
            // 未匹配到本地文章的命中仍然返回，但 articleId 保持空串而不是猜测。
            assertEquals("", items.get(1).path("articleId").asText());
        }
        assertEquals(1, searches.size());
        assertEquals("索引", searches.get(0)[0]);
        assertEquals(CourseKnowledgeSearchFilter.allLocal(), searches.get(0)[1]);
        assertEquals(100, searches.get(0)[2]);
    }

    @Test
    void knowledgeIndexRebuildRequiresTeacherRole() {
        try (var host = hostWithBeans(new ApiSectionTestSupport.FakeCloudSessions())) {
            KnowledgeApiSection section = new KnowledgeApiSection(host);

            SecurityException error = assertThrows(SecurityException.class,
                () -> section.handle("knowledge.index.rebuild", mapper.createObjectNode(), () -> false, ignored -> { }));
            assertEquals("Teaching workspace requires teacher or administrator role", error.getMessage());
        }
    }

    @Test
    void knowledgeIndexRebuildReturnsTheReportForTeachers() throws Exception {
        var sessions = new ApiSectionTestSupport.FakeCloudSessions();
        sessions.signIn(session("t-1", "教师账号", UserRole.TEACHER));
        KnowledgeIndexService index = fake(KnowledgeIndexService.class,
            Map.of("rebuildAll", args -> new KnowledgeIndexService.IndexReport(5, 1, "重建完成")));
        try (var host = hostWithBeans(sessions, index)) {
            KnowledgeApiSection section = new KnowledgeApiSection(host);

            JsonNode result = section.handle("knowledge.index.rebuild", mapper.createObjectNode(), () -> false, ignored -> { });

            assertEquals(5, result.path("indexedChunks").asInt());
            assertEquals(1, result.path("failedJobs").asInt());
            assertEquals("重建完成", result.path("message").asText());
        }
    }

    @Test
    void knowledgeIndexStatusPassesTheServiceReportThrough() throws Exception {
        KnowledgeIndexService index = fake(KnowledgeIndexService.class,
            Map.of("status", args -> new KnowledgeIndexService.IndexStatus(2, 10, 0, "local", "")));
        try (var host = hostWithBeans(index)) {
            KnowledgeApiSection section = new KnowledgeApiSection(host);

            JsonNode result = section.handle("knowledge.index.status", mapper.createObjectNode(), () -> false, ignored -> { });

            assertEquals(2, result.path("pendingJobs").asInt());
            assertEquals(10, result.path("indexedChunks").asInt());
            assertEquals("local", result.path("mode").asText());
            assertTrue(result.path("message").asText().isEmpty());
        }
    }
}
