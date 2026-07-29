package com.sqlteacher.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.AdminAuditEntry;
import com.sqlteacher.application.collaboration.AdminAuditPage;
import com.sqlteacher.application.collaboration.AdminHealthSummary;
import com.sqlteacher.application.collaboration.AdminOperationRejectedException;
import com.sqlteacher.application.collaboration.AdminUserSummary;
import com.sqlteacher.application.collaboration.AssignmentStatus;
import com.sqlteacher.application.collaboration.AssignmentAnalyticsFilter;
import com.sqlteacher.application.collaboration.AssignmentAnalyticsReport;
import com.sqlteacher.application.collaboration.AssignmentAnalyticsRow;
import com.sqlteacher.application.collaboration.AssignmentErrorCount;
import com.sqlteacher.application.collaboration.AssignmentStudentStatus;
import com.sqlteacher.application.collaboration.AssignmentSubmission;
import com.sqlteacher.application.collaboration.AssignmentSubmissionRequest;
import com.sqlteacher.application.collaboration.AssignmentSubmissionRejectedException;
import com.sqlteacher.application.collaboration.AssignmentSubmissionStatus;
import com.sqlteacher.application.collaboration.AssignmentVersionConflictException;
import com.sqlteacher.application.collaboration.SubmissionOperationConflictException;
import com.sqlteacher.application.collaboration.ClassroomService;
import com.sqlteacher.application.collaboration.CloudAuthenticationService;
import com.sqlteacher.application.collaboration.CloudSyncItem;
import com.sqlteacher.application.collaboration.ClassAssignment;
import com.sqlteacher.application.collaboration.RetentionCategory;
import com.sqlteacher.application.collaboration.RetentionJob;
import com.sqlteacher.application.collaboration.RetentionPreview;
import com.sqlteacher.application.collaboration.UserRole;
import com.sqlteacher.application.collaboration.ContentStatus;
import com.sqlteacher.application.collaboration.FeedbackStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Small deployable cloud API for v1.2. It intentionally exposes only account and class APIs;
 * desktop database credentials and BYO-AI keys never cross this boundary.
 */
public final class SqlTeacherCloudServer {
    private static final Logger log = LoggerFactory.getLogger(SqlTeacherCloudServer.class);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final int TOKEN_BYTES = 32;
    private static final int SALT_BYTES = 16;
    private static final int PBKDF2_ITERATIONS = 310_000;
    private static final int HASH_BITS = 256;
    private static final long ACCESS_TOKEN_HOURS = 8;
    private static final long REFRESH_TOKEN_DAYS = 30;

    private final CloudStore store;
    private final V14CloudStore v14Store;
    private final HttpServer server;

