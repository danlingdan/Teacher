package com.sqlteacher.infrastructure.cloud;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.AdminAuditPage;
import com.sqlteacher.application.collaboration.AdminHealthSummary;
import com.sqlteacher.application.collaboration.AdminUserSummary;
import com.sqlteacher.application.collaboration.RetentionCategory;
import com.sqlteacher.application.collaboration.RetentionJob;
import com.sqlteacher.application.collaboration.RetentionPreview;
import com.sqlteacher.application.collaboration.CloudApiRequestException;
import com.sqlteacher.application.collaboration.AssignmentStatus;
import com.sqlteacher.application.collaboration.AssignmentAnalyticsFilter;
import com.sqlteacher.application.collaboration.AssignmentAnalyticsReport;
import com.sqlteacher.application.collaboration.AssignmentSubmission;
import com.sqlteacher.application.collaboration.AssignmentSubmissionRequest;
import com.sqlteacher.application.collaboration.ClassroomService;
import com.sqlteacher.application.collaboration.CloudApiClient;
import com.sqlteacher.application.collaboration.CloudAuthenticationService;
import com.sqlteacher.application.collaboration.CloudSyncItem;
import com.sqlteacher.application.collaboration.ClassAssignment;
import com.sqlteacher.application.collaboration.ClassLearningSummary;
import com.sqlteacher.application.collaboration.UserRole;
import com.sqlteacher.application.collaboration.AssignmentContentSnapshot;
import com.sqlteacher.application.collaboration.CloudNotification;
import com.sqlteacher.application.collaboration.CloudKnowledgeArticle;
import com.sqlteacher.application.collaboration.CloudKnowledgeSearchHit;
import com.sqlteacher.application.collaboration.ContentStatus;
import com.sqlteacher.application.collaboration.CourseBundleImportResult;
import com.sqlteacher.application.collaboration.CourseCatalog;
import com.sqlteacher.application.collaboration.CourseSection;
import com.sqlteacher.application.collaboration.FeedbackDraft;
import com.sqlteacher.application.collaboration.FeedbackStatus;
import com.sqlteacher.application.collaboration.KnowledgeMastery;
import com.sqlteacher.application.collaboration.KnowledgePoint;
import com.sqlteacher.application.collaboration.SharedExerciseVersion;
import com.sqlteacher.application.collaboration.SubmissionFeedback;

