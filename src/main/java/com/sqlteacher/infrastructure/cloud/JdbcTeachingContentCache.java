package com.sqlteacher.infrastructure.cloud;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sqlteacher.application.collaboration.CachedCourseContent;
import com.sqlteacher.application.collaboration.CloudNotification;
import com.sqlteacher.application.collaboration.CourseCatalog;
import com.sqlteacher.application.collaboration.KnowledgeMastery;
import com.sqlteacher.application.collaboration.SubmissionFeedback;
import com.sqlteacher.application.collaboration.TeachingContentCache;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Account-isolated local read cache; it never stores tokens, SQL text or AI keys. */
public final class JdbcTeachingContentCache implements TeachingContentCache {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Path database;

    public JdbcTeachingContentCache(Path database) {
        this.database = Objects.requireNonNull(database).toAbsolutePath().normalize();
    }

    @Override public void saveCourses(String accountId, List<CourseCatalog> courses) {
        put(accountId, "courses", courses);
    }
    @Override public List<CourseCatalog> loadCourses(String accountId) {
        return get(accountId, "courses", new TypeReference<List<CourseCatalog>>() { }, List.of());
    }
    @Override public void saveCourseContent(String accountId, String courseId, CachedCourseContent content) {
        put(accountId, "course:" + key(courseId), content);
    }
    @Override public CachedCourseContent loadCourseContent(String accountId, String courseId) {
        return get(accountId, "course:" + key(courseId), new TypeReference<CachedCourseContent>() { },
            new CachedCourseContent(List.of(), List.of(), List.of()));
    }
    @Override public void saveFeedback(String accountId, String assignmentId, List<SubmissionFeedback> feedback) {
        put(accountId, "feedback:" + key(assignmentId), feedback);
    }
    @Override public List<SubmissionFeedback> loadFeedback(String accountId, String assignmentId) {
        return get(accountId, "feedback:" + key(assignmentId),
            new TypeReference<List<SubmissionFeedback>>() { }, List.of());
    }
    @Override public void saveMastery(String accountId, String classroomId, String studentId,
                                      List<KnowledgeMastery> mastery) {
        put(accountId, "mastery:" + key(classroomId) + ":" + key(studentId == null ? accountId : studentId), mastery);
    }
    @Override public List<KnowledgeMastery> loadMastery(String accountId, String classroomId, String studentId) {
        return get(accountId, "mastery:" + key(classroomId) + ":" + key(studentId == null ? accountId : studentId),
            new TypeReference<List<KnowledgeMastery>>() { }, List.of());
    }
    @Override public void saveNotifications(String accountId, List<CloudNotification> notifications) {
        put(accountId, "notifications", notifications);
    }
    @Override public List<CloudNotification> loadNotifications(String accountId) {
        return get(accountId, "notifications", new TypeReference<List<CloudNotification>>() { }, List.of());
    }

    private void put(String accountId, String cacheKey, Object value) {
        String account = key(accountId);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "insert into teaching_content_cache(account_id,cache_key,payload_json,updated_at) values(?,?,?,?) "
                + "on conflict(account_id,cache_key) do update set payload_json=excluded.payload_json,updated_at=excluded.updated_at")) {
            statement.setString(1, account);
            statement.setString(2, cacheKey);
            statement.setString(3, JSON.writeValueAsString(value));
            statement.setString(4, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException | JsonProcessingException error) {
            throw new IllegalStateException("Teaching content cache could not be saved", error);
        }
    }

    private <T> T get(String accountId, String cacheKey, TypeReference<T> type, T fallback) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select payload_json from teaching_content_cache where account_id=? and cache_key=?")) {
            statement.setString(1, key(accountId));
            statement.setString(2, cacheKey);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? JSON.readValue(row.getString(1), type) : fallback;
            }
        } catch (SQLException | JsonProcessingException error) {
            throw new IllegalStateException("Teaching content cache could not be read", error);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    private static String key(String value) {
        if (value == null || value.isBlank() || value.length() > 160) {
            throw new IllegalArgumentException("Cache identity is invalid");
        }
        return value.trim();
    }
}