    SqlTeacherCloudServer(Path databasePath, int port) throws IOException, SQLException {
        this.store = new CloudStore(databasePath);
        this.v14Store = new V14CloudStore(databasePath);
        String bootstrapEmail = System.getenv("SQLTEACHER_CLOUD_BOOTSTRAP_ADMIN_EMAIL");
        String bootstrapPassword = System.getenv("SQLTEACHER_CLOUD_BOOTSTRAP_ADMIN_PASSWORD");
        if (bootstrapEmail != null && !bootstrapEmail.isBlank()
            && bootstrapPassword != null && !bootstrapPassword.isBlank()) {
            store.ensureBootstrapAdmin(bootstrapEmail, bootstrapPassword.toCharArray());
        }
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext("/health", this::health);
        this.server.createContext("/api/v1/auth/register", this::register);
        this.server.createContext("/api/v1/auth/login", this::login);
        this.server.createContext("/api/v1/auth/refresh", this::refresh);
        this.server.createContext("/api/v1/auth/logout", this::logout);
        this.server.createContext("/api/v1/classes", this::classes);
        this.server.createContext("/api/v1/sync/events", this::syncEvents);
        this.server.createContext("/api/v1/admin", this::admin);
        this.server.createContext("/api/v1/v14", this::v14);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("SQLTEACHER_CLOUD_PORT", "8080"));
        Path database = Path.of(System.getenv().getOrDefault("SQLTEACHER_CLOUD_DB", "./data/cloud.db"))
            .toAbsolutePath().normalize();
        SqlTeacherCloudServer cloudServer = new SqlTeacherCloudServer(database, port);
        cloudServer.server.start();
        log.info("SQLTeacher cloud API started, port={}", port);
    }

    void start() { server.start(); }
    void stop() { server.stop(0); }
    int port() { return server.getAddress().getPort(); }

    private void health(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange); return; }
        respond(exchange, 200, Map.of("status", "ok", "time", Instant.now().toString()));
    }

    private void register(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange); return; }
        try {
            Map<String, String> body = request(exchange);
            SessionData session = store.registerData(body.get("email"), body.get("displayName"), password(body));
            respond(exchange, 201, sessionResponse(session));
        } catch (IllegalArgumentException error) { respond(exchange, 400, errorResponse("INVALID_REQUEST", error.getMessage())); }
        catch (SecurityException error) { respond(exchange, 409, errorResponse("ACCOUNT_EXISTS", "This email is already registered.")); }
        catch (RuntimeException error) { logUnexpectedFailure("registration", error); respond(exchange, 500, errorResponse("SERVER_ERROR", "Registration failed.")); }
        finally { clearPassword(exchange); }
    }

    private void login(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange); return; }
        try {
            Map<String, String> body = request(exchange);
            SessionData session = store.loginData(body.get("email"), password(body));
            respond(exchange, 200, sessionResponse(session));
        } catch (IllegalArgumentException error) { respond(exchange, 400, errorResponse("INVALID_REQUEST", error.getMessage())); }
        catch (SecurityException error) { respond(exchange, 401, errorResponse("LOGIN_FAILED", "Email or password is incorrect.")); }
        catch (RuntimeException error) { logUnexpectedFailure("login", error); respond(exchange, 500, errorResponse("SERVER_ERROR", "Login failed.")); }
        finally { clearPassword(exchange); }
    }

    private void logout(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange); return; }
        try {
            Map<String, String> body = optionalRequest(exchange);
            store.logout(token(exchange), body.get("refreshToken"));
            exchange.sendResponseHeaders(204, -1);
        } catch (SecurityException error) { respond(exchange, 401, errorResponse("UNAUTHORIZED", "Login is required.")); }
    }

    private void refresh(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange); return; }
        try {
            Map<String, String> body = request(exchange);
            respond(exchange, 200, sessionResponse(store.refreshData(body.get("refreshToken"))));
        } catch (IllegalArgumentException error) { respond(exchange, 400, errorResponse("INVALID_REQUEST", error.getMessage())); }
        catch (SecurityException error) { respond(exchange, 401, errorResponse("REFRESH_FAILED", "Refresh token is invalid or expired.")); }
        catch (RuntimeException error) { logUnexpectedFailure("session refresh", error); respond(exchange, 500, errorResponse("SERVER_ERROR", "Session refresh failed.")); }
    }

    private void classes(HttpExchange exchange) throws IOException {
        try {
            AuthenticatedUser actor = store.authenticate(token(exchange));
            String path = exchange.getRequestURI().getPath();
            if ("/api/v1/classes".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, Map.of("classes", store.listVisibleTo(actor)));
                return;
            }
            if ("/api/v1/classes".equals(path) && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                respond(exchange, 201, store.create(actor, body.get("name")));
                return;
            }
            String[] segments = path.split("/");
            if (segments.length == 6 && "members".equals(segments[5]) && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                UserRole role = UserRole.valueOf(body.get("role").toUpperCase(Locale.ROOT));
                String userId = body.get("userId");
                if ((userId == null || userId.isBlank()) && body.get("email") != null) userId = store.userIdByEmail(body.get("email"));
                respond(exchange, 200, store.addMember(actor, segments[4], userId, role));
                return;
            }
            if (segments.length == 6 && "assignments".equals(segments[5])) {
                if ("GET".equals(exchange.getRequestMethod())) {
                    String requestedStatus = queryValue(exchange.getRequestURI().getRawQuery(), "status");
                    AssignmentStatus statusFilter = requestedStatus == null ? null : assignmentStatus(requestedStatus, null);
                    respond(exchange,200,Map.of("assignments",store.listAssignments(actor,segments[4],statusFilter)));return;
                }
                if ("POST".equals(exchange.getRequestMethod())) {
                    Map<String,String> body=request(exchange);
                    AssignmentStatus status = assignmentStatus(body.get("status"), AssignmentStatus.PUBLISHED);
                    respond(exchange,201,store.createAssignment(actor,segments[4],body.get("exerciseId"),body.get("title"),
                        body.get("description"),instantOrNull(body.get("dueAt")),status));return;
                }
            }
            if (segments.length == 6 && "analytics".equals(segments[5]) && "GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, store.classLearningSummary(actor, segments[4]));
                return;
            }
            if (segments.length == 7 && "analytics".equals(segments[5]) && "export".equals(segments[6])
                && "GET".equals(exchange.getRequestMethod())) {
                respondCsv(exchange, store.exportClassLearningCsv(actor, segments[4]));
                return;
            }
            if (segments.length == 8 && "assignments".equals(segments[5]) && "copy".equals(segments[7])
                && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                respond(exchange, 201, store.copyAssignment(actor, segments[4], segments[6],
                    positiveLong(body, "expectedVersion")));
                return;
            }
            if (segments.length == 8 && "assignments".equals(segments[5]) && "submissions".equals(segments[7])) {
                if ("POST".equals(exchange.getRequestMethod())) {
                    Map<String, String> body = request(exchange);
                    AssignmentSubmissionRequest submission = new AssignmentSubmissionRequest(
                        body.get("operationId"), requiredBoolean(body, "passed"), body.get("resultHash"),
                        body.get("errorCode"), instantOrNull(body.get("clientCompletedAt"))
                    );
                    AssignmentSubmission created = store.submitAssignment(actor, segments[4], segments[6], submission);
                    v14Store.recordSubmissionNotification(actor, segments[4], created);
                    respond(exchange, 201, created);
                    return;
                }
                if ("GET".equals(exchange.getRequestMethod())) {
                    respond(exchange, 200, Map.of("submissions",
                        store.listOwnSubmissions(actor, segments[4], segments[6])));
                    return;
                }
            }
            if (segments.length == 8 && "assignments".equals(segments[5]) && "analytics".equals(segments[7])
                && "GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, store.assignmentAnalytics(actor, segments[4], segments[6],
                    analyticsFilter(exchange)));
                return;
            }
            if (segments.length == 9 && "assignments".equals(segments[5]) && "analytics".equals(segments[7])
                && "export".equals(segments[8]) && "GET".equals(exchange.getRequestMethod())) {
                respondCsv(exchange, store.exportAssignmentAnalyticsCsv(actor, segments[4], segments[6],
                    analyticsFilter(exchange)), "assignment-analytics.csv");
                return;
            }
            if (segments.length == 8 && "assignments".equals(segments[5]) && "status".equals(segments[7])
                && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                respond(exchange, 200, store.changeAssignmentStatus(actor, segments[4], segments[6],
                    assignmentStatus(body.get("status"), null), positiveLong(body, "expectedVersion")));
                return;
            }
            if (segments.length == 8 && "assignments".equals(segments[5]) && "due".equals(segments[7])
                && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                respond(exchange, 200, store.setAssignmentDueAt(actor, segments[4], segments[6],
                    instantOrNull(body.get("dueAt")), positiveLong(body, "expectedVersion")));
                return;
            }
            if (segments.length == 8 && "assignments".equals(segments[5]) && "details".equals(segments[7])
                && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                respond(exchange, 200, store.updateAssignment(actor, segments[4], segments[6], body.get("title"),
                    body.get("description"), instantOrNull(body.get("dueAt")), positiveLong(body, "expectedVersion")));
                return;
            }
            respond(exchange, 404, errorResponse("NOT_FOUND", "API endpoint was not found."));
        } catch (AssignmentVersionConflictException error) {
            respond(exchange, 409, Map.of("code", "ASSIGNMENT_VERSION_CONFLICT", "message", error.getMessage(),
                "latest", error.latest()));
        } catch (SubmissionOperationConflictException error) {
            respond(exchange, 409, errorResponse("SUBMISSION_OPERATION_CONFLICT", error.getMessage()));
        } catch (AssignmentSubmissionRejectedException error) {
            respond(exchange, 409, errorResponse(error.code(), error.getMessage()));
        } catch (SecurityException error) { respond(exchange, 403, errorResponse("FORBIDDEN", "You do not have access to this resource.")); }
        catch (IllegalArgumentException error) { respond(exchange, 400, errorResponse("INVALID_REQUEST", error.getMessage())); }
        catch (RuntimeException error) { logUnexpectedFailure("classroom operation", error); respond(exchange, 500, errorResponse("SERVER_ERROR", "Classroom operation failed.")); }
    }

    private void syncEvents(HttpExchange exchange) throws IOException {
        try {
            AuthenticatedUser actor = store.authenticate(token(exchange));
            if ("POST".equals(exchange.getRequestMethod())) {
                SyncUpload upload = JSON.readValue(exchange.getRequestBody(), SyncUpload.class);
                respond(exchange, 200, Map.of("accepted", store.upload(actor, upload.items())));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                long after = queryLong(exchange.getRequestURI().getRawQuery(), "afterVersion", 0);
                respond(exchange, 200, Map.of("items", store.download(actor, after)));
                return;
            }
            methodNotAllowed(exchange);
        } catch (SecurityException error) {
            respond(exchange, 401, errorResponse("UNAUTHORIZED", "Login is required."));
        } catch (IllegalArgumentException error) {
            respond(exchange, 400, errorResponse("INVALID_REQUEST", error.getMessage()));
        } catch (RuntimeException error) {
            logUnexpectedFailure("learning-event synchronization", error);
            respond(exchange, 500, errorResponse("SERVER_ERROR", "Synchronization failed."));
        }
    }

    private void v14(HttpExchange exchange) throws IOException {
        try {
            AuthenticatedUser actor = store.authenticate(token(exchange));
            String[] segments = exchange.getRequestURI().getPath().split("/");
            String method = exchange.getRequestMethod();
            if (segments.length == 5 && "courses".equals(segments[4])) {
                if ("GET".equals(method)) {
                    respond(exchange, 200, Map.of("courses", v14Store.listCourses(actor)));
                    return;
                }
                if ("POST".equals(method)) {
                    Map<String, Object> body = objectRequest(exchange);
                    respond(exchange, 201, v14Store.createCourse(actor, string(body, "name"), string(body, "description")));
                    return;
                }
            }
            if (segments.length == 6 && "courses".equals(segments[4]) && "import".equals(segments[5])
                && "POST".equals(method)) {
                Map<String, Object> body = objectRequest(exchange);
                respond(exchange, 201, v14Store.importCourse(actor, string(body, "bundleJson"),
                    string(body, "operationId")));
                return;
            }
            if (segments.length == 7 && "courses".equals(segments[4])) {
                String courseId = segments[5];
                String action = segments[6];
                if ("sections".equals(action)) {
                    if ("GET".equals(method)) {
                        respond(exchange, 200, Map.of("sections", v14Store.listSections(actor, courseId)));
                        return;
                    }
                    if ("POST".equals(method)) {
                        Map<String, Object> body = objectRequest(exchange);
                        respond(exchange, 201, v14Store.createSection(actor, courseId, string(body, "name"),
                            integer(body, "sortOrder", 0)));
                        return;
                    }
                }
                if ("knowledge-points".equals(action)) {
                    if ("GET".equals(method)) {
                        respond(exchange, 200, Map.of("knowledgePoints", v14Store.listKnowledgePoints(actor, courseId)));
                        return;
                    }
                    if ("POST".equals(method)) {
                        Map<String, Object> body = objectRequest(exchange);
                        respond(exchange, 201, v14Store.createKnowledgePoint(actor, courseId,
                            string(body, "sectionId"), string(body, "name"), string(body, "description"),
                            integer(body, "sortOrder", 0)));
                        return;
                    }
                }
                if ("exercises".equals(action)) {
                    if ("GET".equals(method)) {
                        respond(exchange, 200, Map.of("exercises", v14Store.listExercises(actor, courseId,
                            queryValue(exchange.getRequestURI().getRawQuery(), "knowledgePointId"))));
                        return;
                    }
                    if ("POST".equals(method)) {
                        Map<String, Object> body = objectRequest(exchange);
                        respond(exchange, 201, v14Store.publishExercise(actor, courseId, string(body, "exerciseId"),
                            string(body, "title"), string(body, "prompt"), string(body, "datasetVersion"),
                            string(body, "evaluationRule"), strings(body, "knowledgePointIds"),
                            string(body, "operationId")));
                        return;
                    }
                }
                if ("export".equals(action) && "GET".equals(method)) {
                    respond(exchange, 200, Map.of("bundleJson", v14Store.exportCourse(actor, courseId)));
                    return;
                }
            }
            if (segments.length == 7 && "courses".equals(segments[4]) && "POST".equals(method)) {
                Map<String, Object> body = objectRequest(exchange);
                respond(exchange, 200, v14Store.updateCourse(actor, segments[5], string(body, "name"),
                    string(body, "description"), ContentStatus.valueOf(string(body, "status").toUpperCase(Locale.ROOT)),
                    longValue(body, "expectedVersion", 0)));
                return;
            }
            if (segments.length == 8 && "courses".equals(segments[4]) && "POST".equals(method)) {
                Map<String, Object> body = objectRequest(exchange);
                if ("sections".equals(segments[6])) {
                    respond(exchange, 200, v14Store.updateSection(actor, segments[5], segments[7],
                        string(body, "name"), integer(body, "sortOrder", 0),
                        ContentStatus.valueOf(string(body, "status").toUpperCase(Locale.ROOT)),
                        longValue(body, "expectedVersion", 0)));
                    return;
                }
                if ("knowledge-points".equals(segments[6])) {
                    respond(exchange, 200, v14Store.updateKnowledgePoint(actor, segments[5], segments[7],
                        string(body, "sectionId"), string(body, "name"), string(body, "description"),
                        integer(body, "sortOrder", 0),
                        ContentStatus.valueOf(string(body, "status").toUpperCase(Locale.ROOT)),
                        longValue(body, "expectedVersion", 0)));
                    return;
                }
            }
            if (segments.length == 9 && "courses".equals(segments[4]) && "exercises".equals(segments[6])
                && "status".equals(segments[8]) && "POST".equals(method)) {
                Map<String, Object> body = objectRequest(exchange);
                respond(exchange, 200, v14Store.setExerciseStatus(actor, segments[5], segments[7],
                    ContentStatus.valueOf(string(body, "status").toUpperCase(Locale.ROOT))));
                return;
            }
            if (segments.length == 8 && "classes".equals(segments[4]) && "assignments".equals(segments[6])
                && "from-version".equals(segments[7]) && "POST".equals(method)) {
                Map<String, Object> body = objectRequest(exchange);
                respond(exchange, 201, v14Store.createAssignmentFromVersion(actor, segments[5],
                    string(body, "exerciseVersionId"), string(body, "title"), string(body, "description"),
                    instantOrNull(string(body, "dueAt")), string(body, "operationId")));
                return;
            }
            if (segments.length == 9 && "classes".equals(segments[4]) && "assignments".equals(segments[6])) {
                String classroomId = segments[5];
                String assignmentId = segments[7];
                if ("snapshot".equals(segments[8]) && "GET".equals(method)) {
                    respond(exchange, 200, v14Store.assignmentSnapshot(actor, classroomId, assignmentId));
                    return;
                }
                if ("feedback".equals(segments[8])) {
                    if ("GET".equals(method)) {
                        respond(exchange, 200, Map.of("feedback", v14Store.listFeedback(actor, classroomId, assignmentId)));
                        return;
                    }
                    if ("POST".equals(method)) {
                        Map<String, Object> body = objectRequest(exchange);
                        respond(exchange, 200, v14Store.saveFeedback(actor, classroomId, assignmentId,
                            string(body, "submissionId"), FeedbackStatus.valueOf(string(body, "status").toUpperCase(Locale.ROOT)),
                            string(body, "comment"), strings(body, "knowledgePointIds"),
                            longValue(body, "expectedVersion", 0), string(body, "operationId")));
                        return;
                    }
                }
            }
            if (segments.length == 11 && "classes".equals(segments[4]) && "assignments".equals(segments[6])
                && "submissions".equals(segments[8]) && "feedback-draft".equals(segments[10])
                && "GET".equals(method)) {
                respond(exchange, 200, v14Store.draftFeedback(actor, segments[5], segments[7], segments[9]));
                return;
            }
            if (segments.length == 7 && "classes".equals(segments[4]) && "mastery".equals(segments[6])
                && "GET".equals(method)) {
                respond(exchange, 200, Map.of("mastery", v14Store.mastery(actor, segments[5],
                    queryValue(exchange.getRequestURI().getRawQuery(), "studentUserId"))));
                return;
            }
            if (segments.length == 5 && "notifications".equals(segments[4]) && "GET".equals(method)) {
                String query = exchange.getRequestURI().getRawQuery();
                respond(exchange, 200, Map.of("notifications", v14Store.notifications(actor,
                    (int) queryLong(query, "page", 0), (int) queryLong(query, "pageSize", 50))));
                return;
            }
            if (segments.length == 7 && "notifications".equals(segments[4]) && "read".equals(segments[6])
                && "POST".equals(method)) {
                respond(exchange, 200, v14Store.markRead(actor, segments[5]));
                return;
            }
            respond(exchange, 404, errorResponse("NOT_FOUND", "API endpoint was not found."));
        } catch (V14VersionConflictException error) {
            respond(exchange, 409, errorResponse("CONTENT_VERSION_CONFLICT", error.getMessage()));
        } catch (SecurityException error) {
            respond(exchange, 403, errorResponse("FORBIDDEN", "You do not have access to this resource."));
        } catch (IllegalArgumentException error) {
            respond(exchange, 400, errorResponse("INVALID_REQUEST", error.getMessage()));
        } catch (RuntimeException error) {
            logUnexpectedFailure("v1.4 operation", error);
            respond(exchange, 500, errorResponse("SERVER_ERROR", "v1.4 operation failed."));
        }
    }

    private void admin(HttpExchange exchange) throws IOException {
        try {
            AuthenticatedUser actor = store.authenticate(token(exchange));
            String[] segments = exchange.getRequestURI().getPath().split("/");
            if (segments.length == 5 && "health".equals(segments[4])
                && "GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, store.adminHealth(actor));
                return;
            }
            if (segments.length == 5 && "users".equals(segments[4])
                && "GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, Map.of("users", store.adminUsers(actor)));
                return;
            }
            if (segments.length == 7 && "users".equals(segments[4])
                && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                String action = segments[6];
                if ("disable".equals(action) || "restore".equals(action)) {
                    respond(exchange, 200, store.setUserDisabled(actor, segments[5], "disable".equals(action),
                        body.get("reasonCode")));
                    return;
                }
                if ("revoke-sessions".equals(action)) {
                    store.revokeUserSessions(actor, segments[5], body.get("reasonCode"));
                    respond(exchange, 200, Map.of("status", "ok"));
                    return;
                }
            }
            if (segments.length == 5 && "audit".equals(segments[4])
                && "GET".equals(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getRawQuery();
                long page = queryLong(query, "page", 0);
                long pageSize = queryLong(query, "pageSize", 50);
                if (page > Integer.MAX_VALUE || pageSize > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Pagination value is too large");
                }
                respond(exchange, 200, store.adminAudit(actor, queryValue(query, "action"),
                    instantOrNull(queryValue(query, "from")), instantOrNull(queryValue(query, "to")),
                    (int) page, (int) pageSize));
                return;
            }
            if (segments.length == 6 && "retention".equals(segments[4])
                && "preview".equals(segments[5]) && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                respond(exchange, 200, store.previewRetention(actor,
                    RetentionCategory.valueOf(body.get("category")), Instant.parse(body.get("cutoff"))));
                return;
            }
            if (segments.length == 6 && "retention".equals(segments[4])
                && "execute".equals(segments[5]) && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                respond(exchange, 200, store.executeRetention(actor, body.get("previewId"),
                    body.get("confirmationToken"), body.get("backupReference")));
                return;
            }
            if (segments.length == 7 && "retention".equals(segments[4])
                && "restore".equals(segments[6]) && "POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, store.restoreRetention(actor, segments[5]));
                return;
            }
            respond(exchange, 404, errorResponse("NOT_FOUND", "API endpoint was not found."));
        } catch (AdminOperationRejectedException error) {
            respond(exchange, 409, errorResponse(error.code(), error.getMessage()));
        } catch (SecurityException error) {
            respond(exchange, 403, errorResponse("FORBIDDEN", "Administrator role is required."));
        } catch (IllegalArgumentException error) {
            respond(exchange, 400, errorResponse("INVALID_REQUEST", error.getMessage()));
        } catch (RuntimeException error) {
            logUnexpectedFailure("administrator operation", error);
            respond(exchange, 500, errorResponse("SERVER_ERROR", "Administrator operation failed."));
        }
    }

    private static long queryLong(String query, String name, long defaultValue) {
        if (query == null || query.isBlank()) return defaultValue;
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && name.equals(parts[0])) return Long.parseLong(parts[1]);
        }
        return defaultValue;
    }

    private static void logUnexpectedFailure(String operation, RuntimeException error) {
        log.error("Cloud API operation failed, operation={}, exceptionType={}",
            operation, error.getClass().getSimpleName());
    }

    private static String queryValue(String query, String name) {
        if (query == null || query.isBlank()) return null;
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && name.equals(parts[0])) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static AssignmentAnalyticsFilter analyticsFilter(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        String statusValue = queryValue(query, "status");
        AssignmentStudentStatus status = null;
        if (statusValue != null && !statusValue.isBlank()) {
            try {
                status = AssignmentStudentStatus.valueOf(statusValue.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("status is invalid");
            }
        }
        long page = queryLong(query, "page", 0);
        long pageSize = queryLong(query, "pageSize", 50);
        if (page > Integer.MAX_VALUE || pageSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Pagination value is too large");
        }
        return new AssignmentAnalyticsFilter(status, instantOrNull(queryValue(query, "from")),
            instantOrNull(queryValue(query, "to")), (int) page, (int) pageSize);
    }

    private static Instant instantOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        return Instant.parse(value);
    }

    private static AssignmentStatus assignmentStatus(String value, AssignmentStatus defaultValue) {
        if (value == null || value.isBlank()) {
            if (defaultValue != null) return defaultValue;
            throw new IllegalArgumentException("status must not be blank");
        }
        try {
            return AssignmentStatus.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("status is invalid");
        }
    }

    private static long positiveLong(Map<String, String> body, String name) {
        try {
            long value = Long.parseLong(body.getOrDefault(name, ""));
            if (value < 1) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " must be a positive integer");
        }
    }

    private static boolean requiredBoolean(Map<String, String> body, String name) {
        String value = body.get(name);
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(name + " must be true or false");
        }
        return Boolean.parseBoolean(value);
    }

    private static Map<String, String> request(HttpExchange exchange) throws IOException {
        Map<String, Object> decoded = JSON.readValue(exchange.getRequestBody(), new TypeReference<>() { });
        Map<String, String> result = new LinkedHashMap<>();
        decoded.forEach((key, value) -> result.put(key, value == null ? "" : String.valueOf(value)));
        return result;
    }

    private static Map<String, String> optionalRequest(HttpExchange exchange) throws IOException {
        if (exchange.getRequestHeaders().getFirst("Content-Length") == null
            || "0".equals(exchange.getRequestHeaders().getFirst("Content-Length"))) return Map.of();
        return request(exchange);
    }

    private static Map<String, Object> objectRequest(HttpExchange exchange) throws IOException {
        return JSON.readValue(exchange.getRequestBody(), new TypeReference<>() { });
    }

    private static String string(Map<String, Object> body, String name) {
        Object value = body.get(name);
        return value == null ? null : String.valueOf(value);
    }

    private static int integer(Map<String, Object> body, String name, int defaultValue) {
        Object value = body.get(name);
        if (value == null) return defaultValue;
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private static long longValue(Map<String, Object> body, String name, long defaultValue) {
        Object value = body.get(name);
        if (value == null) return defaultValue;
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static List<String> strings(Map<String, Object> body, String name) {
        Object value = body.get(name);
        if (value == null) return List.of();
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException(name + " must be a list");
        return values.stream().map(String::valueOf).toList();
    }

    private static char[] password(Map<String, String> body) { return body.getOrDefault("password", "").toCharArray(); }
    private static void clearPassword(HttpExchange ignored) { /* request body is released after this handler returns */ }

    private static String token(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() < 20) throw new SecurityException("Missing bearer token");
        return header.substring("Bearer ".length());
    }

    private static void methodNotAllowed(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Allow", "GET, POST");
        respond(exchange, 405, errorResponse("METHOD_NOT_ALLOWED", "Method not allowed."));
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void respondCsv(HttpExchange exchange, String csv) throws IOException {
        respondCsv(exchange, csv, "class-learning-records.csv");
    }

    private static void respondCsv(HttpExchange exchange, String csv, String filename) throws IOException {
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=" + filename);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Map<String, Object> errorResponse(String code, String message) { return Map.of("code", code, "message", message); }
    private static Map<String, Object> sessionResponse(SessionData session) {
        return Map.of("accessToken", session.token(), "expiresAt", session.expiresAt().toString(), "refreshToken", session.refreshToken(), "user", session.user());
    }
    private record SyncUpload(List<CloudSyncItem> items) {
        private SyncUpload { items = items == null ? List.of() : List.copyOf(items); }
    }

    private static final class CloudStore implements CloudAuthenticationService, ClassroomService {
        private static final String ASSIGNMENT_COLUMNS = "id,exercise_id,title,description,created_at,status,"
            + "due_at,updated_at,published_at,copied_from_assignment_id,version";
        private static final String SUBMISSION_COLUMNS = "id,operation_id,classroom_id,assignment_id,user_id,"
            + "attempt_number,status,result_hash,error_code,client_completed_at,submitted_at";
        private final Path database;
        private final SecureRandom random = new SecureRandom();

        private CloudStore(Path database) throws SQLException, IOException {
            this.database = database;
            Files.createDirectories(database.getParent());
            try {
                Class.forName("org.sqlite.JDBC");
                initialize();
            } catch (ClassNotFoundException error) {
                throw new SQLException("SQLite JDBC driver is unavailable", error);
            }
        }

        @Override public Session register(String email, String displayName, char[] password) { return toSession(registerData(email, displayName, password)); }
        @Override public Session login(String email, char[] password) { return toSession(loginData(email, password)); }
        @Override public AuthenticatedUser authenticate(String accessToken) { return authenticateData(accessToken); }
        @Override public void logout(String accessToken) { logout(accessToken, null); }
        void logout(String accessToken,String refreshToken){try(Connection c=open()){c.setAutoCommit(false);revoke(c,"access_tokens",accessToken);if(refreshToken!=null&&!refreshToken.isBlank())revoke(c,"refresh_tokens",refreshToken);c.commit();}catch(SQLException e){throw database(e);}}

        SessionData registerData(String email, String displayName, char[] password) {
            String normalizedEmail = validateEmail(email);
            if (displayName == null || displayName.isBlank() || displayName.length() > 80) throw new IllegalArgumentException("displayName must be 1 to 80 characters");
            validatePassword(password);
            String id = UUID.randomUUID().toString();
            byte[] salt = bytes(SALT_BYTES);
            byte[] hash = hash(password, salt);
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "insert into users(id,email,display_name,password_hash,password_salt,disabled,created_at) values(?,?,?,?,?,0,?)")) {
                statement.setString(1, id); statement.setString(2, normalizedEmail); statement.setString(3, displayName.trim());
                statement.setBytes(4, hash); statement.setBytes(5, salt); statement.setString(6, Instant.now().toString()); statement.executeUpdate();
                try (PreparedStatement role = connection.prepareStatement("insert into user_roles(user_id,role) values(?, 'STUDENT')")) { role.setString(1, id); role.executeUpdate(); }
                audit(connection, id, "AUTH_REGISTER", "USER", id, "SUCCESS", "SELF_SERVICE");
            } catch (SQLException error) { if (error.getMessage().contains("UNIQUE")) throw new SecurityException("duplicate account"); throw database(error); }
            return issue(user(id));
        }

        void ensureBootstrapAdmin(String email, char[] password) {
            String normalizedEmail = validateEmail(email);
            try (Connection connection = open();
                 PreparedStatement find = connection.prepareStatement("select id from users where email=?")) {
                find.setString(1, normalizedEmail);
                try (ResultSet existing = find.executeQuery()) {
                    String userId;
                    if (existing.next()) {
                        userId = existing.getString(1);
                    } else {
                        userId = registerData(normalizedEmail, "System Administrator", password).user().id();
                    }
                    try (PreparedStatement role = connection.prepareStatement(
                        "insert or ignore into user_roles(user_id,role) values(?, 'ADMIN')")) {
                        role.setString(1, userId);
                        role.executeUpdate();
                    }
                }
            } catch (SQLException error) {
                throw database(error);
            } finally {
                java.util.Arrays.fill(password, '\0');
            }
        }

        SessionData loginData(String email, char[] password) {
            String normalizedEmail = validateEmail(email); validatePassword(password);
            String userId;
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                 "select id,password_hash,password_salt,disabled from users where email=?")) {
                statement.setString(1, normalizedEmail);
                try (ResultSet result = statement.executeQuery()) {
                    boolean exists = result.next();
                    userId = exists ? result.getString("id") : null;
                    boolean valid = exists && result.getInt("disabled") == 0
                        && constantTimeEquals(result.getBytes("password_hash"),
                            hash(password, result.getBytes("password_salt")));
                    if (!valid) {
                        audit(connection, null, "AUTH_LOGIN", "USER", userId, "DENIED", "INVALID_CREDENTIALS");
                        throw new SecurityException("invalid credentials");
                    }
                    audit(connection, userId, "AUTH_LOGIN", "USER", userId, "SUCCESS", "CREDENTIAL_VERIFIED");
                }
            } catch (SQLException error) { throw database(error); }
            return issue(user(userId));
        }

        SessionData refreshData(String refreshToken) {
            if (refreshToken == null || refreshToken.isBlank()) throw new IllegalArgumentException("refreshToken must not be blank");
            try (Connection connection = open();
                 PreparedStatement token = connection.prepareStatement(
                     "select user_id from refresh_tokens where token_hash=? and expires_at>? and revoked_at is null")) {
                connection.setAutoCommit(false);
                token.setBytes(1, tokenHash(refreshToken));
                token.setString(2, Instant.now().toString());
                String userId;
                try (ResultSet result = token.executeQuery()) {
                    if (!result.next()) throw new SecurityException("invalid refresh token");
                    userId = result.getString(1);
                }
                try (PreparedStatement revoke = connection.prepareStatement(
                    "update refresh_tokens set revoked_at=? where token_hash=? and revoked_at is null")) {
                    revoke.setString(1, Instant.now().toString());
                    revoke.setBytes(2, tokenHash(refreshToken));
                    if (revoke.executeUpdate() != 1) throw new SecurityException("refresh token already used");
                }
                SessionData session = issue(connection, user(userId));
                connection.commit();
                return session;
            } catch (SQLException error) { throw database(error); }
        }

        @Override public Classroom create(AuthenticatedUser actor, String name) {
            if (!(actor.hasRole(UserRole.TEACHER) || actor.hasRole(UserRole.ADMIN))) throw new SecurityException("teacher role required");
            if (name == null || name.isBlank() || name.length() > 100) throw new IllegalArgumentException("name must be 1 to 100 characters");
            String id = UUID.randomUUID().toString(); Instant now = Instant.now();
            try (Connection connection = open(); PreparedStatement classroom = connection.prepareStatement("insert into classrooms(id,name,created_at) values(?,?,?)"); PreparedStatement member = connection.prepareStatement("insert into classroom_members(classroom_id,user_id,role) values(?,?,?)")) {
                connection.setAutoCommit(false); classroom.setString(1,id); classroom.setString(2,name.trim()); classroom.setString(3,now.toString()); classroom.executeUpdate();
                member.setString(1,id); member.setString(2,actor.id()); member.setString(3,UserRole.TEACHER.name()); member.executeUpdate(); connection.commit();
            } catch (SQLException error) { throw database(error); }
            return classroom(id);
        }

        @Override public Classroom addMember(AuthenticatedUser actor, String classroomId, String userId, UserRole role) {
            if (role == null || role == UserRole.ADMIN) throw new IllegalArgumentException("Only TEACHER or STUDENT can join a classroom");
            requireTeacher(actor, classroomId);
            try (Connection connection = open();
                 PreparedStatement statement = connection.prepareStatement(
                     "insert into classroom_members(classroom_id,user_id,role) values(?,?,?) "
                         + "on conflict(classroom_id,user_id) do update set role=excluded.role");
                 PreparedStatement promoteTeacher = connection.prepareStatement(
                     "insert or ignore into user_roles(user_id,role) values(?, 'TEACHER')")) {
                connection.setAutoCommit(false);
                statement.setString(1, classroomId);
                statement.setString(2, userId);
                statement.setString(3, role.name());
                statement.executeUpdate();
                if (role == UserRole.TEACHER) {
                    promoteTeacher.setString(1, userId);
                    promoteTeacher.executeUpdate();
                }
                connection.commit();
            } catch (SQLException error) { throw database(error); }
            return classroom(classroomId);
        }

        @Override public List<Classroom> listVisibleTo(AuthenticatedUser actor) {
            List<Classroom> classrooms = new ArrayList<>();
            String sql = actor.hasRole(UserRole.ADMIN) ? "select id from classrooms order by created_at desc" : "select c.id from classrooms c join classroom_members m on m.classroom_id=c.id where m.user_id=? order by c.created_at desc";
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
                if (!actor.hasRole(UserRole.ADMIN)) statement.setString(1,actor.id());
                try (ResultSet result = statement.executeQuery()) { while (result.next()) classrooms.add(classroom(result.getString(1))); }
            } catch (SQLException error) { throw database(error); }
            return List.copyOf(classrooms);
        }

        int upload(AuthenticatedUser actor, List<CloudSyncItem> items) {
            if (items.size() > 500) throw new IllegalArgumentException("A sync batch may contain at most 500 items");
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "insert into sync_events(user_id,event_id,event_type,payload_json,occurred_at) values(?,?,?,?,?) "
                    + "on conflict(user_id,event_id) do update set event_type=excluded.event_type,payload_json=excluded.payload_json,occurred_at=excluded.occurred_at")) {
                connection.setAutoCommit(false);
                for (CloudSyncItem item : items) {
                    statement.setString(1, actor.id()); statement.setString(2, item.id()); statement.setString(3, item.type());
                    statement.setString(4, item.payloadJson()); statement.setString(5, item.occurredAt().toString()); statement.addBatch();
                }
                statement.executeBatch(); connection.commit(); return items.size();
            } catch (SQLException error) { throw database(error); }
        }

        List<CloudSyncItem> download(AuthenticatedUser actor, long afterVersion) {
            if (afterVersion < 0) throw new IllegalArgumentException("afterVersion must not be negative");
            List<CloudSyncItem> items = new ArrayList<>();
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "select event_id,event_type,payload_json,occurred_at,version from sync_events where user_id=? and version>? order by version limit 500")) {
                statement.setString(1, actor.id()); statement.setLong(2, afterVersion);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) items.add(new CloudSyncItem(rows.getString(1), rows.getString(2), rows.getString(3), Instant.parse(rows.getString(4)), rows.getLong(5)));
                }
            } catch (SQLException error) { throw database(error); }
            return List.copyOf(items);
        }

        ClassAssignment createAssignment(AuthenticatedUser actor, String classroomId, String exerciseId, String title,
                                         String description, Instant dueAt, AssignmentStatus status) {
            requireTeacher(actor, classroomId);
            validateAssignmentDetails(exerciseId, title, description, dueAt);
            if (status != AssignmentStatus.DRAFT && status != AssignmentStatus.PUBLISHED) {
                throw new IllegalArgumentException("New assignments must be DRAFT or PUBLISHED");
            }
            String id = UUID.randomUUID().toString();
            Instant now = Instant.now();
            Instant publishedAt = status == AssignmentStatus.PUBLISHED ? now : null;
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "insert into class_assignments(id,classroom_id,exercise_id,title,description,created_at,status,"
                    + "due_at,published_at,copied_from_assignment_id,version,updated_at) values(?,?,?,?,?,?,?,?,?,?,1,?)")) {
                statement.setString(1, id);
                statement.setString(2, classroomId);
                statement.setString(3, exerciseId.trim());
                statement.setString(4, title.trim());
                statement.setString(5, normalizeDescription(description));
                statement.setString(6, now.toString());
                statement.setString(7, status.name());
                statement.setString(8, dueAt == null ? null : dueAt.toString());
                statement.setString(9, publishedAt == null ? null : publishedAt.toString());
                statement.setString(10, null);
                statement.setString(11, now.toString());
                statement.executeUpdate();
                audit(connection, actor.id(), "ASSIGNMENT_CREATE", "ASSIGNMENT", id, "SUCCESS", status.name());
                return assignment(connection, classroomId, id);
            } catch (SQLException error) { throw database(error); }
        }

        ClassAssignment copyAssignment(AuthenticatedUser actor, String classroomId, String assignmentId,
                                       long expectedVersion) {
            requireTeacher(actor, classroomId);
            try (Connection connection = open()) {
                ClassAssignment source = assignment(connection, classroomId, assignmentId);
                requireVersion(source, expectedVersion);
                String id = UUID.randomUUID().toString();
                Instant now = Instant.now();
                Instant copiedDueAt = source.dueAt() != null && source.dueAt().isAfter(now) ? source.dueAt() : null;
                String copiedTitle = source.title().length() <= 153 ? source.title() + " (copy)" : source.title();
                try (PreparedStatement statement = connection.prepareStatement(
                    "insert into class_assignments(id,classroom_id,exercise_id,title,description,created_at,status,"
                        + "due_at,published_at,copied_from_assignment_id,version,updated_at) "
                        + "values(?,?,?,?,?,?,'DRAFT',?,null,?,1,?)")) {
                    statement.setString(1, id);
                    statement.setString(2, classroomId);
                    statement.setString(3, source.exerciseId());
                    statement.setString(4, copiedTitle);
                    statement.setString(5, source.description());
                    statement.setString(6, now.toString());
                    statement.setString(7, copiedDueAt == null ? null : copiedDueAt.toString());
                    statement.setString(8, source.id());
                    statement.setString(9, now.toString());
                    statement.executeUpdate();
                }
                audit(connection, actor.id(), "ASSIGNMENT_COPY", "ASSIGNMENT", id, "SUCCESS", "SOURCE_VERSION_OK");
                return assignment(connection, classroomId, id);
            } catch (SQLException error) { throw database(error); }
        }

        ClassAssignment setAssignmentDueAt(AuthenticatedUser actor, String classroomId, String assignmentId,
                                           Instant dueAt, long expectedVersion) {
            requireTeacher(actor, classroomId);
            if (dueAt == null || !dueAt.isAfter(Instant.now())) {
                throw new IllegalArgumentException("dueAt must be in the future");
            }
            try (Connection connection = open()) {
                ClassAssignment current = assignment(connection, classroomId, assignmentId);
                requireEditable(current);
                requireVersion(current, expectedVersion);
                Instant now = Instant.now();
                try (PreparedStatement statement = connection.prepareStatement(
                    "update class_assignments set due_at=?,updated_at=?,version=version+1 "
                        + "where id=? and classroom_id=? and version=?")) {
                    statement.setString(1, dueAt.toString());
                    statement.setString(2, now.toString());
                    statement.setString(3, assignmentId);
                    statement.setString(4, classroomId);
                    statement.setLong(5, expectedVersion);
                    requireUpdated(statement, connection, classroomId, assignmentId);
                }
                audit(connection, actor.id(), "ASSIGNMENT_DUE_UPDATE", "ASSIGNMENT", assignmentId,
                    "SUCCESS", "VERSION_MATCHED");
                return assignment(connection, classroomId, assignmentId);
            } catch (SQLException error) { throw database(error); }
        }

        ClassAssignment changeAssignmentStatus(AuthenticatedUser actor, String classroomId, String assignmentId,
                                                AssignmentStatus status, long expectedVersion) {
            requireTeacher(actor, classroomId);
            if (status == null) throw new IllegalArgumentException("status must not be null");
            Instant now = Instant.now();
            try (Connection connection = open()) {
                ClassAssignment current = assignment(connection, classroomId, assignmentId);
                requireVersion(current, expectedVersion);
                if (!validTransition(current.status(), status)) {
                    throw new IllegalArgumentException("Assignment status transition is not allowed");
                }
                String publishedAt = status == AssignmentStatus.PUBLISHED && current.publishedAt() == null
                    ? now.toString() : current.publishedAt() == null ? null : current.publishedAt().toString();
                try (PreparedStatement statement = connection.prepareStatement(
                    "update class_assignments set status=?,published_at=?,updated_at=?,version=version+1 "
                        + "where id=? and classroom_id=? and version=?")) {
                    statement.setString(1, status.name());
                    statement.setString(2, publishedAt);
                    statement.setString(3, now.toString());
                    statement.setString(4, assignmentId);
                    statement.setString(5, classroomId);
                    statement.setLong(6, expectedVersion);
                    requireUpdated(statement, connection, classroomId, assignmentId);
                }
                audit(connection, actor.id(), "ASSIGNMENT_STATUS_UPDATE", "ASSIGNMENT", assignmentId,
                    "SUCCESS", status.name());
                return assignment(connection, classroomId, assignmentId);
            } catch (SQLException error) { throw database(error); }
        }

        ClassAssignment updateAssignment(AuthenticatedUser actor, String classroomId, String assignmentId,
                                         String title, String description, Instant dueAt, long expectedVersion) {
            requireTeacher(actor, classroomId);
            validateAssignmentDetails("existing", title, description, dueAt);
            try (Connection connection = open()) {
                ClassAssignment current = assignment(connection, classroomId, assignmentId);
                requireEditable(current);
                requireVersion(current, expectedVersion);
                try (PreparedStatement statement = connection.prepareStatement(
                    "update class_assignments set title=?,description=?,due_at=?,updated_at=?,version=version+1 "
                        + "where id=? and classroom_id=? and version=?")) {
                    statement.setString(1, title.trim());
                    statement.setString(2, normalizeDescription(description));
                    statement.setString(3, dueAt == null ? null : dueAt.toString());
                    statement.setString(4, Instant.now().toString());
                    statement.setString(5, assignmentId);
                    statement.setString(6, classroomId);
                    statement.setLong(7, expectedVersion);
                    requireUpdated(statement, connection, classroomId, assignmentId);
                }
                audit(connection, actor.id(), "ASSIGNMENT_DETAILS_UPDATE", "ASSIGNMENT", assignmentId,
                    "SUCCESS", "VERSION_MATCHED");
                return assignment(connection, classroomId, assignmentId);
            } catch (SQLException error) { throw database(error); }
        }

        private boolean validTransition(AssignmentStatus from, AssignmentStatus to) {
            if (from == to) return true;
            return switch (from) {
                case DRAFT -> to == AssignmentStatus.PUBLISHED || to == AssignmentStatus.WITHDRAWN;
                case PUBLISHED -> to == AssignmentStatus.CLOSED || to == AssignmentStatus.WITHDRAWN;
                case CLOSED, WITHDRAWN -> to == AssignmentStatus.ARCHIVED;
                case ARCHIVED -> false;
            };
        }

        List<ClassAssignment> listAssignments(AuthenticatedUser actor, String classroomId,
                                              AssignmentStatus statusFilter) {
            requireMember(actor, classroomId);
            closeExpiredAssignments(classroomId);
            List<ClassAssignment> result = new ArrayList<>();
            boolean teacher = actor.hasRole(UserRole.ADMIN) || isTeacher(actor, classroomId);
            if (!teacher && statusFilter != null && statusFilter != AssignmentStatus.PUBLISHED
                && statusFilter != AssignmentStatus.CLOSED) {
                throw new SecurityException("Students cannot view unpublished assignments");
            }
            StringBuilder sql = new StringBuilder("select ").append(ASSIGNMENT_COLUMNS)
                .append(" from class_assignments where classroom_id=?");
            if (!teacher) sql.append(" and status in ('PUBLISHED','CLOSED')");
            if (statusFilter != null) sql.append(" and status=?");
            sql.append(" order by created_at desc");
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                statement.setString(1, classroomId);
                if (statusFilter != null) statement.setString(2, statusFilter.name());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) result.add(assignment(rows, classroomId));
                }
                return List.copyOf(result);
            } catch (SQLException error) { throw database(error); }
        }

        private void closeExpiredAssignments(String classroomId) {
            Instant now = Instant.now();
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "update class_assignments set status='CLOSED',updated_at=?,version=version+1 "
                    + "where classroom_id=? and status='PUBLISHED' and due_at is not null and due_at<=?")) {
                statement.setString(1, now.toString());
                statement.setString(2, classroomId);
                statement.setString(3, now.toString());
                statement.executeUpdate();
            } catch (SQLException error) { throw database(error); }
        }

        AssignmentSubmission submitAssignment(AuthenticatedUser actor, String classroomId, String assignmentId,
                                              AssignmentSubmissionRequest request) {
            requireStudent(actor, classroomId);
            closeExpiredAssignments(classroomId);
            try (Connection connection = open()) {
                ClassAssignment assignment = assignment(connection, classroomId, assignmentId);
                ensureSubmissionOpen(assignment);
                AssignmentSubmission existing = submissionByOperation(connection, actor.id(), request.operationId());
                if (existing != null) {
                    if (!existing.assignmentId().equals(assignmentId)) {
                        throw new SubmissionOperationConflictException();
                    }
                    return existing;
                }
                String id = UUID.randomUUID().toString();
                Instant submittedAt = Instant.now();
                try (PreparedStatement statement = connection.prepareStatement(
                    "insert into assignment_submissions(id,operation_id,classroom_id,assignment_id,user_id,"
                        + "attempt_number,status,result_hash,error_code,client_completed_at,submitted_at) "
                        + "values(?,?,?,?,?,(select coalesce(max(attempt_number),0)+1 from assignment_submissions "
                        + "where assignment_id=? and user_id=?),?,?,?,?,?) "
                        + "on conflict(user_id,operation_id) do nothing")) {
                    statement.setString(1, id);
                    statement.setString(2, request.operationId());
                    statement.setString(3, classroomId);
                    statement.setString(4, assignmentId);
                    statement.setString(5, actor.id());
                    statement.setString(6, assignmentId);
                    statement.setString(7, actor.id());
                    statement.setString(8, request.passed()
                        ? AssignmentSubmissionStatus.PASSED.name() : AssignmentSubmissionStatus.FAILED.name());
                    statement.setString(9, request.resultHash());
                    statement.setString(10, request.errorCode());
                    statement.setString(11, request.clientCompletedAt() == null
                        ? null : request.clientCompletedAt().toString());
                    statement.setString(12, submittedAt.toString());
                    if (statement.executeUpdate() == 0) {
                        AssignmentSubmission concurrent = submissionByOperation(
                            connection, actor.id(), request.operationId());
                        if (concurrent == null || !concurrent.assignmentId().equals(assignmentId)) {
                            throw new SubmissionOperationConflictException();
                        }
                        return concurrent;
                    }
                }
                return submissionById(connection, id);
            } catch (SQLException error) { throw database(error); }
        }

        List<AssignmentSubmission> listOwnSubmissions(AuthenticatedUser actor, String classroomId,
                                                      String assignmentId) {
            requireStudent(actor, classroomId);
            try (Connection connection = open()) {
                assignment(connection, classroomId, assignmentId);
                List<AssignmentSubmission> submissions = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(
                    "select " + SUBMISSION_COLUMNS + " from assignment_submissions "
                        + "where classroom_id=? and assignment_id=? and user_id=? order by attempt_number")) {
                    statement.setString(1, classroomId);
                    statement.setString(2, assignmentId);
                    statement.setString(3, actor.id());
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) submissions.add(submission(rows));
                    }
                }
                return List.copyOf(submissions);
            } catch (SQLException error) { throw database(error); }
        }

        private void ensureSubmissionOpen(ClassAssignment assignment) {
            if (assignment.status() == AssignmentStatus.PUBLISHED
                && (assignment.dueAt() == null || assignment.dueAt().isAfter(Instant.now()))) return;
            String code = switch (assignment.status()) {
                case DRAFT -> "ASSIGNMENT_NOT_PUBLISHED";
                case PUBLISHED, CLOSED -> "ASSIGNMENT_CLOSED";
                case WITHDRAWN -> "ASSIGNMENT_WITHDRAWN";
                case ARCHIVED -> "ASSIGNMENT_ARCHIVED";
            };
            throw new AssignmentSubmissionRejectedException(code, "Assignment does not accept submissions");
        }

        private AssignmentSubmission submissionByOperation(Connection connection, String userId, String operationId)
            throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                "select " + SUBMISSION_COLUMNS + " from assignment_submissions where user_id=? and operation_id=?")) {
                statement.setString(1, userId);
                statement.setString(2, operationId);
                try (ResultSet row = statement.executeQuery()) {
                    return row.next() ? submission(row) : null;
                }
            }
        }

        private AssignmentSubmission submissionById(Connection connection, String id) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                "select " + SUBMISSION_COLUMNS + " from assignment_submissions where id=?")) {
                statement.setString(1, id);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new IllegalStateException("Submission insert did not return a row");
                    return submission(row);
                }
            }
        }

        private AssignmentSubmission submission(ResultSet row) throws SQLException {
            String clientCompletedAt = row.getString("client_completed_at");
            return new AssignmentSubmission(
                row.getString("id"), row.getString("operation_id"), row.getString("classroom_id"),
                row.getString("assignment_id"), row.getString("user_id"), row.getInt("attempt_number"),
                AssignmentSubmissionStatus.valueOf(row.getString("status")), row.getString("result_hash"),
                row.getString("error_code"), clientCompletedAt == null ? null : Instant.parse(clientCompletedAt),
                Instant.parse(row.getString("submitted_at"))
            );
        }

        AssignmentAnalyticsReport assignmentAnalytics(AuthenticatedUser actor, String classroomId,
                                                       String assignmentId, AssignmentAnalyticsFilter filter) {
            requireTeacher(actor, classroomId);
            AssignmentAnalyticsFilter applied = filter == null ? AssignmentAnalyticsFilter.firstPage() : filter;
            try (Connection connection = open()) {
                assignment(connection, classroomId, assignmentId);
                List<AssignmentAnalyticsRow> allRows = assignmentAnalyticsRows(
                    connection, classroomId, assignmentId, applied);
                List<AssignmentAnalyticsRow> filteredRows = allRows.stream()
                    .filter(row -> matchesStatus(row, applied.status())).toList();
                int submittedStudents = (int) allRows.stream().filter(row -> row.attemptCount() > 0).count();
                int passedStudents = (int) allRows.stream().filter(row -> row.passedAttempts() > 0).count();
                int totalAttempts = allRows.stream().mapToInt(AssignmentAnalyticsRow::attemptCount).sum();
                long offsetValue = (long) applied.page() * applied.pageSize();
                int fromIndex = (int) Math.min(offsetValue, filteredRows.size());
                int toIndex = Math.min(fromIndex + applied.pageSize(), filteredRows.size());
                return new AssignmentAnalyticsReport(
                    classroomId, assignmentId, allRows.size(), submittedStudents, passedStudents, totalAttempts,
                    rate(submittedStudents, allRows.size()), rate(passedStudents, submittedStudents),
                    commonErrors(connection, classroomId, assignmentId, applied),
                    filteredRows.subList(fromIndex, toIndex), applied.page(), applied.pageSize(), filteredRows.size(),
                    Instant.now()
                );
            } catch (SQLException error) { throw database(error); }
        }

        String exportAssignmentAnalyticsCsv(AuthenticatedUser actor, String classroomId, String assignmentId,
                                            AssignmentAnalyticsFilter filter) {
            requireTeacher(actor, classroomId);
            AssignmentAnalyticsFilter applied = filter == null ? AssignmentAnalyticsFilter.firstPage() : filter;
            try (Connection connection = open()) {
                assignment(connection, classroomId, assignmentId);
                List<AssignmentAnalyticsRow> rows = assignmentAnalyticsRows(connection, classroomId, assignmentId, applied)
                    .stream().filter(row -> matchesStatus(row, applied.status())).toList();
                StringBuilder csv = new StringBuilder(
                    "\uFEFFstudent_email,display_name,status,attempt_count,passed_attempts,last_submitted_at\r\n");
                for (AssignmentAnalyticsRow row : rows) {
                    csv.append(csvCell(row.email())).append(',').append(csvCell(row.displayName())).append(',')
                        .append(row.status().name()).append(',').append(row.attemptCount()).append(',')
                        .append(row.passedAttempts()).append(',')
                        .append(row.lastSubmittedAt() == null ? "" : row.lastSubmittedAt()).append("\r\n");
                }
                try (PreparedStatement audit = connection.prepareStatement(
                    "insert into export_audit(id,user_id,classroom_id,row_count,created_at,assignment_id,"
                        + "export_type,filter_summary) values(?,?,?,?,?,?,?,?)")) {
                    audit.setString(1, UUID.randomUUID().toString());
                    audit.setString(2, actor.id());
                    audit.setString(3, classroomId);
                    audit.setInt(4, rows.size());
                    audit.setString(5, Instant.now().toString());
                    audit.setString(6, assignmentId);
                    audit.setString(7, "ASSIGNMENT_ANALYTICS");
                    audit.setString(8, analyticsFilterSummary(applied));
                    audit.executeUpdate();
                }
                audit(connection, actor.id(), "ASSIGNMENT_ANALYTICS_EXPORT", "ASSIGNMENT", assignmentId,
                    "SUCCESS", "FILTERED_EXPORT");
                return csv.toString();
            } catch (SQLException error) { throw database(error); }
        }

        private List<AssignmentAnalyticsRow> assignmentAnalyticsRows(Connection connection, String classroomId,
                                                                     String assignmentId,
                                                                     AssignmentAnalyticsFilter filter)
            throws SQLException {
            StringBuilder sql = new StringBuilder(
                "select u.id,u.email,u.display_name,count(s.id) attempt_count,"
                    + "coalesce(sum(case when s.status='PASSED' then 1 else 0 end),0) passed_attempts,"
                    + "max(s.submitted_at) last_submitted_at from classroom_members m "
                    + "join users u on u.id=m.user_id left join assignment_submissions s "
                    + "on s.assignment_id=? and s.user_id=m.user_id");
            if (filter.from() != null) sql.append(" and s.submitted_at>=?");
            if (filter.to() != null) sql.append(" and s.submitted_at<=?");
            sql.append(" where m.classroom_id=? and m.role='STUDENT' "
                + "group by u.id,u.email,u.display_name order by u.email");
            List<AssignmentAnalyticsRow> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int parameter = 1;
                statement.setString(parameter++, assignmentId);
                if (filter.from() != null) statement.setString(parameter++, filter.from().toString());
                if (filter.to() != null) statement.setString(parameter++, filter.to().toString());
                statement.setString(parameter, classroomId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        int attempts = result.getInt("attempt_count");
                        int passedAttempts = result.getInt("passed_attempts");
                        String lastSubmittedAt = result.getString("last_submitted_at");
                        AssignmentStudentStatus status = attempts == 0 ? AssignmentStudentStatus.NOT_SUBMITTED
                            : passedAttempts > 0 ? AssignmentStudentStatus.PASSED : AssignmentStudentStatus.FAILED;
                        rows.add(new AssignmentAnalyticsRow(
                            result.getString("id"), result.getString("email"), result.getString("display_name"),
                            status, attempts, passedAttempts,
                            lastSubmittedAt == null ? null : Instant.parse(lastSubmittedAt)
                        ));
                    }
                }
            }
            return List.copyOf(rows);
        }

        private List<AssignmentErrorCount> commonErrors(Connection connection, String classroomId,
                                                        String assignmentId, AssignmentAnalyticsFilter filter)
            throws SQLException {
            StringBuilder sql = new StringBuilder(
                "select s.error_code,count(*) error_count from assignment_submissions s "
                    + "join classroom_members m on m.classroom_id=s.classroom_id and m.user_id=s.user_id "
                    + "where s.classroom_id=? and s.assignment_id=? and s.status='FAILED' and s.error_code is not null");
            if (filter.from() != null) sql.append(" and s.submitted_at>=?");
            if (filter.to() != null) sql.append(" and s.submitted_at<=?");
            sql.append(" group by s.error_code order by error_count desc,s.error_code limit 10");
            List<AssignmentErrorCount> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int parameter = 1;
                statement.setString(parameter++, classroomId);
                statement.setString(parameter++, assignmentId);
                if (filter.from() != null) statement.setString(parameter++, filter.from().toString());
                if (filter.to() != null) statement.setString(parameter, filter.to().toString());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(new AssignmentErrorCount(rows.getString("error_code"), rows.getInt("error_count")));
                    }
                }
            }
            return List.copyOf(result);
        }

        private boolean matchesStatus(AssignmentAnalyticsRow row, AssignmentStudentStatus filter) {
            if (filter == null) return true;
            if (filter == AssignmentStudentStatus.SUBMITTED) return row.attemptCount() > 0;
            return row.status() == filter;
        }

        private double rate(int numerator, int denominator) {
            return denominator == 0 ? 0.0 : (double) numerator / denominator;
        }

        private String analyticsFilterSummary(AssignmentAnalyticsFilter filter) {
            return "status=" + (filter.status() == null ? "ALL" : filter.status().name())
                + ";from=" + (filter.from() == null ? "" : filter.from())
                + ";to=" + (filter.to() == null ? "" : filter.to());
        }

        AdminHealthSummary adminHealth(AuthenticatedUser actor) {
            requireAdmin(actor);
            try (Connection connection = open()) {
                return new AdminHealthSummary(
                    count(connection, "select count(*) from users where disabled=0"),
                    count(connection, "select count(*) from users where disabled<>0"),
                    count(connection, "select count(*) from access_tokens where revoked_at is null and expires_at>'"
                        + Instant.now() + "'"),
                    count(connection, "select count(*) from refresh_tokens where revoked_at is null and expires_at>'"
                        + Instant.now() + "'"),
                    count(connection, "select count(*) from class_assignments"),
                    count(connection, "select count(*) from assignment_submissions"),
                    Instant.now()
                );
            } catch (SQLException error) { throw database(error); }
        }

        List<AdminUserSummary> adminUsers(AuthenticatedUser actor) {
            requireAdmin(actor);
            List<AdminUserSummary> users = new ArrayList<>();
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "select id,email,display_name,disabled,created_at from users order by created_at,id")) {
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) users.add(adminUser(connection, rows));
                }
                return List.copyOf(users);
            } catch (SQLException error) { throw database(error); }
        }

        AdminUserSummary setUserDisabled(AuthenticatedUser actor, String userId, boolean disabled,
                                         String reasonCode) {
            requireAdmin(actor);
            String reason = validateReasonCode(reasonCode);
            try (Connection connection = open()) {
                connection.setAutoCommit(false);
                AdminUserSummary target = adminUser(connection, userId);
                if (disabled && target.roles().contains(UserRole.ADMIN) && !target.disabled()
                    && activeAdminCount(connection) <= 1) {
                    audit(connection, actor.id(), "ADMIN_USER_DISABLE", "USER", userId, "DENIED",
                        "LAST_ADMIN_PROTECTED");
                    connection.commit();
                    throw new AdminOperationRejectedException(
                        "LAST_ADMIN_PROTECTED", "The final active administrator cannot be disabled");
                }
                try (PreparedStatement update = connection.prepareStatement(
                    "update users set disabled=? where id=?")) {
                    update.setInt(1, disabled ? 1 : 0);
                    update.setString(2, userId);
                    update.executeUpdate();
                }
                if (disabled) revokeAllSessions(connection, userId);
                audit(connection, actor.id(), disabled ? "ADMIN_USER_DISABLE" : "ADMIN_USER_RESTORE",
                    "USER", userId, "SUCCESS", reason);
                connection.commit();
                return adminUser(connection, userId);
            } catch (SQLException error) { throw database(error); }
        }

        void revokeUserSessions(AuthenticatedUser actor, String userId, String reasonCode) {
            requireAdmin(actor);
            String reason = validateReasonCode(reasonCode);
            try (Connection connection = open()) {
                connection.setAutoCommit(false);
                adminUser(connection, userId);
                revokeAllSessions(connection, userId);
                audit(connection, actor.id(), "ADMIN_SESSION_REVOKE_ALL", "USER", userId, "SUCCESS", reason);
                connection.commit();
            } catch (SQLException error) { throw database(error); }
        }

        AdminAuditPage adminAudit(AuthenticatedUser actor, String action, Instant from, Instant to,
                                  int page, int pageSize) {
            requireAdmin(actor);
            if (action != null && !action.isBlank() && !action.matches("[A-Z0-9_]{3,80}")) {
                throw new IllegalArgumentException("action is invalid");
            }
            if (from != null && to != null && from.isAfter(to)) {
                throw new IllegalArgumentException("from must not be after to");
            }
            if (page < 0 || pageSize < 1 || pageSize > 200) {
                throw new IllegalArgumentException("Invalid audit pagination");
            }
            StringBuilder where = new StringBuilder(" where 1=1");
            List<String> parameters = new ArrayList<>();
            if (action != null && !action.isBlank()) { where.append(" and action=?"); parameters.add(action); }
            if (from != null) { where.append(" and created_at>=?"); parameters.add(from.toString()); }
            if (to != null) { where.append(" and created_at<=?"); parameters.add(to.toString()); }
            try (Connection connection = open()) {
                int totalRows;
                try (PreparedStatement count = connection.prepareStatement(
                    "select count(*) from admin_audit" + where)) {
                    bind(count, parameters);
                    try (ResultSet row = count.executeQuery()) { totalRows = row.getInt(1); }
                }
                List<AdminAuditEntry> entries = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(
                    "select id,actor_user_id,action,target_type,target_id,result,reason_code,correlation_id,created_at "
                        + "from admin_audit" + where + " order by created_at desc,id desc limit ? offset ?")) {
                    int parameter = bind(statement, parameters);
                    statement.setInt(parameter++, pageSize);
                    statement.setLong(parameter, (long) page * pageSize);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) entries.add(new AdminAuditEntry(
                            rows.getString("id"), rows.getString("actor_user_id"), rows.getString("action"),
                            rows.getString("target_type"), rows.getString("target_id"), rows.getString("result"),
                            rows.getString("reason_code"), rows.getString("correlation_id"),
                            Instant.parse(rows.getString("created_at"))
                        ));
                    }
                }
                return new AdminAuditPage(entries, page, pageSize, totalRows);
            } catch (SQLException error) { throw database(error); }
        }

        private int count(Connection connection, String sql) throws SQLException {
            try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
                return row.getInt(1);
            }
        }

        RetentionPreview previewRetention(AuthenticatedUser actor, RetentionCategory category, Instant cutoff) {
            requireAdmin(actor);
            if (category == null || cutoff == null) {
                throw new IllegalArgumentException("Retention category and cutoff are required");
            }
            int minimumDays = minimumRetentionDays(category);
            if (cutoff.isAfter(Instant.now().minus(minimumDays, ChronoUnit.DAYS))) {
                throw new IllegalArgumentException(
                    "Retention cutoff must preserve at least " + minimumDays + " days for " + category);
            }
            RetentionSpec spec = retentionSpec(category);
            String id = UUID.randomUUID().toString();
            String confirmationToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(24));
            Instant now = Instant.now();
            Instant expiresAt = now.plus(15, ChronoUnit.MINUTES);
            try (Connection connection = open()) {
                int affectedRows = retentionCount(connection, spec, cutoff);
                try (PreparedStatement statement = connection.prepareStatement(
                    "insert into retention_jobs(id,category,cutoff,preview_count,affected_count,status,"
                        + "confirmation_hash,expires_at,created_at,actor_user_id) values(?,?,?,?,0,'PREVIEWED',?,?,?,?)")) {
                    statement.setString(1, id);
                    statement.setString(2, category.name());
                    statement.setString(3, cutoff.toString());
                    statement.setInt(4, affectedRows);
                    statement.setBytes(5, tokenHash(confirmationToken));
                    statement.setString(6, expiresAt.toString());
                    statement.setString(7, now.toString());
                    statement.setString(8, actor.id());
                    statement.executeUpdate();
                }
                audit(connection, actor.id(), "ADMIN_RETENTION_PREVIEW", "RETENTION_JOB", id,
                    "SUCCESS", category.name());
                return new RetentionPreview(id, category, cutoff, affectedRows, expiresAt, confirmationToken);
            } catch (SQLException error) { throw database(error); }
        }

        RetentionJob executeRetention(AuthenticatedUser actor, String previewId, String confirmationToken,
                                      String backupReference) {
            requireAdmin(actor);
            validateUuid(previewId, "previewId");
            if (confirmationToken == null || confirmationToken.isBlank()) {
                throw new IllegalArgumentException("confirmationToken is required");
            }
            if (backupReference == null || !backupReference.matches("[A-Za-z0-9._:/-]{3,200}")) {
                throw new IllegalArgumentException("A valid backupReference is required");
            }
            try (Connection validation = open()) {
                RetentionJobState state = retentionJobState(validation, previewId);
                if (!"PREVIEWED".equals(state.status()) || Instant.now().isAfter(state.expiresAt())
                    || !constantTimeEquals(state.confirmationHash(), tokenHash(confirmationToken))) {
                    audit(validation, actor.id(), "ADMIN_RETENTION_EXECUTE", "RETENTION_JOB", previewId,
                        "DENIED", "INVALID_OR_EXPIRED_CONFIRMATION");
                    throw new AdminOperationRejectedException("RETENTION_CONFIRMATION_INVALID",
                        "Retention preview confirmation is invalid or expired");
                }
            } catch (SQLException error) { throw database(error); }
            String safetyBackup = createRetentionBackup(previewId);
            try (Connection connection = open()) {
                connection.setAutoCommit(false);
                RetentionJobState state = retentionJobState(connection, previewId);
                if (!"PREVIEWED".equals(state.status()) || Instant.now().isAfter(state.expiresAt())
                    || !constantTimeEquals(state.confirmationHash(), tokenHash(confirmationToken))) {
                    audit(connection, actor.id(), "ADMIN_RETENTION_EXECUTE", "RETENTION_JOB", previewId,
                        "DENIED", "INVALID_OR_EXPIRED_CONFIRMATION");
                    connection.commit();
                    throw new AdminOperationRejectedException("RETENTION_CONFIRMATION_INVALID",
                        "Retention preview confirmation is invalid or expired");
                }
                try (PreparedStatement lock = connection.prepareStatement(
                    "update retention_jobs set status='EXECUTING' where id=? and status='PREVIEWED'")) {
                    lock.setString(1, previewId);
                    if (lock.executeUpdate() != 1) throw new SQLException("Retention job state changed concurrently");
                }
                RetentionSpec spec = retentionSpec(state.category());
                int currentRows = retentionCount(connection, spec, state.cutoff());
                if (currentRows != state.previewRows()) {
                    try (PreparedStatement update = connection.prepareStatement(
                        "update retention_jobs set status='BLOCKED' where id=?")) {
                        update.setString(1, previewId);
                        update.executeUpdate();
                    }
                    audit(connection, actor.id(), "ADMIN_RETENTION_EXECUTE", "RETENTION_JOB", previewId,
                        "DENIED", "SCOPE_CHANGED");
                    connection.commit();
                    throw new AdminOperationRejectedException("RETENTION_SCOPE_CHANGED",
                        "Retention scope changed after preview; create a new preview");
                }
                int archivedRows = archiveRetentionRows(connection, previewId, spec, state.cutoff());
                int deletedRows = deleteRetentionRows(connection, spec, state.cutoff());
                if (archivedRows != currentRows || deletedRows != currentRows) {
                    throw new SQLException("Retention archive and delete counts did not match preview");
                }
                Instant executedAt = Instant.now();
                try (PreparedStatement update = connection.prepareStatement(
                    "update retention_jobs set status='COMPLETED',affected_count=?,backup_reference=?,safety_backup=?,"
                        + "executed_at=? "
                        + "where id=?")) {
                    update.setInt(1, deletedRows);
                    update.setString(2, backupReference);
                    update.setString(3, safetyBackup);
                    update.setString(4, executedAt.toString());
                    update.setString(5, previewId);
                    update.executeUpdate();
                }
                audit(connection, actor.id(), "ADMIN_RETENTION_EXECUTE", "RETENTION_JOB", previewId,
                    "SUCCESS", state.category().name());
                connection.commit();
                return retentionJob(connection, previewId);
            } catch (SQLException error) { throw database(error); }
        }

        private String createRetentionBackup(String jobId) {
            Path backupDirectory = database.getParent().resolve("retention-backups");
            Path backup = backupDirectory.resolve("retention-" + jobId + ".db").toAbsolutePath().normalize();
            try {
                Files.createDirectories(backupDirectory);
                String escapedPath = backup.toString().replace("'", "''");
                try (Connection source = open(); Statement statement = source.createStatement()) {
                    statement.executeUpdate("vacuum into '" + escapedPath + "'");
                }
                try (Connection verification = DriverManager.getConnection("jdbc:sqlite:" + backup);
                     Statement statement = verification.createStatement();
                     ResultSet result = statement.executeQuery("pragma integrity_check")) {
                    if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                        throw new SQLException("Retention safety backup failed integrity verification");
                    }
                }
                return backup.getFileName().toString();
            } catch (IOException | SQLException error) {
                throw new AdminOperationRejectedException("RETENTION_BACKUP_FAILED",
                    "Retention safety backup could not be created and verified");
            }
        }

        RetentionJob restoreRetention(AuthenticatedUser actor, String jobId) {
            requireAdmin(actor);
            validateUuid(jobId, "jobId");
            try (Connection connection = open()) {
                connection.setAutoCommit(false);
                RetentionJobState state = retentionJobState(connection, jobId);
                if (!"COMPLETED".equals(state.status())) {
                    audit(connection, actor.id(), "ADMIN_RETENTION_RESTORE", "RETENTION_JOB", jobId,
                        "DENIED", "JOB_NOT_RESTORABLE");
                    connection.commit();
                    throw new AdminOperationRejectedException("RETENTION_NOT_RESTORABLE",
                        "Only a completed retention job can be restored");
                }
                int restored = restoreRetentionRows(connection, jobId, retentionSpec(state.category()));
                if (restored != state.affectedRows()) {
                    throw new SQLException("Restored row count did not match the completed job");
                }
                Instant restoredAt = Instant.now();
                try (PreparedStatement update = connection.prepareStatement(
                    "update retention_jobs set status='RESTORED',restored_at=? where id=?")) {
                    update.setString(1, restoredAt.toString());
                    update.setString(2, jobId);
                    update.executeUpdate();
                }
                audit(connection, actor.id(), "ADMIN_RETENTION_RESTORE", "RETENTION_JOB", jobId,
                    "SUCCESS", state.category().name());
                connection.commit();
                return retentionJob(connection, jobId);
            } catch (SQLException error) { throw database(error); }
        }

        private int retentionCount(Connection connection, RetentionSpec spec, Instant cutoff) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from " + spec.table() + " where " + spec.timeColumn() + "<?")) {
                statement.setString(1, cutoff.toString());
                try (ResultSet row = statement.executeQuery()) { return row.getInt(1); }
            }
        }

        private int archiveRetentionRows(Connection connection, String jobId, RetentionSpec spec, Instant cutoff)
            throws SQLException {
            String columns = String.join(",", spec.columns());
            int archived = 0;
            try (PreparedStatement select = connection.prepareStatement(
                "select " + columns + " from " + spec.table() + " where " + spec.timeColumn() + "<?");
                 PreparedStatement insert = connection.prepareStatement(
                     "insert into retention_archive(job_id,category,row_key,payload_json,archived_at) values(?,?,?,?,?)")) {
                select.setString(1, cutoff.toString());
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        for (String column : spec.columns()) payload.put(column, rows.getObject(column));
                        insert.setString(1, jobId);
                        insert.setString(2, spec.category().name());
                        insert.setString(3, String.valueOf(rows.getObject(spec.keyColumn())));
                        insert.setString(4, JSON.writeValueAsString(payload));
                        insert.setString(5, Instant.now().toString());
                        insert.addBatch();
                        archived++;
                    }
                } catch (IOException error) { throw new SQLException("Could not archive retention payload", error); }
                insert.executeBatch();
            }
            return archived;
        }

        private int deleteRetentionRows(Connection connection, RetentionSpec spec, Instant cutoff) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                "delete from " + spec.table() + " where " + spec.timeColumn() + "<?")) {
                statement.setString(1, cutoff.toString());
                return statement.executeUpdate();
            }
        }

        private int restoreRetentionRows(Connection connection, String jobId, RetentionSpec spec) throws SQLException {
            String columns = String.join(",", spec.columns());
            String placeholders = String.join(",", java.util.Collections.nCopies(spec.columns().size(), "?"));
            int restored = 0;
            try (PreparedStatement archive = connection.prepareStatement(
                "select payload_json from retention_archive where job_id=? order by row_key");
                 PreparedStatement insert = connection.prepareStatement(
                     "insert into " + spec.table() + "(" + columns + ") values(" + placeholders + ")")) {
                archive.setString(1, jobId);
                try (ResultSet rows = archive.executeQuery()) {
                    while (rows.next()) {
                        Map<String, Object> payload;
                        try {
                            payload = JSON.readValue(rows.getString(1), new TypeReference<Map<String, Object>>() { });
                        } catch (IOException error) {
                            throw new SQLException("Could not read retention archive payload", error);
                        }
                        int index = 1;
                        for (String column : spec.columns()) insert.setObject(index++, payload.get(column));
                        insert.addBatch();
                        restored++;
                    }
                }
                insert.executeBatch();
            }
            return restored;
        }

        private RetentionJobState retentionJobState(Connection connection, String id) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                "select category,cutoff,preview_count,affected_count,status,confirmation_hash,expires_at "
                    + "from retention_jobs where id=?")) {
                statement.setString(1, id);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new IllegalArgumentException("Retention job was not found");
                    return new RetentionJobState(RetentionCategory.valueOf(row.getString("category")),
                        Instant.parse(row.getString("cutoff")), row.getInt("preview_count"),
                        row.getInt("affected_count"), row.getString("status"),
                        row.getBytes("confirmation_hash"), Instant.parse(row.getString("expires_at")));
                }
            }
        }

        private RetentionJob retentionJob(Connection connection, String id) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                "select category,cutoff,preview_count,affected_count,status,backup_reference,created_at,executed_at,"
                    + "restored_at from retention_jobs where id=?")) {
                statement.setString(1, id);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new IllegalArgumentException("Retention job was not found");
                    return new RetentionJob(id, RetentionCategory.valueOf(row.getString("category")),
                        Instant.parse(row.getString("cutoff")), row.getInt("preview_count"),
                        row.getInt("affected_count"), row.getString("status"), row.getString("backup_reference"),
                        Instant.parse(row.getString("created_at")), instantOrNull(row.getString("executed_at")),
                        instantOrNull(row.getString("restored_at")));
                }
            }
        }

        private RetentionSpec retentionSpec(RetentionCategory category) {
            return switch (category) {
                case SYNC_EVENTS -> new RetentionSpec(category, "sync_events", "version", "occurred_at",
                    List.of("version", "user_id", "event_id", "event_type", "payload_json", "occurred_at"));
                case ASSIGNMENT_SUBMISSIONS -> new RetentionSpec(category, "assignment_submissions", "id",
                    "submitted_at", List.of("id", "operation_id", "classroom_id", "assignment_id", "user_id",
                    "attempt_number", "status", "result_hash", "error_code", "client_completed_at", "submitted_at"));
                case ADMIN_AUDIT -> new RetentionSpec(category, "admin_audit", "id", "created_at",
                    List.of("id", "actor_user_id", "action", "target_type", "target_id", "result", "reason_code",
                        "correlation_id", "created_at"));
                case EXPORT_AUDIT -> new RetentionSpec(category, "export_audit", "id", "created_at",
                    List.of("id", "user_id", "classroom_id", "row_count", "created_at", "assignment_id",
                        "export_type", "filter_summary"));
            };
        }

        private int minimumRetentionDays(RetentionCategory category) {
            return switch (category) {
                case SYNC_EVENTS -> 180;
                case ASSIGNMENT_SUBMISSIONS, ADMIN_AUDIT, EXPORT_AUDIT -> 365;
            };
        }

        private void validateUuid(String value, String name) {
            if (value == null || !value.matches("[a-fA-F0-9-]{36}")) {
                throw new IllegalArgumentException(name + " must be a UUID");
            }
        }

        private AdminUserSummary adminUser(Connection connection, String userId) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                "select id,email,display_name,disabled,created_at from users where id=?")) {
                statement.setString(1, userId);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new IllegalArgumentException("User was not found");
                    return adminUser(connection, row);
                }
            }
        }

        private AdminUserSummary adminUser(Connection connection, ResultSet row) throws SQLException {
            Set<UserRole> roles = new java.util.HashSet<>();
            try (PreparedStatement roleQuery = connection.prepareStatement(
                "select role from user_roles where user_id=? order by role")) {
                roleQuery.setString(1, row.getString("id"));
                try (ResultSet roleRows = roleQuery.executeQuery()) {
                    while (roleRows.next()) roles.add(UserRole.valueOf(roleRows.getString("role")));
                }
            }
            return new AdminUserSummary(row.getString("id"), row.getString("email"), row.getString("display_name"),
                roles, row.getInt("disabled") != 0, Instant.parse(row.getString("created_at")));
        }

        private int activeAdminCount(Connection connection) throws SQLException {
            return count(connection, "select count(*) from users u join user_roles r on r.user_id=u.id "
                + "where u.disabled=0 and r.role='ADMIN'");
        }

        private void revokeAllSessions(Connection connection, String userId) throws SQLException {
            Instant now = Instant.now();
            for (String table : List.of("access_tokens", "refresh_tokens")) {
                try (PreparedStatement statement = connection.prepareStatement(
                    "update " + table + " set revoked_at=? where user_id=? and revoked_at is null")) {
                    statement.setString(1, now.toString());
                    statement.setString(2, userId);
                    statement.executeUpdate();
                }
            }
        }

        private String validateReasonCode(String reasonCode) {
            if (reasonCode == null || !reasonCode.matches("[A-Z0-9_]{3,64}")) {
                throw new IllegalArgumentException("reasonCode must contain only A-Z, 0-9, or underscore");
            }
            return reasonCode;
        }

        private void audit(Connection connection, String actorUserId, String action, String targetType,
                           String targetId, String result, String reasonCode) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                "insert into admin_audit(id,actor_user_id,action,target_type,target_id,result,reason_code,"
                    + "correlation_id,created_at) values(?,?,?,?,?,?,?,?,?)")) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, actorUserId);
                statement.setString(3, action);
                statement.setString(4, targetType);
                statement.setString(5, targetId);
                statement.setString(6, result);
                statement.setString(7, reasonCode);
                statement.setString(8, UUID.randomUUID().toString());
                statement.setString(9, Instant.now().toString());
                statement.executeUpdate();
            }
        }

        private int bind(PreparedStatement statement, List<String> parameters) throws SQLException {
            int index = 1;
            for (String parameter : parameters) statement.setString(index++, parameter);
            return index;
        }
        com.sqlteacher.application.collaboration.ClassLearningSummary classLearningSummary(AuthenticatedUser actor,String classroomId){requireTeacher(actor,classroomId);int students=0;int active=0;int events=0;int success=0;try(Connection c=open();PreparedStatement s=c.prepareStatement("select m.user_id,e.payload_json from classroom_members m left join sync_events e on e.user_id=m.user_id where m.classroom_id=? and m.role='STUDENT'")){s.setString(1,classroomId);java.util.Set<String> seenStudents=new java.util.HashSet<>();java.util.Set<String> activeStudents=new java.util.HashSet<>();try(ResultSet r=s.executeQuery()){while(r.next()){String userId=r.getString(1);seenStudents.add(userId);String payload=r.getString(2);if(payload==null)continue;events++;activeStudents.add(userId);try{if(JSON.readTree(payload).path("successful").asBoolean(false))success++;}catch(IOException ignored){}}}students=seenStudents.size();active=activeStudents.size();}catch(SQLException e){throw database(e);}return new com.sqlteacher.application.collaboration.ClassLearningSummary(classroomId,students,active,events,success,Instant.now());}
        String exportClassLearningCsv(AuthenticatedUser actor,String classroomId){requireTeacher(actor,classroomId);StringBuilder csv=new StringBuilder("\uFEFFstudent_email,event_type,occurred_at,successful\r\n");int rows=0;try(Connection c=open();PreparedStatement s=c.prepareStatement("select u.email,e.event_type,e.occurred_at,e.payload_json from classroom_members m join users u on u.id=m.user_id join sync_events e on e.user_id=m.user_id where m.classroom_id=? and m.role='STUDENT' order by e.occurred_at")){s.setString(1,classroomId);try(ResultSet r=s.executeQuery()){while(r.next()){boolean successful=false;try{successful=JSON.readTree(r.getString(4)).path("successful").asBoolean(false);}catch(IOException ignored){}csv.append(csvCell(r.getString(1))).append(',').append(csvCell(r.getString(2))).append(',').append(csvCell(r.getString(3))).append(',').append(successful).append("\r\n");rows++;}}try(PreparedStatement audit=c.prepareStatement("insert into export_audit(id,user_id,classroom_id,row_count,created_at) values(?,?,?,?,?)")){audit.setString(1,UUID.randomUUID().toString());audit.setString(2,actor.id());audit.setString(3,classroomId);audit.setInt(4,rows);audit.setString(5,Instant.now().toString());audit.executeUpdate();}}catch(SQLException e){throw database(e);}return csv.toString();}
        private String csvCell(String value){String normalized=value==null?"":value;if(!normalized.isEmpty()&&"=+-@".indexOf(normalized.charAt(0))>=0)normalized="'"+normalized;return "\""+normalized.replace("\"","\"\"")+"\"";}
        private ClassAssignment assignment(Connection connection, String classroomId, String assignmentId)
            throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                "select " + ASSIGNMENT_COLUMNS + " from class_assignments where id=? and classroom_id=?")) {
                statement.setString(1, assignmentId);
                statement.setString(2, classroomId);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new IllegalArgumentException("Assignment was not found");
                    return assignment(row, classroomId);
                }
            }
        }

        private ClassAssignment assignment(ResultSet row, String classroomId) throws SQLException {
            String dueAt = row.getString("due_at");
            String publishedAt = row.getString("published_at");
            return new ClassAssignment(
                row.getString("id"), classroomId, row.getString("exercise_id"), row.getString("title"),
                Instant.parse(row.getString("created_at")), AssignmentStatus.valueOf(row.getString("status")),
                dueAt == null ? null : Instant.parse(dueAt), Instant.parse(row.getString("updated_at")),
                row.getString("description"), publishedAt == null ? null : Instant.parse(publishedAt),
                row.getString("copied_from_assignment_id"), row.getLong("version")
            );
        }

        private void validateAssignmentDetails(String exerciseId, String title, String description, Instant dueAt) {
            if (exerciseId == null || exerciseId.isBlank()) {
                throw new IllegalArgumentException("exerciseId must not be blank");
            }
            if (title == null || title.isBlank() || title.length() > 160) {
                throw new IllegalArgumentException("title must be 1 to 160 characters");
            }
            if (description != null && description.length() > 2_000) {
                throw new IllegalArgumentException("description must contain at most 2000 characters");
            }
            if (dueAt != null && !dueAt.isAfter(Instant.now())) {
                throw new IllegalArgumentException("dueAt must be in the future");
            }
        }

        private String normalizeDescription(String description) {
            return description == null ? "" : description.trim();
        }

        private void requireEditable(ClassAssignment assignment) {
            if (assignment.status() == AssignmentStatus.ARCHIVED) {
                throw new IllegalArgumentException("Archived assignments cannot be edited");
            }
        }

        private void requireVersion(ClassAssignment assignment, long expectedVersion) {
            if (expectedVersion < 1) throw new IllegalArgumentException("expectedVersion must be positive");
            if (assignment.version() != expectedVersion) throw new AssignmentVersionConflictException(assignment);
        }

        private void requireUpdated(PreparedStatement statement, Connection connection, String classroomId,
                                    String assignmentId) throws SQLException {
            if (statement.executeUpdate() != 1) {
                throw new AssignmentVersionConflictException(assignment(connection, classroomId, assignmentId));
            }
        }

        private AuthenticatedUser authenticateData(String token) {
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("select user_id from access_tokens where token_hash=? and expires_at>? and revoked_at is null")) {
                statement.setBytes(1, tokenHash(token)); statement.setString(2, Instant.now().toString());
                try (ResultSet result = statement.executeQuery()) { if (!result.next()) throw new SecurityException("invalid token"); return user(result.getString(1)); }
            } catch (SQLException error) { throw database(error); }
        }
        private void revoke(Connection c,String table,String token)throws SQLException{try(PreparedStatement s=c.prepareStatement("update "+table+" set revoked_at=? where token_hash=? and revoked_at is null")){s.setString(1,Instant.now().toString());s.setBytes(2,tokenHash(token));s.executeUpdate();}}
        private SessionData issue(AuthenticatedUser user) { try(Connection c=open()){return issue(c,user);}catch(SQLException e){throw database(e);} }
        private SessionData issue(Connection c, AuthenticatedUser user) throws SQLException { String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(TOKEN_BYTES)); String refresh=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(TOKEN_BYTES)); Instant now=Instant.now(); Instant expiry=now.plus(ACCESS_TOKEN_HOURS, ChronoUnit.HOURS); Instant refreshExpiry=now.plus(REFRESH_TOKEN_DAYS, ChronoUnit.DAYS); try(PreparedStatement access=c.prepareStatement("insert into access_tokens(token_hash,user_id,expires_at,created_at) values(?,?,?,?)");PreparedStatement refreshStatement=c.prepareStatement("insert into refresh_tokens(token_hash,user_id,expires_at,created_at) values(?,?,?,?)")){access.setBytes(1,tokenHash(token));access.setString(2,user.id());access.setString(3,expiry.toString());access.setString(4,now.toString());access.executeUpdate();refreshStatement.setBytes(1,tokenHash(refresh));refreshStatement.setString(2,user.id());refreshStatement.setString(3,refreshExpiry.toString());refreshStatement.setString(4,now.toString());refreshStatement.executeUpdate();} return new SessionData(token,expiry,user,refresh); }
        private AuthenticatedUser user(String id) { try(Connection c=open(); PreparedStatement s=c.prepareStatement("select id,email,display_name from users where id=? and disabled=0")){s.setString(1,id);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SecurityException("unknown user");Set<UserRole> roles=new java.util.HashSet<>();try(PreparedStatement rs=c.prepareStatement("select role from user_roles where user_id=?")){rs.setString(1,id);try(ResultSet rr=rs.executeQuery()){while(rr.next())roles.add(UserRole.valueOf(rr.getString(1)));}}return new AuthenticatedUser(r.getString(1),r.getString(2),r.getString(3),roles);}}catch(SQLException e){throw database(e);} }
        private String userIdByEmail(String email){String normalized=validateEmail(email);try(Connection c=open();PreparedStatement s=c.prepareStatement("select id from users where email=? and disabled=0")){s.setString(1,normalized);try(ResultSet r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("User email was not found");return r.getString(1);}}catch(SQLException e){throw database(e);}}
        private Classroom classroom(String id) {
            try (Connection connection = open();
                 PreparedStatement classroomStatement = connection.prepareStatement(
                     "select name, created_at from classrooms where id=?")) {
                classroomStatement.setString(1, id);
                try (ResultSet classroom = classroomStatement.executeQuery()) {
                    if (!classroom.next()) {
                        throw new IllegalArgumentException("Classroom not found");
                    }
                    List<Member> members = new ArrayList<>();
                    try (PreparedStatement memberStatement = connection.prepareStatement(
                        "select user_id, role from classroom_members where classroom_id=?")) {
                        memberStatement.setString(1, id);
                        try (ResultSet memberRows = memberStatement.executeQuery()) {
                            while (memberRows.next()) {
                                members.add(new Member(memberRows.getString(1), UserRole.valueOf(memberRows.getString(2))));
                            }
                        }
                    }
                    return new Classroom(id, classroom.getString(1), Instant.parse(classroom.getString(2)), members);
                }
            } catch (SQLException error) {
                throw database(error);
            }
        }
        private void requireTeacher(AuthenticatedUser actor,String classId){if(actor.hasRole(UserRole.ADMIN))return;try(Connection c=open();PreparedStatement s=c.prepareStatement("select 1 from classroom_members where classroom_id=? and user_id=? and role='TEACHER'")){s.setString(1,classId);s.setString(2,actor.id());try(ResultSet r=s.executeQuery()){if(!r.next())throw new SecurityException("not classroom teacher");}}catch(SQLException e){throw database(e);}}
        private boolean isTeacher(AuthenticatedUser actor,String classId){if(actor.hasRole(UserRole.ADMIN))return true;try(Connection c=open();PreparedStatement s=c.prepareStatement("select 1 from classroom_members where classroom_id=? and user_id=? and role='TEACHER'")){s.setString(1,classId);s.setString(2,actor.id());try(ResultSet r=s.executeQuery()){return r.next();}}catch(SQLException e){throw database(e);}}
        private void requireMember(AuthenticatedUser actor,String classId){if(actor.hasRole(UserRole.ADMIN))return;try(Connection c=open();PreparedStatement s=c.prepareStatement("select 1 from classroom_members where classroom_id=? and user_id=?")){s.setString(1,classId);s.setString(2,actor.id());try(ResultSet r=s.executeQuery()){if(!r.next())throw new SecurityException("not classroom member");}}catch(SQLException e){throw database(e);}}
        private void requireAdmin(AuthenticatedUser actor) {
            if (!actor.hasRole(UserRole.ADMIN)) throw new SecurityException("administrator role required");
        }
        private void requireStudent(AuthenticatedUser actor, String classId) {
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "select 1 from classroom_members where classroom_id=? and user_id=? and role='STUDENT'")) {
                statement.setString(1, classId);
                statement.setString(2, actor.id());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new SecurityException("student classroom membership required");
                }
            } catch (SQLException error) { throw database(error); }
        }
        private Connection open() throws SQLException {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("pragma foreign_keys=on");
                statement.executeUpdate("pragma busy_timeout=5000");
            }
            return connection;
        }
        private void initialize() throws SQLException {
            try (Connection connection = open(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("pragma foreign_keys=on");
                statement.executeUpdate("create table if not exists users(id text primary key,email text not null unique,"
                    + "display_name text not null,password_hash blob not null,password_salt blob not null,"
                    + "disabled integer not null default 0,created_at text not null)");
                statement.executeUpdate("create table if not exists user_roles(user_id text not null references users(id),"
                    + "role text not null check(role in ('ADMIN','TEACHER','STUDENT')),primary key(user_id,role))");
                statement.executeUpdate("create table if not exists access_tokens(token_hash blob primary key,"
                    + "user_id text not null references users(id),expires_at text not null,created_at text not null,revoked_at text)");
                statement.executeUpdate("create table if not exists refresh_tokens(token_hash blob primary key,"
                    + "user_id text not null references users(id),expires_at text not null,created_at text not null,revoked_at text)");
                statement.executeUpdate("create table if not exists classrooms(id text primary key,name text not null,created_at text not null)");
                statement.executeUpdate("create table if not exists classroom_members(classroom_id text not null references classrooms(id),"
                    + "user_id text not null references users(id),role text not null check(role in ('TEACHER','STUDENT')),"
                    + "primary key(classroom_id,user_id))");
                statement.executeUpdate("create table if not exists class_assignments(id text primary key,"
                    + "classroom_id text not null references classrooms(id),exercise_id text not null,title text not null,"
                    + "description text not null default '',created_at text not null,status text not null default 'PUBLISHED',"
                    + "due_at text,published_at text,copied_from_assignment_id text,version integer not null default 1,"
                    + "updated_at text not null)");
                statement.executeUpdate("create table if not exists sync_events(version integer primary key autoincrement,"
                    + "user_id text not null references users(id),event_id text not null,event_type text not null,"
                    + "payload_json text not null,occurred_at text not null,unique(user_id,event_id))");
                statement.executeUpdate("create table if not exists assignment_submissions(id text primary key,"
                    + "operation_id text not null,classroom_id text not null references classrooms(id),"
                    + "assignment_id text not null references class_assignments(id),user_id text not null references users(id),"
                    + "attempt_number integer not null,status text not null check(status in ('PASSED','FAILED')),"
                    + "result_hash text not null,error_code text,client_completed_at text,submitted_at text not null,"
                    + "unique(user_id,operation_id),unique(assignment_id,user_id,attempt_number))");
                statement.executeUpdate("create table if not exists admin_audit(id text primary key,"
                    + "actor_user_id text references users(id),action text not null,target_type text not null,"
                    + "target_id text,result text not null,reason_code text,correlation_id text not null,"
                    + "created_at text not null)");
                statement.executeUpdate("create table if not exists export_audit(id text primary key,"
                    + "user_id text not null references users(id),classroom_id text not null references classrooms(id),"
                    + "row_count integer not null,created_at text not null,assignment_id text,"
                    + "export_type text not null default 'CLASS_ANALYTICS',filter_summary text)");
                statement.executeUpdate("create table if not exists retention_jobs(id text primary key,"
                    + "category text not null,cutoff text not null,preview_count integer not null,"
                    + "affected_count integer not null default 0,status text not null,confirmation_hash blob not null,"
                    + "expires_at text not null,backup_reference text,safety_backup text,created_at text not null,executed_at text,"
                    + "restored_at text,actor_user_id text not null references users(id))");
                statement.executeUpdate("create table if not exists retention_archive(job_id text not null "
                    + "references retention_jobs(id),category text not null,row_key text not null,payload_json text not null,"
                    + "archived_at text not null,primary key(job_id,row_key))");
                addColumnIfMissing(statement, "class_assignments", "status text not null default 'PUBLISHED'");
                addColumnIfMissing(statement, "class_assignments", "due_at text");
                addColumnIfMissing(statement, "class_assignments", "updated_at text");
                addColumnIfMissing(statement, "class_assignments", "description text not null default ''");
                addColumnIfMissing(statement, "class_assignments", "published_at text");
                addColumnIfMissing(statement, "class_assignments", "copied_from_assignment_id text");
                addColumnIfMissing(statement, "class_assignments", "version integer not null default 1");
                addColumnIfMissing(statement, "export_audit", "assignment_id text");
                addColumnIfMissing(statement, "export_audit", "export_type text not null default 'CLASS_ANALYTICS'");
                addColumnIfMissing(statement, "export_audit", "filter_summary text");
                addColumnIfMissing(statement, "retention_jobs", "safety_backup text");
                statement.executeUpdate("update class_assignments set updated_at=created_at where updated_at is null");
                statement.executeUpdate("update class_assignments set description='' where description is null");
                statement.executeUpdate("update class_assignments set version=1 where version is null or version<1");
                statement.executeUpdate("update class_assignments set published_at=created_at "
                    + "where published_at is null and status<>'DRAFT'");
                statement.executeUpdate("create index if not exists idx_assignments_class_status "
                    + "on class_assignments(classroom_id,status,created_at desc)");
                statement.executeUpdate("create index if not exists idx_submissions_assignment_user "
                    + "on assignment_submissions(assignment_id,user_id,submitted_at)");
                statement.executeUpdate("create index if not exists idx_admin_audit_action_time "
                    + "on admin_audit(action,created_at desc)");
                statement.executeUpdate("create index if not exists idx_retention_jobs_created "
                    + "on retention_jobs(created_at desc)");
            }
        }
        private void addColumnIfMissing(Statement statement,String table,String definition)throws SQLException{try{statement.executeUpdate("alter table "+table+" add column "+definition);}catch(SQLException error){if(!error.getMessage().toLowerCase(Locale.ROOT).contains("duplicate column"))throw error;}}
        private byte[] bytes(int count){byte[] value=new byte[count];random.nextBytes(value);return value;} private byte[] hash(char[] password,byte[] salt){try{KeySpec spec=new PBEKeySpec(password,salt,PBKDF2_ITERATIONS,HASH_BITS);return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}catch(GeneralSecurityException e){throw new IllegalStateException("Password hashing unavailable",e);}} private byte[] tokenHash(String token){try{return java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));}catch(GeneralSecurityException e){throw new IllegalStateException(e);}} private static boolean constantTimeEquals(byte[] a,byte[] b){return java.security.MessageDigest.isEqual(a,b);} private static String validateEmail(String e){if(e==null||!e.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")||e.length()>254)throw new IllegalArgumentException("email must be valid");return e.trim().toLowerCase(Locale.ROOT);} private static void validatePassword(char[] p){if(p==null||p.length<12||p.length>128)throw new IllegalArgumentException("password must contain 12 to 128 characters");} private static IllegalStateException database(SQLException e){return new IllegalStateException("Cloud database operation failed",e);} private static CloudAuthenticationService.Session toSession(SessionData s){return new CloudAuthenticationService.Session(s.token(),s.expiresAt(),s.user(),s.refreshToken());}
        private record RetentionSpec(RetentionCategory category, String table, String keyColumn,
                                     String timeColumn, List<String> columns) { }
        private record RetentionJobState(RetentionCategory category, Instant cutoff, int previewRows,
                                         int affectedRows, String status, byte[] confirmationHash,
                                         Instant expiresAt) { }
    }
    private record SessionData(String token, Instant expiresAt, AuthenticatedUser user, String refreshToken) { }
}
