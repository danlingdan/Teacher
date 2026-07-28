package com.sqlteacher.application.collaboration;

import java.util.List;

public interface TeachingContentCache {
    void saveCourses(String accountId, List<CourseCatalog> courses);
    List<CourseCatalog> loadCourses(String accountId);
    void saveCourseContent(String accountId, String courseId, CachedCourseContent content);
    CachedCourseContent loadCourseContent(String accountId, String courseId);
    void saveFeedback(String accountId, String assignmentId, List<SubmissionFeedback> feedback);
    List<SubmissionFeedback> loadFeedback(String accountId, String assignmentId);
    void saveMastery(String accountId, String classroomId, String studentId, List<KnowledgeMastery> mastery);
    List<KnowledgeMastery> loadMastery(String accountId, String classroomId, String studentId);
    void saveNotifications(String accountId, List<CloudNotification> notifications);
    List<CloudNotification> loadNotifications(String accountId);
}
