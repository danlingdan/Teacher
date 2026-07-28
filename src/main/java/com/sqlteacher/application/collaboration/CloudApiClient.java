package com.sqlteacher.application.collaboration;

import java.util.List;
import java.time.Instant;

/** Desktop boundary for the authenticated SQLTeacher cloud API. */
public interface CloudApiClient {
    CloudAuthenticationService.Session login(String email, char[] password);

    CloudAuthenticationService.Session register(String email, String displayName, char[] password);

    CloudAuthenticationService.Session refresh(String refreshToken);

    void logout(String accessToken);

    default void logout(String accessToken, String refreshToken) {
        logout(accessToken);
    }

    List<ClassroomService.Classroom> listClasses(String accessToken);

    ClassroomService.Classroom createClass(String accessToken, String name);

    ClassroomService.Classroom addClassMember(String accessToken, String classroomId, String email, UserRole role);

    ClassAssignment createAssignment(String accessToken, String classroomId, String exerciseId, String title);

    default ClassAssignment createAssignment(String accessToken, String classroomId, String exerciseId, String title,
                                              Instant dueAt) {
        if (dueAt != null) throw new UnsupportedOperationException("Assignment due dates are unavailable");
        return createAssignment(accessToken, classroomId, exerciseId, title);
    }

    default ClassAssignment createAssignmentDraft(String accessToken, String classroomId, String exerciseId,
                                                   String title, String description, Instant dueAt) {
        throw new UnsupportedOperationException("Assignment drafts are unavailable");
    }

    default ClassAssignment copyAssignment(String accessToken, String classroomId, String assignmentId,
                                           long expectedVersion) {
        throw new UnsupportedOperationException("Assignment copying is unavailable");
    }

    ClassAssignment changeAssignmentStatus(String accessToken, String classroomId, String assignmentId, AssignmentStatus status);

    default ClassAssignment changeAssignmentStatus(String accessToken, String classroomId, String assignmentId,
                                                    AssignmentStatus status, long expectedVersion) {
        throw new UnsupportedOperationException("Versioned assignment updates are unavailable");
    }

    ClassAssignment setAssignmentDueAt(String accessToken, String classroomId, String assignmentId, Instant dueAt);

    default ClassAssignment setAssignmentDueAt(String accessToken, String classroomId, String assignmentId,
                                               Instant dueAt, long expectedVersion) {
        throw new UnsupportedOperationException("Versioned assignment updates are unavailable");
    }

    ClassAssignment updateAssignment(String accessToken, String classroomId, String assignmentId,
                                     String title, Instant dueAt);

    default ClassAssignment updateAssignment(String accessToken, String classroomId, String assignmentId,
                                             String title, String description, Instant dueAt,
                                             long expectedVersion) {
        throw new UnsupportedOperationException("Versioned assignment updates are unavailable");
    }

    List<ClassAssignment> listAssignments(String accessToken, String classroomId);

    ClassLearningSummary getClassLearningSummary(String accessToken, String classroomId);

    String exportClassLearningCsv(String accessToken, String classroomId);

    int uploadSyncItems(String accessToken, List<CloudSyncItem> items);

    List<CloudSyncItem> downloadSyncItems(String accessToken, long afterVersion);
}
