package com.sqlteacher.application.databaselearning;

import com.sqlteacher.application.knowledge.SafeWebContentFetcher;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class DefaultWebDataLabServiceTest {
    private final URI source = URI.create("https://example.com/students.csv");
    private final SafeWebContentFetcher fetcher = uri -> new SafeWebContentFetcher.FetchedWebContent(
        source, "students", "id,name\n1,Alice\n2,O'Reilly", "abc123", Instant.EPOCH);
    private final DefaultWebDataLabService service = new DefaultWebDataLabService(fetcher);

    @Test
    void previewsBoundedRowsAndBuildsSingleInsertDraft() {
        var preview = service.preview(source);
        assertEquals(2, preview.rows().size());
        String sql = service.buildInsertDraft("student", preview);
        assertTrue(sql.contains("INSERT INTO \"student\""));
        assertTrue(sql.contains("'O''Reilly'"));
        assertEquals(1, sql.lines().filter(line -> line.contains("INSERT INTO")).count());
    }

    @Test
    void rejectsUnsafeTargetIdentifier() {
        var preview = service.preview(source);
        assertThrows(IllegalArgumentException.class, () -> service.buildInsertDraft("student; DROP TABLE x", preview));
    }
}
