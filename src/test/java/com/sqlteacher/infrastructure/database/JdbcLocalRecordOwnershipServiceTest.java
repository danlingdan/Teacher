package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.application.event.LocalRecordOwnershipService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcLocalRecordOwnershipServiceTest {
    @TempDir Path directory;

    private JdbcLocalRecordOwnershipService newService(String currentOwner) throws Exception {
        DatabaseConfiguration databaseConfiguration = new DatabaseConfiguration(
            directory.resolve("app.db"), directory.resolve("demo.db"));
        SqlTeacherConfiguration configuration = new SqlTeacherConfiguration(
            "SQLTeacher", directory, databaseConfiguration,
            new AiConfiguration(java.net.URI.create("http://localhost:11434"),
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(30), "test-model"));
        new SqliteAppDatabaseInitializer(configuration).initialize();
        return new JdbcLocalRecordOwnershipService(
            new JdbcConnectionFactory(databaseConfiguration),
            () -> currentOwner);
    }

    private void insertRecords() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("app.db").toAbsolutePath());
             var events = connection.prepareStatement(
                 "insert into learning_events(event_type,occurred_at,connection_id,successful,owner) values('SQL_EXECUTION','2026-09-21T00:00:00Z','app',1,?)");
             var history = connection.prepareStatement(
                 "insert into sql_history(connection_id,sql_text,successful,owner_id) values('app','select 1',1,?)");
             var legacy = connection.createStatement()) {
            events.setString(1, LearningEventOwnerProvider.GUEST_OWNER);
            events.executeUpdate();
            events.setString(1, "user-1");
            events.executeUpdate();
            history.setString(1, LearningEventOwnerProvider.GUEST_OWNER);
            history.executeUpdate();
            history.setString(1, "user-1");
            history.executeUpdate();
            // v3.7.0 迁移出的 NULL 旧行保持全员可见,不属于 guest,不得被并入。
            legacy.executeUpdate("insert into sql_history(connection_id,sql_text,successful) values('app','select 2',1)");
        }
    }

    @Test void countsOnlyGuestOwnedRecords() throws Exception {
        JdbcLocalRecordOwnershipService service = newService("user-1");
        insertRecords();
        LocalRecordOwnershipService.GuestRecordCount counts = service.countGuestRecords();
        assertEquals(1, counts.learningEvents());
        assertEquals(1, counts.sqlHistory());
        assertEquals(2, counts.total());
    }

    @Test void mergeRejectsTheGuestIdentity() throws Exception {
        JdbcLocalRecordOwnershipService service = newService("guest");
        insertRecords();
        assertThrows(IllegalStateException.class, service::mergeGuestRecordsIntoCurrentUser);
    }

    @Test void mergeAdoptsGuestRowsAndLeavesOthersUntouched() throws Exception {
        JdbcLocalRecordOwnershipService service = newService("user-2");
        insertRecords();
        long merged = service.mergeGuestRecordsIntoCurrentUser();
        assertEquals(2, merged);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("app.db").toAbsolutePath());
             var rows = connection.createStatement().executeQuery(
                 "select owner, count(*) from learning_events group by owner order by owner")) {
            assertTrue(rows.next());
            assertEquals("user-1", rows.getString(1));
            assertEquals(1, rows.getInt(2));
            assertTrue(rows.next());
            assertEquals("user-2", rows.getString(1));
            assertEquals(1, rows.getInt(2));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("app.db").toAbsolutePath());
             var rows = connection.createStatement().executeQuery(
                 "select owner_id, count(*) from sql_history group by owner_id order by owner_id")) {
            // 第一行是 NULL(legacy):归并不改变它;其余按账号分组。
            assertTrue(rows.next());
            assertNull(rows.getString(1));
            assertEquals(1, rows.getInt(2));
            assertTrue(rows.next());
            assertEquals("user-1", rows.getString(1));
            assertEquals(1, rows.getInt(2));
            assertTrue(rows.next());
            assertEquals("user-2", rows.getString(1));
            assertEquals(1, rows.getInt(2));
        }
    }
}
