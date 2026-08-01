package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.knowledge.KnowledgeDocument;
import com.sqlteacher.application.knowledge.CourseKnowledgeSearchFilter;
import com.sqlteacher.application.knowledge.KnowledgeVisibility;
import com.sqlteacher.application.mock.MockLearningEventService;
import com.sqlteacher.domain.SqlTeacherException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteKnowledgeServiceTest {
    @TempDir Path tempDir;

    @Test
    void shouldImportSearchAndDeleteDocumentAndIndexTransactionally() throws Exception {
        SqliteKnowledgeService service = initialize();
        Path document = tempDir.resolve("aggregation.md");
        Files.writeString(document, "# 聚合查询\n\nUse GROUP BY to group rows before COUNT and SUM.", StandardCharsets.UTF_8);

        KnowledgeDocument imported = service.importDocument(document);

        assertEquals("聚合查询", imported.title());
        assertEquals(1, service.listDocuments().size());
        assertEquals(1, service.search("GROUP BY", 10).size());
        assertThrows(SqlTeacherException.class, () -> service.importDocument(document));

        service.deleteDocument(imported.id());

        assertTrue(service.listDocuments().isEmpty());
        assertTrue(service.search("GROUP BY", 10).isEmpty());
    }

    @Test
    void shouldRejectMalformedUtf8AndUnsupportedFilesWithoutPartialRows() throws Exception {
        SqliteKnowledgeService service = initialize();
        Path malformed = tempDir.resolve("bad.txt");
        Files.write(malformed, new byte[]{(byte) 0xC3, (byte) 0x28});
        Path unsupported = tempDir.resolve("bad.pdf");
        Files.writeString(unsupported, "not a PDF", StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> service.importDocument(malformed));
        assertThrows(IllegalArgumentException.class, () -> service.importDocument(unsupported));
        assertTrue(service.listDocuments().isEmpty());
    }

    @Test
    void shouldVersionPublishFilterAndPreserveCourseKnowledgeHistory() throws Exception {
        SqliteKnowledgeService service = initialize();
        Path document = tempDir.resolve("joins.md");
        Files.writeString(document, "# 表连接\n\nINNER JOIN 只保留匹配行。", StandardCharsets.UTF_8);

        var article = service.importArticle(document, "SQL 基础", "多表查询", java.util.List.of("表连接"));

        assertEquals(KnowledgeVisibility.PRIVATE, article.visibility());
        assertTrue(service.search("INNER JOIN", CourseKnowledgeSearchFilter.published(), 10).isEmpty());
        article = service.changeVisibility(article.id(), KnowledgeVisibility.PUBLISHED);
        assertEquals(1, service.search("INNER JOIN", CourseKnowledgeSearchFilter.published(), 10).size());

        Files.writeString(document, "# 表连接进阶\n\nLEFT JOIN 保留左表全部行。", StandardCharsets.UTF_8);
        article = service.reviseArticle(article.id(), document, java.util.List.of("表连接", "外连接"));
        var detail = service.getArticle(article.id());

        assertEquals(2, article.currentRevision());
        assertEquals(KnowledgeVisibility.PRIVATE, article.visibility());
        assertEquals(2, detail.history().size());
        assertEquals(java.util.List.of("外连接", "表连接"), article.knowledgePoints());
        assertTrue(service.search("LEFT JOIN", CourseKnowledgeSearchFilter.published(), 10).isEmpty());
    }

    @Test
    void shouldIsolatePrivateArticlesByOwnerAndShareOnlyPublishedArticles() throws Exception {
        var owner = new java.util.concurrent.atomic.AtomicReference<>("teacher-a");
        SqliteKnowledgeService service = initialize(owner::get);
        Path document = tempDir.resolve("private.md");
        Files.writeString(document, "# 私有讲义\n\nHAVING filters grouped rows.", StandardCharsets.UTF_8);
        var article = service.importArticle(document, "SQL", "聚合", java.util.List.of("HAVING"));
        String articleId = article.id();

        owner.set("student-b");
        assertTrue(service.listArticles().isEmpty());
        assertThrows(SqlTeacherException.class, () -> service.getArticle(articleId));

        owner.set("teacher-a");
        Files.writeString(document, "# 已发布讲义\n\nHAVING filters grouped rows after aggregation.", StandardCharsets.UTF_8);
        article = service.reviseArticle(article.id(), document, java.util.List.of("HAVING"));
        service.changeVisibility(article.id(), KnowledgeVisibility.PUBLISHED);
        owner.set("student-b");
        assertEquals(1, service.listArticles().size());
        var studentDetail = service.getArticle(article.id());
        assertEquals(article.id(), studentDetail.article().id());
        assertEquals(1, studentDetail.history().size());
        assertEquals(2, studentDetail.revision().revision());
    }

    private SqliteKnowledgeService initialize() {
        return initialize(() -> "guest");
    }

    private SqliteKnowledgeService initialize(com.sqlteacher.application.event.LearningEventOwnerProvider ownerProvider) {
        DatabaseConfiguration databases = new DatabaseConfiguration(tempDir.resolve("app.db"), tempDir.resolve("demo.db"));
        new SqliteAppDatabaseInitializer(new SqlTeacherConfiguration(
            "SQLTeacher", tempDir, databases,
            new AiConfiguration(URI.create("http://localhost:11434"), Duration.ofSeconds(1), Duration.ofSeconds(1), "test")
        )).initialize();
        return new SqliteKnowledgeService(new JdbcConnectionFactory(databases), new MockLearningEventService(), ownerProvider);
    }
}
