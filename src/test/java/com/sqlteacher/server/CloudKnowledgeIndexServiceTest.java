package com.sqlteacher.server;

import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CloudKnowledgeIndexServiceTest {
    @TempDir Path directory;

    @Test
    void shouldIndexOutboxFuseSearchAndRecheckVisibility() throws Exception {
        Path database = directory.resolve("cloud.db");
        initializeUsers(database);
        V14CloudStore store = new V14CloudStore(database);
        AuthenticatedUser teacher = new AuthenticatedUser("teacher-1", "teacher@example.edu", "Teacher",
            Set.of(UserRole.TEACHER));
        AuthenticatedUser student = new AuthenticatedUser("student-1", "student@example.edu", "Student",
            Set.of(UserRole.STUDENT));
        var course = store.createCourse(teacher, "SQL", "Vector retrieval");
        store.publishKnowledge(teacher, course.id(), "", "Published", "WHERE filters rows", "PUBLISHED");
        store.publishKnowledge(teacher, course.id(), "", "Private", "teacher secret", "PRIVATE");
        FakeVectorClient vectors = new FakeVectorClient();
        CloudKnowledgeIndexService service = new CloudKnowledgeIndexService(store,
            new FakeEmbeddingClient(), vectors, 3);

        assertEquals(2, store.pendingKnowledgeIndexCount());
        assertEquals(2, service.processPendingNow());
        assertEquals(0, store.pendingKnowledgeIndexCount());
        assertEquals(2, vectors.points.size());
        assertFalse(vectors.points.getFirst().payload().containsKey("content"));

        assertEquals(2, service.search(teacher, course.id(), "semantic-only", 10).size());
        var studentHits = service.search(student, course.id(), "semantic-only", 10);
        assertEquals(1, studentHits.size());
        assertEquals("Published", studentHits.getFirst().title());
        assertEquals(List.of("PUBLISHED"), vectors.lastVisibility);
        assertEquals(6, scalar(database, "select max(version) from cloud_schema_version"));
        assertEquals(1, scalar(database, "select count(*) from cloud_knowledge_embedding_profile"));
        assertEquals(2, service.rebuild());
        assertEquals(2, store.pendingKnowledgeIndexCount());
        assertEquals(2, service.processPendingNow());
    }

    @Test
    void shouldPersistRetryStateAndKeepKeywordFallback() throws Exception {
        Path database = directory.resolve("failed.db");
        initializeUsers(database);
        V14CloudStore store = new V14CloudStore(database);
        AuthenticatedUser teacher = new AuthenticatedUser("teacher-1", "teacher@example.edu", "Teacher",
            Set.of(UserRole.TEACHER));
        var course = store.createCourse(teacher, "SQL", "Fallback");
        store.publishKnowledge(teacher, course.id(), "", "WHERE", "WHERE filters rows", "PUBLISHED");
        CloudKnowledgeIndexService service = new CloudKnowledgeIndexService(store, new FailingEmbeddingClient(),
            new FakeVectorClient(), 3);

        assertThrows(RuntimeException.class, service::processPendingNow);
        assertEquals(1, store.pendingKnowledgeIndexCount());
        assertEquals(1, scalar(database,
            "select count(*) from cloud_knowledge_index_outbox where status='FAILED' and attempts=1 and last_error='IllegalStateException'"));
        assertEquals(1, service.search(teacher, course.id(), "WHERE", 10).size());
    }

    private static void initializeUsers(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.executeUpdate("pragma foreign_keys=on");
            statement.executeUpdate("create table users(id text primary key,email text not null unique,display_name text not null,"
                + "password_hash blob not null,password_salt blob not null,disabled integer not null default 0,created_at text not null)");
            statement.executeUpdate("create table user_roles(user_id text not null references users(id),role text not null,primary key(user_id,role))");
            for (String id : List.of("teacher-1", "student-1")) {
                try (var insert = connection.prepareStatement(
                    "insert into users(id,email,display_name,password_hash,password_salt,created_at) values(?,?,?,?,?,?)")) {
                    insert.setString(1, id); insert.setString(2, id + "@example.edu"); insert.setString(3, id);
                    insert.setBytes(4, new byte[]{1}); insert.setBytes(5, new byte[]{1});
                    insert.setString(6, Instant.now().toString()); insert.executeUpdate();
                }
            }
        }
    }

    private static int scalar(Path database, String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement(); var row = statement.executeQuery(sql)) {
            return row.getInt(1);
        }
    }

    private static final class FakeEmbeddingClient implements CloudKnowledgeEmbeddingClient {
        @Override
        public EmbeddingBatch embed(List<String> texts, Purpose purpose) {
            return new EmbeddingBatch("test", "embedding-test", texts.stream()
                .map(ignored -> new float[]{1, 0, 0}).toList());
        }

        @Override public boolean ready() { return true; }
    }

    private static final class FailingEmbeddingClient implements CloudKnowledgeEmbeddingClient {
        @Override public EmbeddingBatch embed(List<String> texts, Purpose purpose) { throw new IllegalStateException("secret content"); }
        @Override public boolean ready() { return false; }
    }

    private static final class FakeVectorClient implements CloudKnowledgeVectorClient {
        private final List<QdrantVectorClient.Point> points = new ArrayList<>();
        private List<String> lastVisibility = List.of();

        @Override public void validateCollection(int expectedDimension) { assertEquals(3, expectedDimension); }

        @Override public void upsert(List<QdrantVectorClient.Point> values) { points.addAll(values); }

        @Override
        public List<QdrantVectorClient.SearchHit> search(float[] vector, String courseId,
                                                          List<String> visibility, int limit) {
            lastVisibility = List.copyOf(visibility);
            return points.stream().map(point -> new QdrantVectorClient.SearchHit(point.id(), 0.9,
                Map.copyOf(point.payload()))).toList();
        }

        @Override public boolean ready() { return true; }
    }
}
