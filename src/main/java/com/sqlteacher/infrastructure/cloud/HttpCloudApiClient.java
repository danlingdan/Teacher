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
