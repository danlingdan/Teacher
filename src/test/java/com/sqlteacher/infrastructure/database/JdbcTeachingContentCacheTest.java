package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.collaboration.CloudNotification;
import com.sqlteacher.application.collaboration.ContentStatus;
import com.sqlteacher.application.collaboration.CourseCatalog;
import com.sqlteacher.application.collaboration.NotificationType;
import com.sqlteacher.infrastructure.cloud.JdbcTeachingContentCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcTeachingContentCacheTest {
    @TempDir Path directory;

    @Test
    void shouldPersistAccountIsolatedCourseAndNotificationCaches() throws Exception {
        Path database = directory.resolve("app.db");
        new SqliteSchemaMigrator().migrate(database);
        JdbcTeachingContentCache cache = new JdbcTeachingContentCache(database);
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        CourseCatalog course = new CourseCatalog("course-1", "SQL", "", ContentStatus.ACTIVE, 1,
            "teacher-1", now, now);
        CloudNotification notification = new CloudNotification("notice-1", NotificationType.FEEDBACK_PUBLISHED,
            "ASSIGNMENT", "assignment-1", "反馈", "教师已发布反馈", null, now);

        cache.saveCourses("account-a", List.of(course));
        cache.saveNotifications("account-a", List.of(notification));

        assertEquals(List.of(course), cache.loadCourses("account-a"));
        assertEquals(List.of(notification), cache.loadNotifications("account-a"));
        assertTrue(cache.loadCourses("account-b").isEmpty());
        assertTrue(cache.loadNotifications("account-b").isEmpty());
    }
}