import java.io.IOException;
import java.net.URI;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** HTTPS cloud client. HTTP is accepted only for loopback integration tests. */
public final class HttpCloudApiClient implements CloudApiClient {
    private final URI baseUri;
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public HttpCloudApiClient(URI baseUri) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri must not be null");
        if (!"https".equalsIgnoreCase(baseUri.getScheme())) {
            if (!"http".equalsIgnoreCase(baseUri.getScheme()) || !isLoopback(baseUri)) {
                throw new IllegalArgumentException("Cloud API must use HTTPS; HTTP is allowed only for loopback tests");
            }
        }
    }

    private static boolean isLoopback(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException error) {
            return false;
        }
    }

    @Override
    public CloudAuthenticationService.Session login(String email, char[] password) {
        return authenticate("auth/login", Map.of("email", email, "password", new String(password)));
    }

    @Override
    public CloudAuthenticationService.Session register(String email, String displayName, char[] password) {
        return authenticate("auth/register", Map.of("email", email, "displayName", displayName, "password", new String(password)));
    }

    @Override
    public CloudAuthenticationService.Session refresh(String refreshToken) {
        return authenticate("auth/refresh", Map.of("refreshToken", refreshToken));
    }

    @Override public void logout(String accessToken){send("auth/logout","POST",Map.of(),accessToken);}
    @Override public void logout(String accessToken,String refreshToken){send("auth/logout","POST",refreshToken==null?Map.of():Map.of("refreshToken",refreshToken),accessToken);}

    @Override
    public List<ClassroomService.Classroom> listClasses(String accessToken) {
        return request("classes", "GET", null, accessToken, new TypeReference<Map<String, List<ClassroomDto>>>() { }).getOrDefault("classes", List.of())
            .stream().map(ClassroomDto::toDomain).toList();
    }

    @Override
    public ClassroomService.Classroom createClass(String accessToken, String name) {
        return request("classes", "POST", Map.of("name", name), accessToken, ClassroomDto.class).toDomain();
    }

    @Override
    public ClassroomService.Classroom addClassMember(String accessToken,String classroomId,String email,UserRole role){
        return request("classes/"+classroomId+"/members","POST",Map.of("email",email,"role",role.name()),accessToken,ClassroomDto.class).toDomain();
    }

    @Override public ClassAssignment createAssignment(String token,String classroomId,String exerciseId,String title){return createAssignment(token,classroomId,exerciseId,title,null);}
    @Override public ClassAssignment createAssignment(String token,String classroomId,String exerciseId,String title,Instant dueAt){Map<String,String> body=assignmentBody(exerciseId,title,"",dueAt);body.put("status",AssignmentStatus.PUBLISHED.name());return request("classes/"+classroomId+"/assignments","POST",body,token,ClassAssignment.class);}
    @Override public ClassAssignment createAssignmentDraft(String token,String classroomId,String exerciseId,String title,String description,Instant dueAt){Map<String,String> body=assignmentBody(exerciseId,title,description,dueAt);body.put("status",AssignmentStatus.DRAFT.name());return request("classes/"+classroomId+"/assignments","POST",body,token,ClassAssignment.class);}
    @Override public ClassAssignment copyAssignment(String token,String classroomId,String assignmentId,long expectedVersion){return request("classes/"+classroomId+"/assignments/"+assignmentId+"/copy","POST",Map.of("expectedVersion",Long.toString(expectedVersion)),token,ClassAssignment.class);}
    @Override public ClassAssignment changeAssignmentStatus(String token,String classroomId,String assignmentId,AssignmentStatus status){return changeAssignmentStatus(token,classroomId,assignmentId,status,1);}
    @Override public ClassAssignment changeAssignmentStatus(String token,String classroomId,String assignmentId,AssignmentStatus status,long expectedVersion){return request("classes/"+classroomId+"/assignments/"+assignmentId+"/status","POST",Map.of("status",status.name(),"expectedVersion",Long.toString(expectedVersion)),token,ClassAssignment.class);}
    @Override public ClassAssignment setAssignmentDueAt(String token,String classroomId,String assignmentId,Instant dueAt){return setAssignmentDueAt(token,classroomId,assignmentId,dueAt,1);}
    @Override public ClassAssignment setAssignmentDueAt(String token,String classroomId,String assignmentId,Instant dueAt,long expectedVersion){return request("classes/"+classroomId+"/assignments/"+assignmentId+"/due","POST",Map.of("dueAt",dueAt.toString(),"expectedVersion",Long.toString(expectedVersion)),token,ClassAssignment.class);}
    @Override public ClassAssignment updateAssignment(String token,String classroomId,String assignmentId,String title,Instant dueAt){return updateAssignment(token,classroomId,assignmentId,title,"",dueAt,1);}
    @Override public ClassAssignment updateAssignment(String token,String classroomId,String assignmentId,String title,String description,Instant dueAt,long expectedVersion){Map<String,String> body=assignmentBody(null,title,description,dueAt);body.put("expectedVersion",Long.toString(expectedVersion));return request("classes/"+classroomId+"/assignments/"+assignmentId+"/details","POST",body,token,ClassAssignment.class);}
    @Override public List<ClassAssignment> listAssignments(String token,String classroomId){Map<String,List<ClassAssignment>> result=request("classes/"+classroomId+"/assignments","GET",null,token,new TypeReference<Map<String,List<ClassAssignment>>>(){});return result.getOrDefault("assignments",List.of());}
    @Override public AssignmentSubmission submitAssignment(String token,String classroomId,String assignmentId,AssignmentSubmissionRequest submission){return request("classes/"+classroomId+"/assignments/"+assignmentId+"/submissions","POST",submission,token,AssignmentSubmission.class);}
    @Override public List<AssignmentSubmission> listOwnAssignmentSubmissions(String token,String classroomId,String assignmentId){Map<String,List<AssignmentSubmission>> result=request("classes/"+classroomId+"/assignments/"+assignmentId+"/submissions","GET",null,token,new TypeReference<Map<String,List<AssignmentSubmission>>>(){});return result.getOrDefault("submissions",List.of());}
    @Override public AssignmentAnalyticsReport getAssignmentAnalytics(String token,String classroomId,String assignmentId,AssignmentAnalyticsFilter filter){return request("classes/"+classroomId+"/assignments/"+assignmentId+"/analytics"+analyticsQuery(filter),"GET",null,token,AssignmentAnalyticsReport.class);}
    @Override public String exportAssignmentAnalyticsCsv(String token,String classroomId,String assignmentId,AssignmentAnalyticsFilter filter){return send("classes/"+classroomId+"/assignments/"+assignmentId+"/analytics/export"+analyticsQuery(filter),"GET",null,token);}
    @Override
    public AdminHealthSummary getAdminHealth(String token) {
        return request("admin/health", "GET", null, token, AdminHealthSummary.class);
    }

    @Override
    public List<AdminUserSummary> listAdminUsers(String token) {
        Map<String, List<AdminUserSummary>> result = request("admin/users", "GET", null, token,
            new TypeReference<Map<String, List<AdminUserSummary>>>() { });
        return result.getOrDefault("users", List.of());
    }

    @Override
    public AdminUserSummary setUserDisabled(String token, String userId, boolean disabled, String reasonCode) {
        return request("admin/users/" + userId + (disabled ? "/disable" : "/restore"), "POST",
            Map.of("reasonCode", reasonCode), token, AdminUserSummary.class);
    }

    @Override
    public void revokeUserSessions(String token, String userId, String reasonCode) {
        send("admin/users/" + userId + "/revoke-sessions", "POST", Map.of("reasonCode", reasonCode), token);
    }

    @Override
    public AdminAuditPage getAdminAudit(String token, String action, Instant from, Instant to, int page, int pageSize) {
        return request("admin/audit" + adminAuditQuery(action, from, to, page, pageSize),
            "GET", null, token, AdminAuditPage.class);
    }

    @Override
    public RetentionPreview previewRetention(String token, RetentionCategory category, Instant cutoff) {
        return request("admin/retention/preview", "POST", Map.of(
            "category", category.name(), "cutoff", cutoff.toString()), token, RetentionPreview.class);
    }

    @Override
    public RetentionJob executeRetention(String token, String previewId, String confirmationToken,
                                         String backupReference) {
        return request("admin/retention/execute", "POST", Map.of(
            "previewId", previewId, "confirmationToken", confirmationToken,
            "backupReference", backupReference), token, RetentionJob.class);
    }

    @Override
    public RetentionJob restoreRetention(String token, String jobId) {
        if (jobId == null || !jobId.matches("[a-fA-F0-9-]{36}")) {
            throw new IllegalArgumentException("jobId must be a UUID");
        }
        return request("admin/retention/" + jobId + "/restore", "POST", Map.of(),
            token, RetentionJob.class);
    }
    @Override public ClassLearningSummary getClassLearningSummary(String token,String classroomId){return request("classes/"+classroomId+"/analytics","GET",null,token,ClassLearningSummary.class);}
    @Override public String exportClassLearningCsv(String token,String classroomId){return send("classes/"+classroomId+"/analytics/export","GET",null,token);}

    private static Map<String, String> assignmentBody(String exerciseId, String title, String description,
                                                      Instant dueAt) {
        Map<String, String> body = new java.util.LinkedHashMap<>();
        if (exerciseId != null) body.put("exerciseId", exerciseId);
        body.put("title", title);
        body.put("description", description == null ? "" : description);
        if (dueAt != null) body.put("dueAt", dueAt.toString());
        return body;
    }

    private static String analyticsQuery(AssignmentAnalyticsFilter filter) {
        AssignmentAnalyticsFilter value = filter == null ? AssignmentAnalyticsFilter.firstPage() : filter;
        Map<String, String> parameters = new java.util.LinkedHashMap<>();
        if (value.status() != null) parameters.put("status", value.status().name());
        if (value.from() != null) parameters.put("from", value.from().toString());
        if (value.to() != null) parameters.put("to", value.to().toString());
        parameters.put("page", Integer.toString(value.page()));
        parameters.put("pageSize", Integer.toString(value.pageSize()));
        return "?" + parameters.entrySet().stream()
            .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
            .collect(java.util.stream.Collectors.joining("&"));
    }

    private static String adminAuditQuery(String action, Instant from, Instant to, int page, int pageSize) {
        Map<String, String> parameters = new java.util.LinkedHashMap<>();
        if (action != null && !action.isBlank()) parameters.put("action", action);
        if (from != null) parameters.put("from", from.toString());
        if (to != null) parameters.put("to", to.toString());
        parameters.put("page", Integer.toString(page));
        parameters.put("pageSize", Integer.toString(pageSize));
        return "?" + parameters.entrySet().stream()
            .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
            .collect(java.util.stream.Collectors.joining("&"));
    }

    @Override
    public int uploadSyncItems(String accessToken, List<CloudSyncItem> items) {
        Map<String, Integer> result = request("sync/events", "POST", Map.of("items", items), accessToken,
            new TypeReference<Map<String, Integer>>() { });
        return result.getOrDefault("accepted", 0);
    }

    @Override
    public List<CloudSyncItem> downloadSyncItems(String accessToken, long afterVersion) {
        Map<String, List<CloudSyncItem>> result = request("sync/events?afterVersion=" + afterVersion, "GET", null,
            accessToken, new TypeReference<Map<String, List<CloudSyncItem>>>() { });
        return result.getOrDefault("items", List.of());
    }

    @Override
    public CourseCatalog createCourse(String token, String name, String description) {
        return request("v14/courses", "POST", Map.of("name", name, "description", description == null ? "" : description),
            token, CourseCatalog.class);
    }

    @Override
    public List<CourseCatalog> listCourses(String token) {
        Map<String, List<CourseCatalog>> result = request("v14/courses", "GET", null, token,
            new TypeReference<>() { });
        return result.getOrDefault("courses", List.of());
    }

    @Override
    public CourseCatalog updateCourse(String token, String courseId, String name, String description,
                                      ContentStatus status, long expectedVersion) {
        return request("v14/courses/" + courseId + "/details", "POST", Map.of(
            "name", name, "description", description == null ? "" : description,
            "status", status.name(), "expectedVersion", expectedVersion), token, CourseCatalog.class);
    }

    @Override
    public CourseSection createCourseSection(String token, String courseId, String name, int sortOrder) {
        return request("v14/courses/" + courseId + "/sections", "POST",
            Map.of("name", name, "sortOrder", sortOrder), token, CourseSection.class);
    }

    @Override
    public KnowledgePoint createKnowledgePoint(String token, String courseId, String sectionId, String name,
                                               String description, int sortOrder) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        if (sectionId != null && !sectionId.isBlank()) body.put("sectionId", sectionId);
        body.put("name", name);
        body.put("description", description == null ? "" : description);
        body.put("sortOrder", sortOrder);
        return request("v14/courses/" + courseId + "/knowledge-points", "POST", body, token,
            KnowledgePoint.class);
    }

    @Override
    public List<CourseSection> listCourseSections(String token, String courseId) {
        Map<String, List<CourseSection>> result = request("v14/courses/" + courseId + "/sections", "GET", null,
            token, new TypeReference<>() { });
        return result.getOrDefault("sections", List.of());
    }

    @Override
    public CourseSection updateCourseSection(String token, String courseId, String sectionId, String name,
                                             int sortOrder, ContentStatus status, long expectedVersion) {
        return request("v14/courses/" + courseId + "/sections/" + sectionId, "POST", Map.of(
            "name", name, "sortOrder", sortOrder, "status", status.name(), "expectedVersion", expectedVersion),
            token, CourseSection.class);
    }

    @Override
    public List<KnowledgePoint> listKnowledgePoints(String token, String courseId) {
        Map<String, List<KnowledgePoint>> result = request("v14/courses/" + courseId + "/knowledge-points", "GET",
            null, token, new TypeReference<>() { });
        return result.getOrDefault("knowledgePoints", List.of());
    }

    @Override
    public KnowledgePoint updateKnowledgePoint(String token, String courseId, String knowledgePointId,
                                               String sectionId, String name, String description, int sortOrder,
                                               ContentStatus status, long expectedVersion) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        if (sectionId != null && !sectionId.isBlank()) body.put("sectionId", sectionId);
        body.put("name", name);
        body.put("description", description == null ? "" : description);
        body.put("sortOrder", sortOrder);
        body.put("status", status.name());
        body.put("expectedVersion", expectedVersion);
        return request("v14/courses/" + courseId + "/knowledge-points/" + knowledgePointId, "POST", body,
            token, KnowledgePoint.class);
    }

    @Override
    public SharedExerciseVersion publishSharedExercise(String token, String courseId, String exerciseId,
                                                       String title, String prompt, String datasetVersion,
                                                       String evaluationRule, List<String> knowledgePointIds,
                                                       String operationId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        if (exerciseId != null && !exerciseId.isBlank()) body.put("exerciseId", exerciseId);
        body.put("title", title);
        body.put("prompt", prompt);
        body.put("datasetVersion", datasetVersion);
        body.put("evaluationRule", evaluationRule);
        body.put("knowledgePointIds", knowledgePointIds == null ? List.of() : knowledgePointIds);
        body.put("operationId", operationId);
        return request("v14/courses/" + courseId + "/exercises", "POST", body, token,
            SharedExerciseVersion.class);
    }

    @Override
    public List<SharedExerciseVersion> listSharedExercises(String token, String courseId, String knowledgePointId) {
        String path = "v14/courses/" + courseId + "/exercises";
        if (knowledgePointId != null && !knowledgePointId.isBlank()) {
            path += "?knowledgePointId=" + URLEncoder.encode(knowledgePointId, StandardCharsets.UTF_8);
        }
        Map<String, List<SharedExerciseVersion>> result = request(path, "GET", null, token,
            new TypeReference<>() { });
        return result.getOrDefault("exercises", List.of());
    }

    @Override
    public SharedExerciseVersion setSharedExerciseStatus(String token, String courseId, String exerciseId,
                                                         ContentStatus status) {
        return request("v14/courses/" + courseId + "/exercises/" + exerciseId + "/status", "POST",
            Map.of("status", status.name()), token, SharedExerciseVersion.class);
    }

    @Override
    public ClassAssignment createAssignmentFromVersion(String token, String classroomId, String exerciseVersionId,
                                                       String title, String description, Instant dueAt,
                                                       String operationId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("exerciseVersionId", exerciseVersionId);
        body.put("title", title == null ? "" : title);
        body.put("description", description == null ? "" : description);
        if (dueAt != null) body.put("dueAt", dueAt.toString());
        body.put("operationId", operationId);
        return request("v14/classes/" + classroomId + "/assignments/from-version", "POST", body, token,
            ClassAssignment.class);
    }

    @Override
    public AssignmentContentSnapshot getAssignmentContentSnapshot(String token, String classroomId,
                                                                  String assignmentId) {
        return request("v14/classes/" + classroomId + "/assignments/" + assignmentId + "/snapshot", "GET",
            null, token, AssignmentContentSnapshot.class);
    }

    @Override
    public SubmissionFeedback saveSubmissionFeedback(String token, String classroomId, String assignmentId,
                                                      String submissionId, FeedbackStatus status, String comment,
                                                      List<String> knowledgePointIds, long expectedVersion,
                                                      String operationId) {
        return request("v14/classes/" + classroomId + "/assignments/" + assignmentId + "/feedback", "POST",
            Map.of("submissionId", submissionId, "status", status.name(), "comment", comment == null ? "" : comment,
                "knowledgePointIds", knowledgePointIds == null ? List.of() : knowledgePointIds,
                "expectedVersion", expectedVersion, "operationId", operationId), token, SubmissionFeedback.class);
    }

    @Override
    public List<SubmissionFeedback> listSubmissionFeedback(String token, String classroomId, String assignmentId) {
        Map<String, List<SubmissionFeedback>> result = request(
            "v14/classes/" + classroomId + "/assignments/" + assignmentId + "/feedback", "GET", null, token,
            new TypeReference<>() { });
        return result.getOrDefault("feedback", List.of());
    }

    @Override
    public FeedbackDraft draftSubmissionFeedback(String token, String classroomId, String assignmentId,
                                                  String submissionId) {
        return request("v14/classes/" + classroomId + "/assignments/" + assignmentId + "/submissions/"
            + submissionId + "/feedback-draft", "GET", null, token, FeedbackDraft.class);
    }

    @Override
    public List<KnowledgeMastery> getKnowledgeMastery(String token, String classroomId, String studentUserId) {
        String path = "v14/classes/" + classroomId + "/mastery";
        if (studentUserId != null && !studentUserId.isBlank()) {
            path += "?studentUserId=" + URLEncoder.encode(studentUserId, StandardCharsets.UTF_8);
        }
        Map<String, List<KnowledgeMastery>> result = request(path, "GET", null, token,
            new TypeReference<>() { });
        return result.getOrDefault("mastery", List.of());
    }

    @Override
    public List<CloudNotification> listNotifications(String token, int page, int pageSize) {
        Map<String, List<CloudNotification>> result = request("v14/notifications?page=" + page + "&pageSize=" + pageSize,
            "GET", null, token, new TypeReference<>() { });
        return result.getOrDefault("notifications", List.of());
    }

    @Override
    public CloudNotification markNotificationRead(String token, String notificationId) {
        return request("v14/notifications/" + notificationId + "/read", "POST", Map.of(), token,
            CloudNotification.class);
    }

    @Override
    public String exportCourseBundle(String token, String courseId) {
        Map<String, String> result = request("v14/courses/" + courseId + "/export", "GET", null, token,
            new TypeReference<>() { });
        return result.getOrDefault("bundleJson", "");
    }

    @Override
    public CourseBundleImportResult importCourseBundle(String token, String bundleJson, String operationId) {
        return request("v14/courses/import", "POST", Map.of("bundleJson", bundleJson,
            "operationId", operationId), token, CourseBundleImportResult.class);
    }

    @Override
    public CloudKnowledgeArticle publishCloudKnowledge(String token, String courseId, String sectionId,
                                                       String title, String content, String visibility) {
        return request("v14/courses/" + courseId + "/knowledge", "POST", Map.of(
            "sectionId", sectionId == null ? "" : sectionId, "title", title, "content", content,
            "visibility", visibility), token, CloudKnowledgeArticle.class);
    }

    @Override
    public List<CloudKnowledgeArticle> listCloudKnowledge(String token, String courseId) {
        Map<String, List<CloudKnowledgeArticle>> result = request("v14/courses/" + courseId + "/knowledge",
            "GET", null, token, new TypeReference<>() { });
        return result.getOrDefault("articles", List.of());
    }

    @Override
    public List<CloudKnowledgeSearchHit> searchCloudKnowledge(String token, String courseId, String query, int limit) {
        String path = "v14/courses/" + courseId + "/knowledge?q="
            + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&limit=" + limit;
        Map<String, List<CloudKnowledgeSearchHit>> result = request(path, "GET", null, token,
            new TypeReference<>() { });
        return result.getOrDefault("results", List.of());
    }

    private CloudAuthenticationService.Session authenticate(String path, Map<String, String> payload) {
        SessionDto result = request(path, "POST", payload, null, SessionDto.class);
        return result.toDomain();
    }

    private <T> T request(String path, String method, Object payload, String token, Class<T> type) {
        try { return json.readValue(send(path, method, payload, token), type); }
        catch (IOException error) { throw new IllegalStateException("Cloud API response is invalid", error); }
    }
    private <T> T request(String path, String method, Object payload, String token, TypeReference<T> type) {
        try { return json.readValue(send(path, method, payload, token), type); }
        catch (IOException error) { throw new IllegalStateException("Cloud API response is invalid", error); }
    }
    private String send(String path, String method, Object payload, String token) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve("/api/v1/" + path))
                .header("Accept", "application/json");
            if (token != null) builder.header("Authorization", "Bearer " + token);
            if (payload == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload), StandardCharsets.UTF_8));
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String code = "CLOUD_REQUEST_FAILED";
                String message = "Cloud API request failed (HTTP " + response.statusCode() + ")";
                try {
                    var error = json.readTree(response.body());
                    if (error.hasNonNull("code")) code = error.get("code").asText();
                    if (error.hasNonNull("message")) message = error.get("message").asText();
                } catch (IOException ignored) {
                    // Keep the safe generic message when the error response is malformed.
                }
                throw new CloudApiRequestException(response.statusCode(), code, message);
            }
            return response.body();
        } catch (IOException error) { throw new IllegalStateException("Cloud API is unavailable", error); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("Cloud API request was interrupted", error); }
    }

    private record SessionDto(String accessToken, Instant expiresAt, UserDto user, String refreshToken) {
        CloudAuthenticationService.Session toDomain() { return new CloudAuthenticationService.Session(accessToken, expiresAt, user.toDomain(), refreshToken); }
    }
    private record UserDto(String id, String email, String displayName, List<UserRole> roles) {
        AuthenticatedUser toDomain() { return new AuthenticatedUser(id, email, displayName, java.util.Set.copyOf(roles)); }
    }
    private record ClassroomDto(String id, String name, Instant createdAt, List<MemberDto> members) {
        ClassroomService.Classroom toDomain() { return new ClassroomService.Classroom(id, name, createdAt, members.stream().map(MemberDto::toDomain).toList()); }
    }
    private record MemberDto(String userId, UserRole role) {
        ClassroomService.Member toDomain() { return new ClassroomService.Member(userId, role); }
    }
}
