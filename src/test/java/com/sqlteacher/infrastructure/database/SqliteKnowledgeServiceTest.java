package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.AiConfiguration;
import java.util.List;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.knowledge.CourseKnowledgeImportResult;
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
    void shouldRejectMalformedUtf8InvalidPdfAndUnsupportedFilesWithoutPartialRows() throws Exception {
        SqliteKnowledgeService service = initialize();
        Path malformed = tempDir.resolve("bad.txt");
        Files.write(malformed, new byte[]{(byte) 0xC3, (byte) 0x28});
        Path invalidPdf = tempDir.resolve("bad.pdf");
        Files.writeString(invalidPdf, "not a PDF", StandardCharsets.UTF_8);
        Path unsupported = tempDir.resolve("bad.exe");
        Files.writeString(unsupported, "not supported", StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> service.importDocument(malformed));
        assertThrows(SqlTeacherException.class, () -> service.importDocument(invalidPdf));
        assertThrows(IllegalArgumentException.class, () -> service.importDocument(unsupported));
        assertTrue(service.listDocuments().isEmpty());
    }

    @Test
    void shouldVersionPublishFilterAndPreserveCourseKnowledgeHistory() throws Exception {
        SqliteKnowledgeService service = initialize();
        Path document = tempDir.resolve("joins.md");
        Files.writeString(document, "# 表连接\n\nINNER JOIN 只保留匹配行。", StandardCharsets.UTF_8);

        var article = service.importArticle(document, "SQL 基础", "多表查询", java.util.List.of("表连接"), false).article();

        assertEquals(KnowledgeVisibility.PRIVATE, article.visibility());
        assertTrue(service.search("INNER JOIN", CourseKnowledgeSearchFilter.published(), 10, 0).isEmpty());
        article = service.changeVisibility(article.id(), KnowledgeVisibility.PUBLISHED);
        assertEquals(1, service.search("INNER JOIN", CourseKnowledgeSearchFilter.published(), 10, 0).size());

        Files.writeString(document, "# 表连接进阶\n\nLEFT JOIN 保留左表全部行。", StandardCharsets.UTF_8);
        article = service.reviseArticle(article.id(), document, java.util.List.of("表连接", "外连接"));
        var detail = service.getArticle(article.id());

        assertEquals(2, article.currentRevision());
        assertEquals(KnowledgeVisibility.PRIVATE, article.visibility());
        assertEquals(2, detail.history().size());
        assertEquals(java.util.List.of("外连接", "表连接"), article.knowledgePoints());
        assertTrue(service.search("LEFT JOIN", CourseKnowledgeSearchFilter.published(), 10, 0).isEmpty());
    }

    @Test
    void shouldIsolatePrivateArticlesByOwnerAndShareOnlyPublishedArticles() throws Exception {
        var owner = new java.util.concurrent.atomic.AtomicReference<>("teacher-a");
        SqliteKnowledgeService service = initialize(owner::get);
        Path document = tempDir.resolve("private.md");
        Files.writeString(document, "# 私有讲义\n\nHAVING filters grouped rows.", StandardCharsets.UTF_8);
        var article = service.importArticle(document, "SQL", "聚合", java.util.List.of("HAVING"), false).article();
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

    @Test
    void shouldDeleteTheWholeArticleAggregateWithoutRelyingOnForeignKeys() throws Exception {
        // v3.6.0 KBF-2：应用库连接外键是关闭的，聚合删除必须显式清空修订、混合分块、
        // 索引作业、底层文档与 FTS 分块，不能依赖 on delete cascade。
        SqliteKnowledgeService service = initialize();
        Path document = tempDir.resolve("aggregate-delete.md");
        Files.writeString(document, "# 索引原理\n\nB-TREE 索引加速等值与范围查询。", StandardCharsets.UTF_8);
        var article = service.importArticle(document, "SQL 基础", "索引", java.util.List.of("索引"), false).article();

        service.deleteArticle(article.id());

        assertTrue(service.listArticles().isEmpty());
        assertTrue(service.listDocuments().isEmpty());
        assertTrue(service.search("B-TREE", 10).isEmpty());
        assertThrows(SqlTeacherException.class, () -> service.getArticle(article.id()));
        SqlTeacherException again = assertThrows(SqlTeacherException.class,
            () -> service.deleteArticle(article.id()));
        assertEquals("COURSE_KNOWLEDGE_NOT_FOUND", again.errorCode());
    }

    @Test
    void shouldDeleteOrphanedArticleLeftByTheLegacyDocumentOnlyDeletePath() throws Exception {
        // 旧版删除只清底层文档行，会留下树里仍可见、可打开的文章残影；
        // 聚合删除必须能清掉这种残影（底层文档缺失按 0 行处理，不再报 NOT_FOUND）。
        SqliteKnowledgeService service = initialize();
        Path document = tempDir.resolve("legacy-delete.md");
        Files.writeString(document, "# 视图\n\n视图是存储的查询语句。", StandardCharsets.UTF_8);
        var article = service.importArticle(document, "SQL 基础", "视图", java.util.List.of(), false).article();
        service.deleteDocument(article.documentId());

        assertEquals(1, service.listArticles().size());

        service.deleteArticle(article.id());

        assertTrue(service.listArticles().isEmpty());
        assertTrue(service.listDocuments().isEmpty());
    }

    @Test
    void shouldDetectNormalizedDuplicateContentAndRequireExplicitConsent() throws Exception {
        // v3.6.0 KBF-3：同一内容经不同来源（CRLF/多余空行/BOM 差异）再次导入时，
        // 默认不导入并返回既有文章清单；显式 allowDuplicate 才允许并存。
        SqliteKnowledgeService service = initialize();
        Path original = tempDir.resolve("original.md");
        Files.writeString(original, "# 索引原理\n\nB-TREE 索引加速等值与范围查询。", StandardCharsets.UTF_8);
        var article = service.importArticle(original, "SQL 基础", "索引", java.util.List.of("索引"), false).article();

        Path variant = tempDir.resolve("variant.md");
        Files.writeString(variant, "\uFEFF# 索引原理\r\n\r\n\r\nB-TREE 索引加速等值与范围查询。", StandardCharsets.UTF_8);
        var candidate = service.importArticle(variant, "其他课程", "其他章节", java.util.List.of(), false);

        assertTrue(candidate.duplicateCandidate());
        assertEquals(java.util.List.of(new CourseKnowledgeImportResult.ContentDuplicate(
            article.id(), "索引原理", "SQL 基础", "索引", 1)), candidate.duplicates());
        assertEquals(1, service.listArticles().size());

        var forced = service.importArticle(variant, "其他课程", "其他章节", java.util.List.of(), true);
        assertTrue(!forced.duplicateCandidate());
        assertEquals(2, service.listArticles().size());
    }

    @Test
    void shouldKeepLegacyDuplicateDocumentErrorForByteIdenticalForcedImport() throws Exception {
        // 字节级完全相同的强制导入仍受 knowledge_documents.content_hash 唯一约束：
        // 交互式查重给出友好提示，硬绕过时保留既有 KNOWLEDGE_DOCUMENT_DUPLICATE 错误。
        SqliteKnowledgeService service = initialize();
        Path document = tempDir.resolve("same.md");
        Files.writeString(document, "# 事务\n\nACID 是事务的四个特性。", StandardCharsets.UTF_8);
        service.importArticle(document, "SQL 基础", "事务", java.util.List.of(), false);

        var candidate = service.importArticle(document, "SQL 基础", "事务", java.util.List.of(), false);
        assertTrue(candidate.duplicateCandidate());

        SqlTeacherException error = assertThrows(SqlTeacherException.class,
            () -> service.importArticle(document, "SQL 基础", "事务", java.util.List.of(), true));
        assertEquals("KNOWLEDGE_DOCUMENT_DUPLICATE", error.errorCode());
    }

    private SqliteKnowledgeService initialize() {
        return initialize(() -> "guest");
    }

    @Test
    void shouldChunkAlongStructuralBoundariesWithoutSplittingCodeOrTables() {
        // v3.6.0 KBQ-1：标题开新块；代码块与表格不被拦腰切断；超长块按行边界二次切分。
        String document = String.join("\n",
            "# 第一章",
            "引言段落。",
            "",
            "## 第二节",
            "下面是代码：",
            "",
            "```sql",
            "SELECT a,",
            "       b,",
            "       c",
            "FROM t;",
            "```",
            "",
            "| 列一 | 列二 |",
            "| ---- | ---- |",
            "| a    | b    |");
        List<String> chunks = SqliteKnowledgeService.chunk(document);

        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).startsWith("# 第一章"));
        assertTrue(chunks.get(1).startsWith("## 第二节"));
        // 代码块整体落在同一个分块里。
        assertTrue(chunks.get(1).contains("SELECT a,\n       b,\n       c\nFROM t;"));
        // 表格整体不被切断（行内多余空白按既有规则折叠为单个空格）。
        assertTrue(chunks.get(1).contains("| 列一 | 列二 |"));
        assertTrue(chunks.get(1).contains("| a | b |"));

        // 每个分块都不超过上限；单行超长的代码块按行边界切分。
        String longLine = "x".repeat(SqliteKnowledgeService.MAX_CHUNK_CHARACTERS + 200);
        String codeHeavy = "# 标题\n\n```text\n" + longLine + "\n还有一行\n```";
        for (String piece : SqliteKnowledgeService.chunk(codeHeavy)) {
            assertTrue(piece.length() <= SqliteKnowledgeService.MAX_CHUNK_CHARACTERS + 1);
        }
    }

    @Test
    void shouldRechunkAllArticlesWithTheCurrentAlgorithmAndResetIndexJobs() throws Exception {
        SqliteKnowledgeService service = initialize();
        Path document = tempDir.resolve("rechunk.md");
        Files.writeString(document, "# Overview\n\nlegacy rechunk content", StandardCharsets.UTF_8);
        var article = service.importArticle(document, "SQL 基础", "总览", java.util.List.of(), false).article();

        assertEquals(1, service.rechunkAllArticles());

        // 重切后检索仍可用（FTS 触发器随 knowledge_chunks 重建）。
        assertEquals(1, service.search("rechunk", 10).size());
        assertEquals(article.id(), service.getArticle(article.id()).article().id());
    }

    @Test
    void shouldPushFiltersDownAndFallBackToOrWhenAndMisses() throws Exception {
        SqliteKnowledgeService service = initialize();
        Path document = tempDir.resolve("filter.md");
        Files.writeString(document, "# 聚合函数\n\nGROUP BY 配合 HAVING 过滤分组。", StandardCharsets.UTF_8);
        var article = service.importArticle(document, "SQL 进阶", "聚合查询", java.util.List.of("聚合"), false).article();
        service.changeVisibility(article.id(), KnowledgeVisibility.PUBLISHED);

        // 课程/章节过滤下推：命中。
        assertEquals(1, service.search("GROUP BY", new CourseKnowledgeSearchFilter(
            "SQL 进阶", "聚合查询", "", false), 10, 0).size());
        // 课程不匹配：无命中（下推生效，而不是取回后再过滤）。
        assertTrue(service.search("GROUP BY", new CourseKnowledgeSearchFilter(
            "别的课程", "", "", false), 10, 0).isEmpty());
        // AND 无命中（"聚合" 不是独立 token）→ OR + bm25 兜底仍可凭 GROUP 召回。
        assertEquals(1, service.search("GROUP 聚合", CourseKnowledgeSearchFilter.published(), 10, 0).size());
        // 分页 offset 越过结果集：空。
        assertTrue(service.search("GROUP BY", CourseKnowledgeSearchFilter.published(), 10, 1).isEmpty());
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
