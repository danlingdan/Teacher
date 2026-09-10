package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.collaboration.AssignmentStatus;
import com.sqlteacher.application.collaboration.ClassAssignment;
import com.sqlteacher.application.collaboration.ClassLearningSummary;
import com.sqlteacher.application.collaboration.ClassroomService;
import com.sqlteacher.application.collaboration.CloudApiClient;
import com.sqlteacher.application.collaboration.CloudAuthenticationService;
import com.sqlteacher.application.collaboration.CloudSyncItem;
import com.sqlteacher.application.collaboration.ExerciseBankBlock;
import com.sqlteacher.application.collaboration.ExerciseBankManifest;
import com.sqlteacher.application.collaboration.UserRole;
import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.domain.SqlTeacherException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExerciseBankSyncServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldDetectUpToDateBankWithoutDownloading() {
        Fixture fixture = new Fixture();
        fixture.manifest = new ExerciseBankManifest(
            1,
            List.of(new ExerciseBankManifest.BlockRef(ExerciseBankApplier.DATASET_ID, 1, "ignored")),
            List.of()
        );

        var status = fixture.service().check();
        var result = fixture.service().update(null);

        assertTrue(status.upToDate());
        assertEquals(0, status.pendingBlocks());
        assertFalse(result.applied());
        assertEquals("题库已是最新。", result.message());
    }

    @Test
    void shouldStreamOnlyPendingBlocksAndApplyAtomically() {
        Fixture fixture = new Fixture();
        String updatedBlock = exerciseBlock(
            "query-01", "查询全部学生（更新版）",
            "select id, name, class_name, score from student order by id", 4);
        String newBlock = exerciseBlock(
            "sync-new", "统计学生总数",
            "select count(*) as total from student", 1);
        fixture.manifest = new ExerciseBankManifest(
            7,
            List.of(new ExerciseBankManifest.BlockRef(ExerciseBankApplier.DATASET_ID, 1, "ignored")),
            List.of(
                new ExerciseBankManifest.BlockRef("query-01", 4, sha256Hex(updatedBlock)),
                new ExerciseBankManifest.BlockRef("sync-new", 1, sha256Hex(newBlock))
            )
        );
        fixture.blocks.put("EXERCISE:query-01",
            new ExerciseBankBlock("EXERCISE", "query-01", 4, sha256Hex(updatedBlock), updatedBlock));
        fixture.blocks.put("EXERCISE:sync-new",
            new ExerciseBankBlock("EXERCISE", "sync-new", 1, sha256Hex(newBlock), newBlock));

        var status = fixture.service().check();
        List<String> progress = new java.util.ArrayList<>();
        var result = fixture.service().update(progress::add);

        assertFalse(status.upToDate());
        assertEquals(2, status.pendingBlocks());
        assertTrue(result.applied());
        assertEquals(7, result.appliedVersion());
        assertEquals(2, result.updatedExercises());
        assertEquals(4, fixture.storedVersion("query-01"));
        assertEquals(1, fixture.storedVersion("sync-new"));
        assertTrue(progress.contains("completed"));
    }

    @Test
    void shouldRejectTamperedBlockWithoutChangingLocalBank() {
        Fixture fixture = new Fixture();
        String block = exerciseBlock(
            "query-01", "被篡改的题面",
            "select id, name, class_name, score from student order by id", 4);
        fixture.manifest = new ExerciseBankManifest(
            5,
            List.of(),
            List.of(new ExerciseBankManifest.BlockRef("query-01", 4, "0".repeat(64)))
        );
        fixture.blocks.put("EXERCISE:query-01",
            new ExerciseBankBlock("EXERCISE", "query-01", 4, "0".repeat(64), block));

        SqlTeacherException error = assertThrows(
            SqlTeacherException.class, () -> fixture.service().update(null)
        );

        assertEquals("EXERCISE_BANK_INVALID", error.errorCode());
        assertEquals(3, fixture.storedVersion("query-01"));
    }

    @Test
    void shouldKeepLocalBankWhenReassembledPackageFailsSelfTest() {
        Fixture fixture = new Fixture();
        String broken = exerciseBlock(
            "sync-bad", "引用缺失表的题",
            "select name from missing_table", 1);
        fixture.manifest = new ExerciseBankManifest(
            9,
            List.of(),
            List.of(new ExerciseBankManifest.BlockRef("sync-bad", 1, sha256Hex(broken)))
        );
        fixture.blocks.put("EXERCISE:sync-bad",
            new ExerciseBankBlock("EXERCISE", "sync-bad", 1, sha256Hex(broken), broken));

        SqlTeacherException error = assertThrows(
            SqlTeacherException.class, () -> fixture.service().update(null)
        );

        assertEquals("EXERCISE_BANK_INVALID", error.errorCode());
        assertEquals(0, fixture.storedVersion("sync-bad"));
        assertTrue(fixture.listExercises().stream().noneMatch(title -> title.equals("引用缺失表的题")));
    }

    private static String exerciseBlock(String id, String title, String referenceSql, int version) {
        return """
            ===[EXERCISE]===
            ID: %s
            TITLE: %s
            KNOWLEDGE: 测试
            DIFFICULTY: BEGINNER
            DATASET: %s
            DESCRIPTION:
            测试题目。
            SQL:
            %s
            COMPARE_COLUMNS: true
            COMPARE_ROWS: true
            ROW_ORDER: true
            VERSION: %d
            ENABLED: true
            """.formatted(id, title, ExerciseBankApplier.DATASET_ID, referenceSql, version);
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private final class Fixture {
        ExerciseBankManifest manifest = new ExerciseBankManifest(1, List.of(), List.of());
        final Map<String, ExerciseBankBlock> blocks = new HashMap<>();
        final Path appDb = tempDir.resolve("app.db");

        Fixture() {
            SqlTeacherConfiguration configuration = new SqlTeacherConfiguration(
                "SQLTeacher",
                tempDir,
                new DatabaseConfiguration(appDb, tempDir.resolve("demo.db")),
                new AiConfiguration(URI.create("http://localhost:11434"), Duration.ofSeconds(1), Duration.ofSeconds(2), "test")
            );
            new SqliteAppDatabaseInitializer(configuration).initialize();
        }

        ExerciseBankSyncService service() {
            return new ExerciseBankSyncService(new FakeCloudApi(), appDb.toString());
        }

        int storedVersion(String exerciseId) {
            return readVersion(exerciseId);
        }

        private int readVersion(String exerciseId) {
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + appDb);
                 var statement = connection.prepareStatement("select version from exercises where id = ?")) {
                statement.setString(1, exerciseId);
                try (var rows = statement.executeQuery()) {
                    return rows.next() ? rows.getInt(1) : 0;
                }
            } catch (java.sql.SQLException error) {
                throw new IllegalStateException(error);
            }
        }

        List<String> listExercises() {
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + appDb);
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("select title from exercises")) {
                List<String> titles = new java.util.ArrayList<>();
                while (rows.next()) titles.add(rows.getString(1));
                return titles;
            } catch (java.sql.SQLException error) {
                throw new IllegalStateException(error);
            }
        }

        private final class FakeCloudApi implements CloudApiClient {
            @Override public ExerciseBankManifest fetchExerciseBankManifest(String channel) { return manifest; }
            @Override public ExerciseBankBlock fetchExerciseBankBlock(String channel, String type, String id) {
                return blocks.get(type + ":" + id);
            }
            @Override public int uploadSyncItems(String token, List<CloudSyncItem> items) { return 0; }
            @Override public List<CloudSyncItem> downloadSyncItems(String token, long afterVersion) { return List.of(); }
            @Override public CloudAuthenticationService.Session login(String email, char[] password) { throw unsupported(); }
            @Override public CloudAuthenticationService.Session register(String email, String name, char[] password) { throw unsupported(); }
            @Override public CloudAuthenticationService.Session refresh(String refreshToken) { throw unsupported(); }
            @Override public void logout(String token) { throw unsupported(); }
            @Override public List<ClassroomService.Classroom> listClasses(String token) { throw unsupported(); }
            @Override public ClassroomService.Classroom createClass(String token, String name) { throw unsupported(); }
            @Override public ClassroomService.Classroom addClassMember(String token, String classId, String email, UserRole role) { throw unsupported(); }
            @Override public ClassAssignment createAssignment(String token, String classId, String exerciseId, String title) { throw unsupported(); }
            @Override public ClassAssignment changeAssignmentStatus(String token, String classId, String assignmentId, AssignmentStatus status) { throw unsupported(); }
            @Override public ClassAssignment setAssignmentDueAt(String token, String classId, String assignmentId, Instant dueAt) { throw unsupported(); }
            @Override public ClassAssignment updateAssignment(String token, String classId, String assignmentId, String title, Instant dueAt) { throw unsupported(); }
            @Override public List<ClassAssignment> listAssignments(String token, String classId) { throw unsupported(); }
            @Override public ClassLearningSummary getClassLearningSummary(String token, String classId) { throw unsupported(); }
            @Override public String exportClassLearningCsv(String token, String classId) { throw unsupported(); }

            private static UnsupportedOperationException unsupported() { return new UnsupportedOperationException(); }
        }
    }
}
