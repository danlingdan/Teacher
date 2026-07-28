package com.sqlteacher.infrastructure.cloud;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.AssignmentStatus;
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
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("Cloud API request failed (HTTP " + response.statusCode() + ")");
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
