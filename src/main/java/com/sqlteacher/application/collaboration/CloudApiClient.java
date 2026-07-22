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

    ClassAssignment changeAssignmentStatus(String accessToken, String classroomId, String assignmentId, AssignmentStatus status);

    ClassAssignment setAssignmentDueAt(String accessToken, String classroomId, String assignmentId, Instant dueAt);

    ClassAssignment updateAssignment(String accessToken, String classroomId, String assignmentId,
                                     String title, Instant dueAt);

    List<ClassAssignment> listAssignments(String accessToken, String classroomId);

    ClassLearningSummary getClassLearningSummary(String accessToken, String classroomId);

    String exportClassLearningCsv(String accessToken, String classroomId);

    int uploadSyncItems(String accessToken, List<CloudSyncItem> items);

    List<CloudSyncItem> downloadSyncItems(String accessToken, long afterVersion);
}
