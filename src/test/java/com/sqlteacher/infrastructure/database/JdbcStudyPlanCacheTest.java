package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.planning.ObjectiveResourceType;
import com.sqlteacher.application.planning.StudyPlanAction;
import com.sqlteacher.application.planning.StudyPlanActionState;
import com.sqlteacher.application.planning.StudyPlanActionType;
import com.sqlteacher.application.planning.StudyPlanReasonCode;
import com.sqlteacher.application.planning.StudyPlanSnapshot;
import com.sqlteacher.infrastructure.cloud.InMemoryLearningEventOwnerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcStudyPlanCacheTest {
    @TempDir Path directory;

    @Test
    void shouldPersistLifecycleDiffOutboxAndOwnerIsolation() {
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        TestContext context = context();
        context.owner().useAuthenticatedUser("student-1");
        JdbcStudyPlanCache cache = new JdbcStudyPlanCache(context.connections(), context.owner(),
            Clock.fixed(now, ZoneOffset.UTC));
        StudyPlanAction action = action(70);
        StudyPlanSnapshot first = new StudyPlanSnapshot("student-1", "course-1", "policy-1", "facts-1", now,
            now.plus(Duration.ofDays(7)), List.of(action));

        assertEquals(1, cache.save(first).changes().size());
        assertEquals("目标一", cache.currentPlans().getFirst().actions().getFirst().title());
        var operation = cache.updateAction("course-1", action.id(), StudyPlanActionState.STARTED);
        assertEquals(1, cache.pendingOperations());

        StudyPlanSnapshot changed = new StudyPlanSnapshot("student-1", "course-1", "policy-1", "facts-2",
            now.plusSeconds(60), now.plus(Duration.ofDays(7)), List.of(action(90)));
        var refresh = cache.save(changed);
        assertTrue(refresh.changes().stream().anyMatch(item -> item.explanation().contains("优先级")));
        assertEquals(StudyPlanActionState.STARTED, refresh.snapshot().actions().getFirst().state());
        assertEquals(operation.operationId(), cache.pending().getFirst().operationId());
        cache.markDelivered(operation.operationId(), action.id(), 1);
        assertEquals(0, cache.pendingOperations());
        assertEquals(StudyPlanActionState.OPEN, cache.save(changed).snapshot().actions().getFirst().state());

        context.owner().useAuthenticatedUser("student-2");
        assertTrue(cache.currentPlans().isEmpty());
        assertThrows(SecurityException.class, () -> cache.save(first));
    }

    private TestContext context() {
        Path app = directory.resolve("app.db");
        SqlTeacherConfiguration configuration = new SqlTeacherConfiguration("SQLTeacher", directory,
            new DatabaseConfiguration(app, directory.resolve("demo.db")), new AiConfiguration(
            URI.create("http://localhost:11434"), Duration.ofSeconds(1), Duration.ofSeconds(1), "test"));
        new SqliteAppDatabaseInitializer(configuration).initialize();
        return new TestContext(new JdbcConnectionFactory(configuration.database()), new InMemoryLearningEventOwnerContext());
    }

    private StudyPlanAction action(int priority) {
        return new StudyPlanAction("action-1", "objective-1", StudyPlanActionType.REVIEW_KNOWLEDGE,
            "目标一", "复习目标一", ObjectiveResourceType.KNOWLEDGE_POINT, "point-1",
            StudyPlanReasonCode.INSUFFICIENT_EVIDENCE, priority);
    }

    private record TestContext(JdbcConnectionFactory connections, InMemoryLearningEventOwnerContext owner) { }
}
