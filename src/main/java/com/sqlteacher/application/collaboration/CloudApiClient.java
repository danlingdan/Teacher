package com.sqlteacher.application.collaboration;

import java.util.List;

/** Desktop boundary for the authenticated SQLTeacher cloud API. */
public interface CloudApiClient {
    CloudAuthenticationService.Session login(String email, char[] password);

    CloudAuthenticationService.Session register(String email, String displayName, char[] password);

    void logout(String accessToken);

    List<ClassroomService.Classroom> listClasses(String accessToken);

    ClassroomService.Classroom createClass(String accessToken, String name);

    ClassroomService.Classroom addClassMember(String accessToken, String classroomId, String email, UserRole role);

    ClassAssignment createAssignment(String accessToken, String classroomId, String exerciseId, String title);

    List<ClassAssignment> listAssignments(String accessToken, String classroomId);

    int uploadSyncItems(String accessToken, List<CloudSyncItem> items);

    List<CloudSyncItem> downloadSyncItems(String accessToken, long afterVersion);
}
