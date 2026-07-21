package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.knowledge.KnowledgeDocument;
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

    private SqliteKnowledgeService initialize() {
        DatabaseConfiguration databases = new DatabaseConfiguration(tempDir.resolve("app.db"), tempDir.resolve("demo.db"));
        new SqliteAppDatabaseInitializer(new SqlTeacherConfiguration(
            "SQLTeacher", tempDir, databases,
            new AiConfiguration(URI.create("http://localhost:11434"), Duration.ofSeconds(1), Duration.ofSeconds(1), "test")
        )).initialize();
        return new SqliteKnowledgeService(new JdbcConnectionFactory(databases), new MockLearningEventService());
    }
}
