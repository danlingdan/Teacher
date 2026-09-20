package com.sqlteacher.server;

import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.ClassLearningOverview;
import com.sqlteacher.application.collaboration.ClassroomEventPage;
import com.sqlteacher.application.collaboration.CloudSyncItem;
import com.sqlteacher.application.collaboration.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** v3.7.0 TFB-S1/S2: teacher-facing classroom event details and learning overview. */
class CloudClassroomStoreLearningDetailTest {
    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");

    @TempDir Path directory;

    @Test
    void overviewSummarizesDistributionSevenDayActivesAndTrend() throws Exception {
        Path database = directory.resolve("overview.db");
        CloudClassroomStore store = store(database);
        String classroomId = setupClassWithStudent(store, database);

        store.upload(student("s-1"), List.of(
            item("e-1", "SQL_EXECUTION", "2026-09-20T01:00:00Z", true),
            item("e-2", "EXERCISE_FAILED", "2026-09-20T02:00:00Z", false),
            item("e-3", "SQL_EXECUTION", "2026-08-01T00:00:00Z", true)));

        ClassLearningOverview overview = store.classLearningOverview(teacher("t-1"), classroomId);

        assertEquals(1, overview.summary().studentCount());
        assertEquals(1, overview.summary().activeStudentCount());
        assertEquals(3, overview.summary().syncedEvents());
        assertEquals(2, overview.summary().successfulEvents());
        assertEquals(1, overview.activeStudents7d());
        assertEquals(2L, overview.eventsByType().get("SQL_EXECUTION"));
        assertEquals(1L, overview.eventsByType().get("EXERCISE_FAILED"));
        assertEquals(14, overview.trend().size());
        assertEquals(2L, overview.trend().get(13).events());
        assertEquals(1L, overview.trend().get(13).activeStudents());
        assertEquals("2026-09-20", overview.trend().get(13).date());
        assertEquals(0L, overview.trend().getFirst().events());
    }

    @Test
    void eventsPageReturnsFilterableCursorPaginatedDetailsWithAudit() throws Exception {
        Path database = directory.resolve("details.db");
        CloudClassroomStore store = store(database);
        String classroomId = setupClassWithStudent(store, database);
        store.upload(student("s-1"), List.of(
            item("e-1", "SQL_EXECUTION", "2026-09-20T01:00:00Z", true),
            item("e-2", "EXERCISE_FAILED", "2026-09-20T02:00:00Z", false),
            item("e-3", "SQL_EXECUTION", "2026-08-01T00:00:00Z", true)));

        ClassroomEventPage page = store.classroomEvents(teacher("t-1"), classroomId, "s-1",
            null, null, null, null, 50);

        assertEquals(3, page.entries().size());
        assertNull(page.nextCursor());
        assertEquals("SELECT 1", page.entries().getFirst().attributes().get("sqlText"));
        assertFalse(page.entries().get(1).successful());

        ClassroomEventPage singlePage = store.classroomEvents(teacher("t-1"), classroomId, "s-1",
            null, null, null, null, 1);
        assertEquals(1, singlePage.entries().size());
        assertNotNull(singlePage.nextCursor());
        ClassroomEventPage secondPage = store.classroomEvents(teacher("t-1"), classroomId, "s-1",
            null, null, null, singlePage.nextCursor(), 50);
        assertEquals(2, secondPage.entries().size());

        assertEquals(2, store.classroomEvents(teacher("t-1"), classroomId, "s-1",
            "SQL_EXECUTION", null, null, null, 50).entries().size());
        assertEquals(2, store.classroomEvents(teacher("t-1"), classroomId, "s-1",
            null, Instant.parse("2026-09-01T00:00:00Z"), null, null, 50).entries().size());

        assertEquals(5, auditRows(database));
    }

    @Test
    void eventsPageEnforcesTeacherRoleAndClassMembership() throws Exception {
        Path database = directory.resolve("access.db");
        CloudClassroomStore store = store(database);
        String classroomId = setupClassWithStudent(store, database);
        seedUser(database, "outsider");

        assertThrows(SecurityException.class, () -> store.classroomEvents(student("s-1"), classroomId, "s-1",
            null, null, null, null, 50));
        assertThrows(SecurityException.class, () -> store.classLearningOverview(student("s-1"), classroomId));
        assertThrows(IllegalArgumentException.class, () -> store.classroomEvents(teacher("t-1"), classroomId, "outsider",
            null, null, null, null, 50));
        assertThrows(IllegalArgumentException.class, () -> store.classroomEvents(teacher("t-1"), classroomId, "s-1",
            "NOT_AN_EVENT_TYPE", null, null, null, 50));
    }

    private CloudClassroomStore store(Path database) throws Exception {
        return new CloudClassroomStore(database, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String setupClassWithStudent(CloudClassroomStore store, Path database) throws Exception {
        seedUser(database, "t-1");
        seedUser(database, "s-1");
        String classroomId = store.create(teacher("t-1"), "Feedback 101").id();
        store.addMember(teacher("t-1"), classroomId, "s-1", UserRole.STUDENT);
        return classroomId;
    }

    private static CloudSyncItem item(String eventId, String eventType, String occurredAt, boolean successful) {
        String payload = "{\"connectionId\":\"demo\",\"successful\":" + successful
            + ",\"attributes\":{\"statementType\":\"SELECT\",\"sqlText\":\"SELECT 1\"}}";
        return new CloudSyncItem("device:" + eventId, eventType, payload, Instant.parse(occurredAt), 0);
    }

    private void seedUser(Path database, String id) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "insert into users(id,email,display_name,password_hash,password_salt,created_at) values('"
                    + id + "','" + id + "@example.edu','User',x'00',x'00','2026-01-01T00:00:00Z')");
        }
    }

    private int auditRows(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select count(*) from export_audit")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private AuthenticatedUser teacher(String id) {
        return new AuthenticatedUser(id, id + "@example.edu", "Teacher", Set.of(UserRole.TEACHER));
    }

    private AuthenticatedUser student(String id) {
        return new AuthenticatedUser(id, id + "@example.edu", "Student", Set.of(UserRole.STUDENT));
    }
}
