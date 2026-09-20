package com.sqlteacher.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.CloudSyncItem;
import com.sqlteacher.application.collaboration.UserRole;
import com.sqlteacher.application.event.LearningEventUploadPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v3.7.0 TFB-D1: the cloud store converges uploaded payloads to the shared attribute allowlist. */
class CloudClassroomStoreSyncSanitizationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path directory;

    @Test
    void uploadSanitizesKnownEventTypesToLocalAllowlist() throws Exception {
        Path database = directory.resolve("sanitize.db");
        CloudClassroomStore store = new CloudClassroomStore(database);
        AuthenticatedUser student = student(database, "s-1");
        String rogue = "{\"connectionId\":\"demo\",\"successful\":true,\"attributes\":{"
            + "\"_desktop_owner_id\":\"s-1\","
            + "\"_cloud_event_id\":\"other-device:1\","
            + "\"statementType\":\"SELECT\","
            + "\"rogueLocalKey\":\"internal\","
            + "\"sqlText\":\"" + "x".repeat(9_000) + "\"}}";

        store.upload(student, List.of(new CloudSyncItem("device:1", "SQL_EXECUTION", rogue,
            Instant.parse("2026-09-20T00:00:00Z"), 0)));

        JsonNode stored = JSON.readTree(storedPayload(database, "device:1"));
        JsonNode attributes = stored.get("attributes");
        assertEquals("SELECT", attributes.get("statementType").asText());
        assertFalse(attributes.has("_desktop_owner_id"));
        assertFalse(attributes.has("_cloud_event_id"));
        assertFalse(attributes.has("rogueLocalKey"));
        assertEquals(LearningEventUploadPolicy.SQL_TEXT_LIMIT, attributes.get("sqlText").asText().length());
    }

    @Test
    void uploadKeepsUnknownEventTypesButStripsLocalOnlyKeys() throws Exception {
        Path database = directory.resolve("forward.db");
        CloudClassroomStore store = new CloudClassroomStore(database);
        AuthenticatedUser student = student(database, "s-1");
        String payload = "{\"connectionId\":\"demo\",\"successful\":true,\"attributes\":{"
            + "\"_desktop_owner_id\":\"s-1\",\"futureEvidence\":\"kept\"}}";

        store.upload(student, List.of(new CloudSyncItem("device:2", "FUTURE_EVENT", payload,
            Instant.parse("2026-09-20T00:00:00Z"), 0)));

        JsonNode attributes = JSON.readTree(storedPayload(database, "device:2")).get("attributes");
        assertEquals("kept", attributes.get("futureEvidence").asText());
        assertFalse(attributes.has("_desktop_owner_id"));
    }

    @Test
    void uploadStoresUnparseablePayloadsUnchanged() throws Exception {
        Path database = directory.resolve("raw.db");
        CloudClassroomStore store = new CloudClassroomStore(database);
        AuthenticatedUser student = student(database, "s-1");

        store.upload(student, List.of(new CloudSyncItem("device:3", "SQL_EXECUTION", "not-json",
            Instant.parse("2026-09-20T00:00:00Z"), 0)));

        assertEquals("not-json", storedPayload(database, "device:3"));
    }

    private String storedPayload(Path database, String eventId) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement statement = connection.prepareStatement(
                 "select payload_json from sync_events where event_id=?")) {
            statement.setString(1, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), "event row must exist");
                return rows.getString(1);
            }
        }
    }

    /** sync_events 对 users 有外键：store 级测试先落用户行，再返回带该 id 的会话身份。 */
    private AuthenticatedUser student(Path database, String id) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "insert into users(id,email,display_name,password_hash,password_salt,created_at) values('"
                    + id + "','" + id + "@example.edu','Student',x'00',x'00','2026-01-01T00:00:00Z')");
        }
        return new AuthenticatedUser(id, id + "@example.edu", "Student " + id, Set.of(UserRole.STUDENT));
    }
}
