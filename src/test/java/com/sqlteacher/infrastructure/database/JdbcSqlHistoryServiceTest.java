package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.execution.SqlHistoryEntry;
import com.sqlteacher.application.execution.SqlHistoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcSqlHistoryServiceTest {
    @TempDir
    Path tempDir;

    private JdbcSqlHistoryService service() throws Exception {
        return serviceAs("");
    }

    private JdbcSqlHistoryService serviceAs(String owner) throws Exception {
        return new JdbcSqlHistoryService(TestDatabases.migratedFactory(tempDir), () -> owner);
    }

    private SqlHistoryEntry entry(String connectionId, String sql, boolean successful) {
        return new SqlHistoryEntry(connectionId, "", sql, successful, 3, 42, Instant.parse("2026-09-07T01:00:00Z"));
    }

    @Test
    void shouldRecordAndListNewestFirst() throws Exception {
        JdbcSqlHistoryService service = service();
        service.record(entry("demo", "select 1", true));
        service.record(entry("demo", "select 2", false));

        List<SqlHistoryEntry> entries = service.list(10);

        assertEquals(2, entries.size());
        assertEquals("select 2", entries.getFirst().sqlText());
        assertTrue(!entries.getFirst().successful());
        assertEquals("select 1", entries.getLast().sqlText());
        assertTrue(entries.getFirst().connectionName().isEmpty());
    }

    @Test
    void shouldKeepOnlyTheNewestEntries() throws Exception {
        JdbcSqlHistoryService service = service();
        for (int index = 0; index < SqlHistoryService.MAX_ENTRIES + 20; index++) {
            service.record(entry("demo", "select " + index, true));
        }

        List<SqlHistoryEntry> entries = service.list(SqlHistoryService.MAX_ENTRIES);

        assertEquals(SqlHistoryService.MAX_ENTRIES, entries.size());
        assertEquals("select " + 219, entries.getFirst().sqlText());
        assertEquals("select 20", entries.getLast().sqlText());
    }

    @Test
    void shouldClearAllEntries() throws Exception {
        JdbcSqlHistoryService service = service();
        service.record(entry("demo", "select 1", true));

        service.clear();

        assertTrue(service.list(10).isEmpty());
    }

    @Test
    void shouldIsolateHistoryByOwnerWhileKeepingLegacyRowsVisible() throws Exception {
        JdbcSqlHistoryService legacy = serviceAs(null);
        legacy.record(entry("demo", "legacy select", true));
        JdbcSqlHistoryService alice = serviceAs("alice");
        alice.record(entry("demo", "alice select", true));
        JdbcSqlHistoryService bob = serviceAs("bob");
        bob.record(entry("demo", "bob select", true));

        List<SqlHistoryEntry> aliceView = alice.list(10);
        List<SqlHistoryEntry> bobView = bob.list(10);

        // v3.7.0 TFB-D4：新记录按 owner 隔离；迁移出的 NULL 旧行对所有人可见。
        assertEquals(2, aliceView.size());
        assertTrue(aliceView.stream().anyMatch(item -> item.sqlText().equals("alice select")));
        assertTrue(aliceView.stream().anyMatch(item -> item.sqlText().equals("legacy select")));
        assertFalse(aliceView.stream().anyMatch(item -> item.sqlText().equals("bob select")));
        assertEquals(2, bobView.size());
    }
}
