package com.sqlteacher.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sqlteacher.domain.SqlTeacherException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.sqlteacher.application.collaboration.CloudArtifactSyncItem;
import com.sqlteacher.application.collaboration.ClassAssignment;
import com.sqlteacher.application.collaboration.RetentionCategory;
import com.sqlteacher.application.collaboration.RetentionJob;
import com.sqlteacher.application.collaboration.RetentionPreview;
import com.sqlteacher.application.collaboration.UserRole;
import com.sqlteacher.application.collaboration.ContentStatus;
import com.sqlteacher.application.collaboration.FeedbackStatus;
import com.sqlteacher.application.planning.ObjectiveResourceType;
import com.sqlteacher.application.planning.StudyPlanActionState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
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

/**
 * Small deployable cloud API for v1.2. It intentionally exposes only account and class APIs;
 * desktop database credentials and BYO-AI keys never cross this boundary.
 */
public final class SqlTeacherCloudServer {
    private static final Logger log = LoggerFactory.getLogger(SqlTeacherCloudServer.class);
    private static final ObjectMapper JSON = CloudJsonStoreSupport.mapper();
    private static final Duration ONE_HOUR = Duration.ofHours(1);
    private static final int REGISTER_MAX_PER_EMAIL_PER_HOUR = 10;
    private static final int REGISTER_MAX_PER_IP_PER_HOUR = 20;
    private static final int RESET_MAX_PER_EMAIL_PER_HOUR = 3;
    private static final int RESET_MAX_PER_IP_PER_HOUR = 10;
    private static final int SYNC_UPLOAD_MAX_BYTES = 1024 * 1024;
    private static final int BANK_PUBLISH_MAX_BYTES = 2 * 1024 * 1024;

    private final CloudAuthenticationStore authStore;
    private final CloudClassroomStore classroomStore;
    private final CloudAdministrationStore adminStore;
    private final V14CloudStore v14Store;
    private final V19CloudStore v19Store;
    private final V110SupportStore v110SupportStore;
    private final V111AccountStore v111AccountStore;
    private final V31ExerciseBankStore v31BankStore;
    private final CloudKnowledgeIndexService knowledgeIndex;
    private java.util.concurrent.ScheduledExecutorService retentionPurgeExecutor;
    private final AuthRateLimiter authRateLimiter = new AuthRateLimiter();
    private final HttpServer server;

    SqlTeacherCloudServer(Path databasePath, int port) throws IOException, SQLException {
        this(databasePath, port, databasePath.getParent() == null ? Path.of(".") : databasePath.getParent());
    }

    SqlTeacherCloudServer(Path databasePath, int port, Path mailDirectory) throws IOException, SQLException {
        this(databasePath, port, mailDirectory, Clock.systemUTC());
    }

    /** Test overload: fixes the classroom-store time source so deadline behavior is deterministic. */
    SqlTeacherCloudServer(Path databasePath, int port, Clock clock) throws IOException, SQLException {
        this(databasePath, port, databasePath.getParent() == null ? Path.of(".") : databasePath.getParent(), clock);
    }

    SqlTeacherCloudServer(Path databasePath, int port, Path mailDirectory, Clock clock)
            throws IOException, SQLException {
        this.authStore = new CloudAuthenticationStore(databasePath);
        this.classroomStore = new CloudClassroomStore(databasePath, clock);
        this.adminStore = new CloudAdministrationStore(databasePath);
        this.v14Store = new V14CloudStore(databasePath);
        this.v19Store = new V19CloudStore(databasePath);
        this.v110SupportStore = new V110SupportStore(databasePath);
        // v3.8.0 ACC-S1：配置 SQLTEACHER_CLOUD_SMTP_* 环境变量后走真实 SMTP 通道；
        // 未配置时保持文件 outbox 行为不变（本地与测试环境零依赖）。
        this.v111AccountStore = new V111AccountStore(databasePath,
            SmtpMailSender.fromEnvironment(new FileMailSender(mailDirectory)));
        this.v31BankStore = new V31ExerciseBankStore(databasePath);
        this.knowledgeIndex = CloudKnowledgeIndexService.fromEnvironment(v14Store);
        String bootstrapEmail = System.getenv("SQLTEACHER_CLOUD_BOOTSTRAP_ADMIN_EMAIL");
        String bootstrapPassword = System.getenv("SQLTEACHER_CLOUD_BOOTSTRAP_ADMIN_PASSWORD");
        if (bootstrapEmail != null && !bootstrapEmail.isBlank()
            && bootstrapPassword != null && !bootstrapPassword.isBlank()) {
            authStore.ensureBootstrapAdmin(bootstrapEmail, bootstrapPassword.toCharArray());
        }
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext("/health", this::health);
        this.server.createContext("/api/v1/auth/register", this::register);
        this.server.createContext("/api/v1/auth/login", this::login);
        this.server.createContext("/api/v1/auth/refresh", this::refresh);
        this.server.createContext("/api/v1/auth/logout", this::logout);
        this.server.createContext("/api/v1/auth/change-password", this::changePassword);
        this.server.createContext("/api/v1/auth/request-password-reset", this::requestPasswordReset);
        this.server.createContext("/api/v1/auth/reset-password", this::resetPassword);
        this.server.createContext("/api/v1/app", this::appSupport);
        this.server.createContext("/api/v1/support", this::support);
        this.server.createContext("/api/v1/account", this::account);
        this.server.createContext("/api/v1/sessions", this::sessions);
        this.server.createContext("/api/v1/classes", this::classes);
        this.server.createContext("/api/v1/sync/events", this::syncEvents);
        this.server.createContext("/api/v1/admin", this::admin);
        this.server.createContext("/api/v1/bank", this::bank);
        this.server.createContext("/api/v1/v14", this::v14);
        this.server.createContext("/api/v1/v20", this::v20);
        this.server.createContext("/api/v1/v19", this::v19);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("SQLTEACHER_CLOUD_PORT", "8080"));
        Path database = Path.of(System.getenv().getOrDefault("SQLTEACHER_CLOUD_DB", "./data/cloud.db"))
            .toAbsolutePath().normalize();
        SqlTeacherCloudServer cloudServer = new SqlTeacherCloudServer(database, port);
        cloudServer.start();
        log.info("SQLTeacher cloud API started, port={}", port);
    }

    void start() {
        server.start();
        knowledgeIndex.start();
        startSyncRetentionPurge();
    }

    void stop() {
        knowledgeIndex.close();
        if (retentionPurgeExecutor != null) {
            retentionPurgeExecutor.shutdownNow();
        }
        server.stop(0);
    }

    /**
     * v3.7.0 TFB-S4: daily automatic retention for synced learning events, defaulting to a
     * 180-day window (decision point 2, 2026-09-20). Override with
     * SQLTEACHER_CLOUD_SYNC_RETENTION_DAYS; 0 disables the purge entirely.
     */
    private synchronized void startSyncRetentionPurge() {
        if (retentionPurgeExecutor != null) return;
        int days;
        try {
            days = Integer.parseInt(System.getenv().getOrDefault("SQLTEACHER_CLOUD_SYNC_RETENTION_DAYS", "180"));
        } catch (NumberFormatException badConfiguration) {
            days = 180;
        }
        if (days <= 0) {
            log.info("Automatic sync retention disabled by configuration");
            return;
        }
        final int retentionDays = days;
        retentionPurgeExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sync-retention-purge");
            thread.setDaemon(true);
            return thread;
        });
        retentionPurgeExecutor.scheduleWithFixedDelay(() -> {
            try {
                int removed = adminStore.autoPurgeSyncEvents(
                    java.time.Instant.now().minus(java.time.Duration.ofDays(retentionDays)));
                if (removed > 0) {
                    log.info("Automatic sync retention removed {} learning events older than {} days",
                        removed, retentionDays);
                }
            } catch (RuntimeException error) {
                log.info("Automatic sync retention skipped: {}", error.getClass().getSimpleName());
            }
        }, 10, 24 * 60L * 60L, java.util.concurrent.TimeUnit.SECONDS);
    }
    int port() {
        return server.getAddress().getPort();
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange);
            return;
        }
        CloudKnowledgeIndexService.Health index = knowledgeIndex.health();
        respond(exchange, 200, Map.of("status", "ok", "time", Instant.now().toString(),
            "apiVersion", "1.11", "problemReports", "available", "signedUpdates", "available",
            "knowledgeIndex", index.status(), "knowledgeIndexBacklog", index.backlog()));
    }

    private void register(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange);
            return;
        }
        try {
            Map<String, String> body = request(exchange);
            String emailKey = normalizedEmailKey(body.get("email"));
            String clientKey = clientPrincipal(exchange);
            enforceQuota("register:email:" + emailKey, REGISTER_MAX_PER_EMAIL_PER_HOUR, ONE_HOUR);
            enforceQuota("register:ip:" + clientKey, REGISTER_MAX_PER_IP_PER_HOUR, ONE_HOUR);
            SessionData session = authStore.registerData(body.get("email"), body.get("displayName"),
                password(body), body.get("deviceLabel"));
            respond(exchange, 201, sessionResponse(session));
        } catch (AuthRateLimiter.RateLimitedException error) { respondRateLimited(exchange, error); }
        catch (IllegalArgumentException error) { respond(exchange, 400, errorResponse("INVALID_REQUEST", error.getMessage())); }
        catch (SecurityException error) { respond(exchange, 409, errorResponse("ACCOUNT_EXISTS", "This email is already registered.")); }
        catch (RuntimeException error) {
            logUnexpectedFailure("registration", error);
            respond(exchange, 500, errorResponse("SERVER_ERROR", "Registration failed."));
        }
        finally { clearPassword(exchange); }
    }

    private void login(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange);
            return;
        }
        String failureKey = "";
        try {
            Map<String, String> body = request(exchange);
            failureKey = "login:" + normalizedEmailKey(body.get("email"));
            authRateLimiter.checkLocked(failureKey);
            SessionData session = authStore.loginData(body.get("email"), password(body), body.get("deviceLabel"));
            authRateLimiter.clearFailures(failureKey);
            respond(exchange, 200, sessionResponse(session));
        } catch (AuthRateLimiter.RateLimitedException error) { respondRateLimited(exchange, error); }
        catch (IllegalArgumentException error) { respond(exchange, 400, errorResponse("INVALID_REQUEST", error.getMessage())); }
        catch (SecurityException error) {
            authRateLimiter.recordFailure(failureKey);
            respond(exchange, 401, errorResponse("LOGIN_FAILED", "Email or password is incorrect."));
        }
        catch (RuntimeException error) {
            logUnexpectedFailure("login", error);
            respond(exchange, 500, errorResponse("SERVER_ERROR", "Login failed."));
        }
        finally { clearPassword(exchange); }
    }

    private void logout(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange);
            return;
        }
        try {
            Map<String, String> body = optionalRequest(exchange);
            authStore.logout(token(exchange), body.get("refreshToken"));
            exchange.sendResponseHeaders(204, -1);
        } catch (SecurityException error) { respond(exchange, 401, errorResponse("UNAUTHORIZED", "Login is required.")); }
    }

    private void refresh(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange);
            return;
        }
        String failureKey = "refresh:" + clientPrincipal(exchange);
        try {
            Map<String, String> body = request(exchange);
            authRateLimiter.checkLocked(failureKey);
            SessionData session = authStore.refreshData(body.get("refreshToken"), body.get("deviceLabel"));
            authRateLimiter.clearFailures(failureKey);
            respond(exchange, 200, sessionResponse(session));
        } catch (AuthRateLimiter.RateLimitedException error) { respondRateLimited(exchange, error); }
        catch (IllegalArgumentException error) { respond(exchange, 400, errorResponse("INVALID_REQUEST", error.getMessage())); }
        catch (SecurityException error) {
            authRateLimiter.recordFailure(failureKey);
            respond(exchange, 401, errorResponse("REFRESH_FAILED", "Refresh token is invalid or expired."));
        }
        catch (RuntimeException error) {
            logUnexpectedFailure("session refresh", error);
            respond(exchange, 500, errorResponse("SERVER_ERROR", "Session refresh failed."));
        }
    }

    private void requestPasswordReset(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange);
            return;
        }
        try {
            Map<String, String> body = request(exchange);
            String emailKey = normalizedEmailKey(body.get("email"));
            String clientKey = clientPrincipal(exchange);
            enforceQuota("reset:email:" + emailKey, RESET_MAX_PER_EMAIL_PER_HOUR, ONE_HOUR);
            enforceQuota("reset:ip:" + clientKey, RESET_MAX_PER_IP_PER_HOUR, ONE_HOUR);
            v111AccountStore.requestPasswordReset(body.get("email"));
            respond(exchange, 200, Map.of("status", "ok"));
        } catch (AuthRateLimiter.RateLimitedException error) { respondRateLimited(exchange, error); }
        catch (RuntimeException error) {
            logUnexpectedFailure("password reset request", error);
            respond(exchange, 500, errorResponse("SERVER_ERROR", "Password reset request failed."));
        }
    }

    private void resetPassword(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange);
            return;
        }
        try {
            Map<String, String> body = request(exchange);
            // v3.8.0 ACC-S2：6 位验证码路径为主；既有 token 入参 additive 保留。
            if (body.get("code") != null && !body.get("code").isBlank()) {
                v111AccountStore.resetPassword(body.get("email"), body.get("code"), password(body, "newPassword"));
            } else {
                v111AccountStore.resetPassword(body.get("token"), password(body, "newPassword"));
            }
            respond(exchange, 200, Map.of("status", "ok"));
        } catch (IllegalArgumentException error) { respond(exchange, 400, errorResponse("INVALID_REQUEST", error.getMessage())); }
        catch (RuntimeException error) {
            logUnexpectedFailure("password reset", error);
            respond(exchange, 500, errorResponse("SERVER_ERROR", "Password reset failed."));
        }
        finally { clearPassword(exchange); }
    }

    private void sessions(HttpExchange exchange) throws IOException {
        try {
            AuthenticatedUser actor = authStore.authenticate(token(exchange));
            String path = exchange.getRequestURI().getPath();
            if ("/api/v1/sessions".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, Map.of("sessions", v111AccountStore.listSessions(actor.id())));
                return;
            }
            String[] segments = path.split("/");
            if (segments.length == 6 && "revoke".equals(segments[5]) && "POST".equals(exchange.getRequestMethod())) {
                v111AccountStore.revokeSession(actor.id(), segments[4], token(exchange));
                respond(exchange, 200, Map.of("status", "ok"));
                return;
            }
            respond(exchange, 404, errorResponse("NOT_FOUND", "Sessions endpoint not found."));
        } catch (SecurityException error) { respond(exchange, 401, errorResponse("UNAUTHORIZED", "Login is required.")); }
        catch (IllegalArgumentException error) { respond(exchange, 400, errorResponse("INVALID_REQUEST", error.getMessage())); }
        catch (RuntimeException error) {
            logUnexpectedFailure("sessions", error);
            respond(exchange, 500, errorResponse("SERVER_ERROR", "Session operation failed."));
        }
    }

    private void account(HttpExchange exchange) throws IOException {
        try {
            AuthenticatedUser actor = authStore.authenticate(token(exchange));
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("/api/v1/account/export".equals(path) && "POST".equals(method)) {
                respond(exchange, 202, v111AccountStore.requestAccountExport(actor.id()));
                return;
            }
            if (path.startsWith("/api/v1/account/export/") && "GET".equals(method)) {
                String taskId = path.substring("/api/v1/account/export/".length());
                respond(exchange, 200, Map.of("payload", v111AccountStore.getAccountExport(actor.id(), taskId)));
                return;
            }
            if ("/api/v1/account/delete".equals(path) && "POST".equals(method)) {
                respond(exchange, 202, v111AccountStore.requestAccountDeletion(actor.id()));
                return;
            }
            if ("/api/v1/account/delete".equals(path) && "DELETE".equals(method)) {
                respond(exchange, 200, v111AccountStore.cancelAccountDeletion(actor.id()));
                return;
            }
            if ("/api/v1/account/delete".equals(path) && "GET".equals(method)) {
                respond(exchange, 200, v111AccountStore.getAccountDeletionStatus(actor.id()));
                return;
            }
            if ("/api/v1/account/bind-email".equals(path) && "POST".equals(method)) {
                Map<String, String> body = request(exchange);
                v111AccountStore.requestEmailVerification(actor.id(), body.get("email"));
                respond(exchange, 200, Map.of("status", "ok"));
                return;
            }
            if ("/api/v1/account/verify-email".equals(path) && "POST".equals(method)) {
                Map<String, String> body = request(exchange);
                // v3.8.0 ACC-S2：验证码化确认（6 位码），码在请求时按账号签发并哈希存储。
                v111AccountStore.confirmEmailVerification(actor.id(), body.get("code"));
                respond(exchange, 200, Map.of("status", "ok"));
                return;
            }
            // v3.8.0 ACC-S3：自助修改显示名（邮箱走 bind-email 换绑流，密码走 change-password）。
            if ("/api/v1/account/profile".equals(path) && ("PATCH".equals(method) || "POST".equals(method))) {
                Map<String, String> body = request(exchange);
                v111AccountStore.updateProfile(actor.id(), body.get("displayName"));
                respond(exchange, 200, Map.of("status", "ok"));
                return;
            }
            // v3.8.0 ACC-S4（决策点 1 方案 B）：教师升级码兑换（登录态自助，限流 5 次/账号/时）。
            if ("/api/v1/account/role-codes/redeem".equals(path) && "POST".equals(method)) {
                Map<String, String> body = request(exchange);
                enforceQuota("role-redeem:" + actor.id(), 5, Duration.ofHours(1));
                adminStore.redeemRoleCode(actor, body.get("code"));
                respond(exchange, 200, Map.of("status", "ok"));
                return;
            }
            respond(exchange, 404, errorResponse("NOT_FOUND", "Account endpoint not found."));
        } catch (SecurityException error) { respond(exchange, 401, errorResponse("UNAUTHORIZED", "Login is required.")); }
        catch (IllegalArgumentException error) { respond(exchange, 400, errorResponse("INVALID_REQUEST", error.getMessage())); }
        catch (RuntimeException error) {
            logUnexpectedFailure("account", error);
            respond(exchange, 500, errorResponse("SERVER_ERROR", "Account operation failed."));
        }
    }

    private void classes(HttpExchange exchange) throws IOException {
        try {
            AuthenticatedUser actor = authStore.authenticate(token(exchange));
            String path = exchange.getRequestURI().getPath();
            if ("/api/v1/classes".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, Map.of("classes", classroomStore.listVisibleTo(actor)));
                return;
            }
            if ("/api/v1/classes".equals(path) && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                respond(exchange, 201, classroomStore.create(actor, body.get("name")));
                return;
            }
            String[] segments = path.split("/");
            // v3.4.1 CLS-2：学生凭班级码自助加入。字面量 join 必须先于 {id} 段匹配；
            // 未知码与其他非法请求统一 404，不泄露班级码存在性；限流防短码枚举。
            if (segments.length == 5 && "join".equals(segments[4]) && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                enforceQuota("class-join:" + actor.id(), 10, Duration.ofMinutes(1));
                try {
                    respond(exchange, 200, classroomStore.joinByCode(actor, body.get("code")));
                } catch (IllegalArgumentException invalidCode) {
                    respond(exchange, 404, errorResponse("JOIN_CODE_INVALID", "班级码无效"));
                }
                return;
            }
            if (segments.length == 6 && "members".equals(segments[5]) && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                UserRole role = UserRole.valueOf(body.get("role").toUpperCase(Locale.ROOT));
                String userId = body.get("userId");
                if ((userId == null || userId.isBlank()) && body.get("email") != null) userId = authStore.userIdByEmail(body.get("email"));
                respond(exchange, 200, classroomStore.addMember(actor, segments[4], userId, role));
                return;
            }
            if (segments.length == 6 && "roster".equals(segments[5]) && "GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, Map.of("members", classroomStore.classRoster(actor, segments[4])));
                return;
            }
            if (segments.length == 6 && "join-code".equals(segments[5]) && "GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, Map.of("joinCode", classroomStore.joinCode(actor, segments[4])));
                return;
            }
            if (segments.length == 7 && "join-code".equals(segments[5]) && "rotate".equals(segments[6])
                && "POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, Map.of("joinCode", classroomStore.rotateJoinCode(actor, segments[4])));
                return;
            }
            if (segments.length == 6 && "assignments".equals(segments[5])) {
                if ("GET".equals(exchange.getRequestMethod())) {
                    String requestedStatus = queryValue(exchange.getRequestURI().getRawQuery(), "status");
                    AssignmentStatus statusFilter = requestedStatus == null ? null : assignmentStatus(requestedStatus, null);
                    respond(exchange, 200, Map.of("assignments",
                        classroomStore.listAssignments(actor, segments[4], statusFilter)));
                    return;
                }
                if ("POST".equals(exchange.getRequestMethod())) {
                    Map<String, String> body = request(exchange);
                    AssignmentStatus status = assignmentStatus(body.get("status"), AssignmentStatus.PUBLISHED);
                    respond(exchange, 201, classroomStore.createAssignment(actor, segments[4], body.get("exerciseId"),
                        body.get("title"), body.get("description"), instantOrNull(body.get("dueAt")), status));
                    return;
                }
            }
            if (segments.length == 6 && "analytics".equals(segments[5]) && "GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, classroomStore.classLearningSummary(actor, segments[4]));
                return;
            }
            if (segments.length == 7 && "analytics".equals(segments[5]) && "export".equals(segments[6])
                && "GET".equals(exchange.getRequestMethod())) {
                respondCsv(exchange, classroomStore.exportClassLearningCsv(actor, segments[4]));
                return;
            }
            // v3.7.0 TFB-S2：班级学情总览（汇总 + 7 日活跃 + 类型分布 + 14 日趋势）。
            // 独立端点而非扩展 /analytics 响应，保证旧桌面客户端解析不受新增字段影响。
            if (segments.length == 7 && "analytics".equals(segments[5]) && "overview".equals(segments[6])
                && "GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, classroomStore.classLearningOverview(actor, segments[4]));
                return;
            }
            // v3.7.0 TFB-S1：教师分页读取本班学生的学习事件明细（含审计）。
            if (segments.length == 6 && "events".equals(segments[5]) && "GET".equals(exchange.getRequestMethod())) {
                String rawQuery = exchange.getRequestURI().getRawQuery();
                long cursorValue = queryLong(rawQuery, "cursor", -1);
                long limitValue = queryLong(rawQuery, "limit", 50);
                respond(exchange, 200, classroomStore.classroomEvents(actor, segments[4],
                    queryValue(rawQuery, "studentUserId"),
                    queryValue(rawQuery, "eventType"),
                    instantOrNull(queryValue(rawQuery, "from")), instantOrNull(queryValue(rawQuery, "to")),
                    cursorValue < 0 ? null : cursorValue, (int) Math.min(limitValue, Integer.MAX_VALUE)));
                return;
            }
            if (segments.length == 8 && "assignments".equals(segments[5]) && "copy".equals(segments[7])
                && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                respond(exchange, 201, classroomStore.copyAssignment(actor, segments[4], segments[6],
                    positiveLong(body, "expectedVersion")));
                return;
            }
            if (segments.length == 7 && "assignments".equals(segments[5]) && "own-status".equals(segments[6])
                && "GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, classroomStore.listOwnPassedStatuses(actor, segments[4]));
                return;
            }
            if (segments.length == 8 && "assignments".equals(segments[5]) && "submissions".equals(segments[7])) {
                if ("POST".equals(exchange.getRequestMethod())) {
                    Map<String, String> body = request(exchange);
                    String payload = body.get("submissionPayload");
                    if (payload != null && payload.getBytes(StandardCharsets.UTF_8).length > 16_384) {
                        throw new IllegalArgumentException("submissionPayload must be at most 16384 bytes");
                    }
                    AssignmentSubmissionRequest submission = new AssignmentSubmissionRequest(
                        body.get("operationId"), requiredBoolean(body, "passed"), body.get("resultHash"),
                        body.get("errorCode"), instantOrNull(body.get("clientCompletedAt")), payload
                    );
                    AssignmentSubmission created = classroomStore.submitAssignment(actor, segments[4], segments[6], submission);
                    v14Store.recordSubmissionNotification(actor, segments[4], created);
                    respond(exchange, 201, created);
                    return;
                }
                if ("GET".equals(exchange.getRequestMethod())) {
                    respond(exchange, 200, Map.of("submissions",
                        classroomStore.listOwnSubmissions(actor, segments[4], segments[6])));
                    return;
                }
            }
            if (segments.length == 8 && "assignments".equals(segments[5]) && "analytics".equals(segments[7])
                && "GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, classroomStore.assignmentAnalytics(actor, segments[4], segments[6],
                    analyticsFilter(exchange)));
                return;
            }
            if (segments.length == 9 && "assignments".equals(segments[5]) && "analytics".equals(segments[7])
                && "export".equals(segments[8]) && "GET".equals(exchange.getRequestMethod())) {
                respondCsv(exchange, classroomStore.exportAssignmentAnalyticsCsv(actor, segments[4], segments[6],
                    analyticsFilter(exchange)), "assignment-analytics.csv");
                return;
            }
            if (segments.length == 8 && "assignments".equals(segments[5]) && "status".equals(segments[7])
                && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                respond(exchange, 200, classroomStore.changeAssignmentStatus(actor, segments[4], segments[6],
                    assignmentStatus(body.get("status"), null), positiveLong(body, "expectedVersion")));
                return;
            }
            if (segments.length == 8 && "assignments".equals(segments[5]) && "due".equals(segments[7])
                && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                respond(exchange, 200, classroomStore.setAssignmentDueAt(actor, segments[4], segments[6],
                    instantOrNull(body.get("dueAt")), positiveLong(body, "expectedVersion")));
                return;
            }
            if (segments.length == 8 && "assignments".equals(segments[5]) && "details".equals(segments[7])
                && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                respond(exchange, 200, classroomStore.updateAssignment(actor, segments[4], segments[6], body.get("title"),
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
        catch (RuntimeException error) {
            logUnexpectedFailure("classroom operation", error);
            respond(exchange, 500, errorResponse("SERVER_ERROR", "Classroom operation failed."));
        }
    }

    private void syncEvents(HttpExchange exchange) throws IOException {
        try {
            AuthenticatedUser actor = authStore.authenticate(token(exchange));
            if ("POST".equals(exchange.getRequestMethod())) {
                // v3.7.0 TFB-S4：上传限流（每账号每分钟 1 次），配合客户端 5 分钟自动同步节流。
                enforceQuota("sync-upload:" + actor.id(), 1, Duration.ofMinutes(1));
                SyncUpload upload = JSON.readValue(requestBytes(exchange, SYNC_UPLOAD_MAX_BYTES), SyncUpload.class);
                respond(exchange, 200, Map.of("accepted", classroomStore.upload(actor, upload.items())));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                long after = queryLong(exchange.getRequestURI().getRawQuery(), "afterVersion", 0);
                respond(exchange, 200, Map.of("items", classroomStore.download(actor, after)));
                return;
            }
            methodNotAllowed(exchange);
        } catch (PayloadTooLargeException error) {
            respond(exchange, 413, errorResponse("REQUEST_TOO_LARGE", "Request body is too large."));
        } catch (CloudClassroomStore.SyncItemPayloadTooLargeException error) {
            respond(exchange, 400, errorResponse("PAYLOAD_TOO_LARGE", error.getMessage()));
        } catch (AuthRateLimiter.RateLimitedException error) {
            respondRateLimited(exchange, error);
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
            AuthenticatedUser actor = authStore.authenticate(token(exchange));
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
                if ("knowledge".equals(action)) {
                    if ("GET".equals(method)) {
                        String query = queryValue(exchange.getRequestURI().getRawQuery(), "q");
                        if (query == null || query.isBlank()) {
                            respond(exchange, 200, Map.of("articles", v14Store.listKnowledge(actor, courseId)));
                        } else {
                            respond(exchange, 200, Map.of("results", knowledgeIndex.search(actor, courseId, query,
                                (int) queryLong(exchange.getRequestURI().getRawQuery(), "limit", 20))));
                        }
                        return;
                    }
                    if ("POST".equals(method)) {
                        Map<String, Object> body = objectRequest(exchange);
                        Object article = v14Store.publishKnowledge(actor, courseId, string(body, "sectionId"),
                            string(body, "title"), string(body, "content"), string(body, "visibility"));
                        knowledgeIndex.wake();
                        respond(exchange, 201, article);
                        return;
                    }
                }
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

    private void v20(HttpExchange exchange) throws IOException {
        try {
            AuthenticatedUser actor = authStore.authenticate(token(exchange));
            String path = exchange.getRequestURI().getPath();
            if ("/api/v1/v20/course-packages/preview".equals(path)
                    && "POST".equals(exchange.getRequestMethod())) {
                Map<String, Object> body = objectRequest(exchange);
                respond(exchange, 200, v14Store.previewCoursePackage(actor, string(body, "packageJson")));
                return;
            }
            if ("/api/v1/v20/course-packages/import".equals(path)
                    && "POST".equals(exchange.getRequestMethod())) {
                Map<String, Object> body = objectRequest(exchange);
                boolean confirmed = Boolean.parseBoolean(String.valueOf(body.getOrDefault("licenseConfirmed", false)));
                respond(exchange, 201, v14Store.importCoursePackage(actor, string(body, "packageJson"),
                    string(body, "operationId"), string(body, "expectedSha256"), confirmed));
                return;
            }
            if ("/api/v1/v20/sync/artifacts".equals(path) && "POST".equals(exchange.getRequestMethod())) {
                Map<String, Object> body = objectRequest(exchange);
                List<CloudArtifactSyncItem> items = JSON.convertValue(body.getOrDefault("items", List.of()),
                    new TypeReference<List<CloudArtifactSyncItem>>() { });
                respond(exchange, 200, Map.of("results", v14Store.uploadArtifactSync(actor, items)));
                return;
            }
            if ("/api/v1/v20/sync/artifacts".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                long afterCursor = queryLong(exchange.getRequestURI().getRawQuery(), "afterCursor", 0);
                respond(exchange, 200, v14Store.downloadArtifactSync(actor, afterCursor));
                return;
            }
            respond(exchange, 404, errorResponse("NOT_FOUND", "Cloud 2.0 endpoint was not found."));
        } catch (SecurityException error) {
            respond(exchange, 403, errorResponse("FORBIDDEN", error.getMessage()));
        } catch (IllegalStateException error) {
            respond(exchange, 409, errorResponse("COURSE_PACKAGE_CONFLICT", error.getMessage()));
        } catch (IllegalArgumentException error) {
            respond(exchange, 400, errorResponse("INVALID_COURSE_PACKAGE", error.getMessage()));
        } catch (RuntimeException error) {
            logUnexpectedFailure("v2.0 operation", error);
            respond(exchange, 500, errorResponse("SERVER_ERROR", "Cloud 2.0 operation failed."));
        }
    }

    /** Reads ?channel= from the query string; blank or absent means the default channel. */
    private static String queryChannel(String query) {
        if (query == null || query.isBlank()) {
            return V31ExerciseBankStore.DEFAULT_CHANNEL;
        }
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0 && "channel".equals(pair.substring(0, separator))) {
                return pair.substring(separator + 1);
            }
        }
        return V31ExerciseBankStore.DEFAULT_CHANNEL;
    }

    private void bank(HttpExchange exchange) throws IOException {
        try {
            String[] segments = exchange.getRequestURI().getPath().split("/");
            String query = exchange.getRequestURI().getQuery();
            String channel = queryChannel(query);
            String method = exchange.getRequestMethod();
            if (segments.length == 5 && "manifest".equals(segments[4]) && "GET".equals(method)) {
                respond(exchange, 200, v31BankStore.manifest(channel));
                return;
            }
            if (segments.length == 5 && "channels".equals(segments[4]) && "GET".equals(method)) {
                respond(exchange, 200, Map.of("items", v31BankStore.channels()));
                return;
            }
            if (segments.length == 5 && "publish".equals(segments[4]) && "POST".equals(method)) {
                Map<String, Object> body = JSON.readValue(requestBytes(exchange, BANK_PUBLISH_MAX_BYTES), new TypeReference<>() { });
                String text = String.valueOf(body.getOrDefault("text", ""));
                String bodyChannel = String.valueOf(body.getOrDefault("channel", "network"));
                AuthenticatedUser actor = authStore.authenticate(token(exchange));
                int bankVersion = v31BankStore.publish(actor, bodyChannel, text);
                respond(exchange, 200, Map.of("bankVersion", bankVersion));
                return;
            }
            if (segments.length == 5 && "rollback".equals(segments[4]) && "POST".equals(method)) {
                Map<String, Object> body = JSON.readValue(requestBytes(exchange, 4_096), new TypeReference<>() { });
                String bodyChannel = String.valueOf(body.getOrDefault("channel", "network"));
                int bankVersion = Integer.parseInt(String.valueOf(body.get("bankVersion")));
                AuthenticatedUser actor = authStore.authenticate(token(exchange));
                int applied = v31BankStore.rollback(actor, bodyChannel, bankVersion);
                respond(exchange, 200, Map.of("bankVersion", applied));
                return;
            }
            if (segments.length == 7 && "block".equals(segments[4]) && "GET".equals(method)) {
                Map<String, Object> block = v31BankStore.block(channel, segments[5], segments[6]);
                if (block == null) {
                    respond(exchange, 404, errorResponse("EXERCISE_BANK_BLOCK_NOT_FOUND", "Unknown exercise bank block."));
                    return;
                }
                respond(exchange, 200, block);
                return;
            }
            if (segments.length == 8 && "block".equals(segments[4]) && "GET".equals(method)) {
                Map<String, Object> block = v31BankStore.block(segments[5], segments[6], segments[7]);
                if (block == null) {
                    respond(exchange, 404, errorResponse("EXERCISE_BANK_BLOCK_NOT_FOUND", "Unknown exercise bank block."));
                    return;
                }
                respond(exchange, 200, block);
                return;
            }
            methodNotAllowed(exchange);
        } catch (SecurityException error) {
            respond(exchange, 403, errorResponse("FORBIDDEN", error.getMessage()));
        } catch (PayloadTooLargeException error) {
            respond(exchange, 413, errorResponse("REQUEST_TOO_LARGE", "Request body is too large."));
        } catch (IllegalArgumentException error) {
            respond(exchange, 400, errorResponse("INVALID_REQUEST", error.getMessage()));
        } catch (SqlTeacherException error) {
            respond(exchange, 400, errorResponse(error.errorCode(), error.getMessage()));
        } catch (RuntimeException error) {
            logUnexpectedFailure("exercise bank operation", error);
            respond(exchange, 500, errorResponse("SERVER_ERROR", "Exercise bank operation failed."));
        }
    }

    private void admin(HttpExchange exchange) throws IOException {
        try {
            AuthenticatedUser actor = authStore.authenticate(token(exchange));
            String[] segments = exchange.getRequestURI().getPath().split("/");
            if (segments.length == 5 && "health".equals(segments[4])
                && "GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, adminStore.adminHealth(actor));
                return;
            }
            if (segments.length == 5 && "knowledge-index".equals(segments[4])
                && "GET".equals(exchange.getRequestMethod())) {
                requireAdmin(actor);
                CloudKnowledgeIndexService.Health health = knowledgeIndex.health();
                respond(exchange, 200, Map.of("status", health.status(), "backlog", health.backlog()));
                return;
            }
            if (segments.length == 6 && "knowledge-index".equals(segments[4])
                && "rebuild".equals(segments[5]) && "POST".equals(exchange.getRequestMethod())) {
                requireAdmin(actor);
                respond(exchange, 202, Map.of("queued", knowledgeIndex.rebuild()));
                return;
            }
            if (segments.length == 5 && "users".equals(segments[4])
                && "GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, Map.of("users", adminStore.adminUsers(actor)));
                return;
            }
            if (segments.length == 7 && "users".equals(segments[4])
                && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                String action = segments[6];
                if ("disable".equals(action) || "restore".equals(action)) {
                    respond(exchange, 200, adminStore.setUserDisabled(actor, segments[5], "disable".equals(action),
                        body.get("reasonCode")));
                    return;
                }
                if ("revoke-sessions".equals(action)) {
                    adminStore.revokeUserSessions(actor, segments[5], body.get("reasonCode"));
                    respond(exchange, 200, Map.of("status", "ok"));
                    return;
                }
            }
            // v3.8.0 ACC-S4（决策点 1 方案 B）：教师升级码签发/列表/撤销，仅管理员，全部落审计。
            if (segments.length == 5 && "role-codes".equals(segments[4])
                && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                int ttlDays;
                try {
                    ttlDays = Integer.parseInt(body.getOrDefault("ttlDays", "30"));
                } catch (NumberFormatException badTtl) {
                    throw new IllegalArgumentException("ttlDays must be a number");
                }
                respond(exchange, 201, adminStore.issueTeacherRoleCode(actor, ttlDays));
                return;
            }
            if (segments.length == 5 && "role-codes".equals(segments[4])
                && "GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, Map.of("items", adminStore.listTeacherRoleCodes(actor)));
                return;
            }
            if (segments.length == 7 && "role-codes".equals(segments[4]) && "revoke".equals(segments[6])
                && "POST".equals(exchange.getRequestMethod())) {
                adminStore.revokeTeacherRoleCode(actor, segments[5]);
                respond(exchange, 200, Map.of("status", "ok"));
                return;
            }
            if (segments.length == 5 && "audit".equals(segments[4])
                && "GET".equals(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getRawQuery();
                long page = queryLong(query, "page", 0);
                long pageSize = queryLong(query, "pageSize", 50);
                if (page > Integer.MAX_VALUE || pageSize > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Pagination value is too large");
                }
                respond(exchange, 200, adminStore.adminAudit(actor, queryValue(query, "action"),
                    instantOrNull(queryValue(query, "from")), instantOrNull(queryValue(query, "to")),
                    (int) page, (int) pageSize));
                return;
            }
            if (segments.length == 6 && "retention".equals(segments[4])
                && "preview".equals(segments[5]) && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                respond(exchange, 200, adminStore.previewRetention(actor,
                    RetentionCategory.valueOf(body.get("category")), Instant.parse(body.get("cutoff"))));
                return;
            }
            if (segments.length == 6 && "retention".equals(segments[4])
                && "execute".equals(segments[5]) && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                respond(exchange, 200, adminStore.executeRetention(actor, body.get("previewId"),
                    body.get("confirmationToken"), body.get("backupReference")));
                return;
            }
            if (segments.length == 7 && "retention".equals(segments[4])
                && "restore".equals(segments[6]) && "POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, adminStore.restoreRetention(actor, segments[5]));
                return;
            }
            // W6.3：令牌与保留备份的保留期清理；dry-run 默认先行，保留期可用环境变量覆盖。
            if (segments.length == 6 && "cleanup".equals(segments[4]) && "GET".equals(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getRawQuery();
                respond(exchange, 200, adminStore.cleanupPreview(actor, segments[5],
                    (int) queryLong(query, "days", -1)));
                return;
            }
            if (segments.length == 6 && "cleanup".equals(segments[4])
                && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                respond(exchange, 200, adminStore.cleanupExecute(actor, segments[5],
                    (int) queryLong(exchange.getRequestURI().getRawQuery(), "days", -1)));
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

    private void changePassword(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange);
            return;
        }
        Map<String, String> body = new LinkedHashMap<>();
        try {
            body = request(exchange);
            authStore.changePassword(token(exchange), body.getOrDefault("currentPassword", "").toCharArray(),
                body.getOrDefault("newPassword", "").toCharArray());
            exchange.sendResponseHeaders(204, -1);
        } catch (IllegalArgumentException error) { respond(exchange, 400, errorResponse("INVALID_PASSWORD", error.getMessage())); }
        catch (SecurityException error) { respond(exchange, 401, errorResponse("REAUTHENTICATION_FAILED", "Current password is incorrect.")); }
        catch (RuntimeException error) {
            logUnexpectedFailure("password change", error);
            respond(exchange, 500, errorResponse("SERVER_ERROR", "Password change failed."));
        }
        finally {
            body.replaceAll((key, value) -> "");
        }
    }

    private void appSupport(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/api/v1/app/capabilities".equals(path) && "GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 200, Map.of("apiVersion", "2.0", "minimumClientVersion", "1.9.0",
                "serverTime", Instant.now().toString(), "maintenance", false,
                "maximumSyncBatch", 200, "maximumSummaryBytes", 16384,
                "capabilities", List.of("BATCH_SUBMISSION_STATUS", "SIGNED_UPDATES", "PROBLEM_REPORTS", "CHANGE_PASSWORD", "REPORT_STATUS",
                    "REPORT_WITHDRAWAL", "REPORT_EXPORT", "SCREENSHOT_ATTACHMENT", "SESSIONS", "ACCOUNT_EXPORT",
                    "ACCOUNT_DELETION", "PASSWORD_RESET", "ROLLOUT", "COURSE_PACKAGE_V2",
                    "ARTIFACT_SYNC_V2", "EXPLICIT_SYNC_CONFLICTS", "PROJECT_METADATA_SYNC")));
            return;
        }
        if ("/api/v1/app/update-manifest".equals(path) && "GET".equals(exchange.getRequestMethod())) {
            try {
                String configured = System.getenv("SQLTEACHER_UPDATE_MANIFEST");
                if (configured == null || configured.isBlank()) {
                    respond(exchange, 404, errorResponse("UPDATE_MANIFEST_UNAVAILABLE", "No stable update is published."));
                    return;
                }
                Path manifest = Path.of(configured).toAbsolutePath().normalize();
                if (!Files.isRegularFile(manifest) || Files.size(manifest) > 128 * 1024) {
                    respond(exchange, 503, errorResponse("UPDATE_MANIFEST_UNAVAILABLE", "Update metadata is unavailable."));
                    return;
                }
                byte[] bytes = Files.readAllBytes(manifest);
                var envelope = JSON.readTree(bytes);
                if (!envelope.isObject() || !envelope.hasNonNull("keyId") || !envelope.hasNonNull("payload") || !envelope.hasNonNull("signature")) {
                    throw new IOException("Update envelope is invalid");
                }
                respondJsonBytes(exchange, 200, bytes, "public, max-age=300");
            } catch (RuntimeException | IOException error) {
                log.warn("Stable update manifest could not be served: {}", error.getClass().getSimpleName());
                respond(exchange, 503, errorResponse("UPDATE_MANIFEST_UNAVAILABLE", "Update metadata is unavailable."));
            }
            return;
        }
        if ("/api/v1/app/knowledge-bundle-manifest".equals(path) && "GET".equals(exchange.getRequestMethod())) {
            try {
                String configured = System.getenv("SQLTEACHER_KNOWLEDGE_BUNDLE_MANIFEST");
                if (configured == null || configured.isBlank()) {
                    respond(exchange, 404, errorResponse("KNOWLEDGE_BUNDLE_UNAVAILABLE", "No official knowledge bundle is published."));
                    return;
                }
                Path manifest = Path.of(configured).toAbsolutePath().normalize();
                if (!Files.isRegularFile(manifest) || Files.size(manifest) > 128 * 1024) {
                    respond(exchange, 503, errorResponse("KNOWLEDGE_BUNDLE_UNAVAILABLE", "Knowledge bundle metadata is unavailable."));
                    return;
                }
                byte[] bytes = Files.readAllBytes(manifest);
                var node = JSON.readTree(bytes);
                if (!node.isObject() || !node.hasNonNull("bundleId") || !node.hasNonNull("version") || !node.hasNonNull("sha256")) {
                    throw new IOException("Knowledge bundle manifest is invalid");
                }
                respondJsonBytes(exchange, 200, bytes, "public, max-age=300");
            } catch (RuntimeException | IOException error) {
                log.warn("Knowledge bundle manifest could not be served: {}", error.getClass().getSimpleName());
                respond(exchange, 503, errorResponse("KNOWLEDGE_BUNDLE_UNAVAILABLE", "Knowledge bundle metadata is unavailable."));
            }
            return;
        }
        if ("/api/v1/app/knowledge-bundle".equals(path) && "GET".equals(exchange.getRequestMethod())) {
            try {
                String configured = System.getenv("SQLTEACHER_KNOWLEDGE_BUNDLE_FILE");
                if (configured == null || configured.isBlank()) {
                    respond(exchange, 404, errorResponse("KNOWLEDGE_BUNDLE_UNAVAILABLE", "No official knowledge bundle is published."));
                    return;
                }
                Path bundle = Path.of(configured).toAbsolutePath().normalize();
                if (!Files.isRegularFile(bundle)) {
                    respond(exchange, 503, errorResponse("KNOWLEDGE_BUNDLE_UNAVAILABLE", "Knowledge bundle file is unavailable."));
                    return;
                }
                respondFile(exchange, bundle, "application/zip", "knowledge-bundle.zip");
            } catch (RuntimeException | IOException error) {
                log.warn("Knowledge bundle could not be served: {}", error.getClass().getSimpleName());
                respond(exchange, 503, errorResponse("KNOWLEDGE_BUNDLE_UNAVAILABLE", "Knowledge bundle file is unavailable."));
            }
            return;
        }
        respond(exchange, 404, errorResponse("NOT_FOUND", "Application service endpoint not found."));
    }

    private void support(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            if ("/api/v1/support/reports".equals(path) && "POST".equals(exchange.getRequestMethod())) {
                String userId = null;
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                if (authorization != null && !authorization.isBlank()) userId = authStore.authenticate(token(exchange)).id();
                Map<String, Object> body = objectRequest(exchange);
                String remote = clientPrincipal(exchange);
                respond(exchange, 201, v110SupportStore.submit(body, userId, remote));
                return;
            }
            String[] segments = path.split("/");
            if (segments.length == 7 && "POST".equals(exchange.getRequestMethod()) && "withdraw".equals(segments[6])) {
                String queryToken = queryValue(exchange.getRequestURI().getRawQuery(), "queryToken");
                respond(exchange, 200, v110SupportStore.withdraw(segments[5], queryToken));
                return;
            }
            if (segments.length == 7 && "GET".equals(exchange.getRequestMethod()) && "export".equals(segments[6])) {
                String queryToken = queryValue(exchange.getRequestURI().getRawQuery(), "queryToken");
                respond(exchange, 200, v110SupportStore.export(segments[5], queryToken));
                return;
            }
            if (segments.length == 6 && "GET".equals(exchange.getRequestMethod())) {
                String queryToken = queryValue(exchange.getRequestURI().getRawQuery(), "queryToken");
                respond(exchange, 200, v110SupportStore.status(segments[5], queryToken));
                return;
            }
            respond(exchange, 404, errorResponse("NOT_FOUND", "Support endpoint not found."));
        } catch (V110SupportStore.RateLimitException error) {
            exchange.getResponseHeaders().set("Retry-After", "3600");
            respond(exchange, 429, errorResponse("REPORT_RATE_LIMITED", "Too many reports. Try again later."));
        } catch (PayloadTooLargeException error) {
            respond(exchange, 413, errorResponse("REQUEST_TOO_LARGE", "Request body is too large."));
        } catch (IllegalArgumentException error) { respond(exchange, 400, errorResponse("INVALID_REPORT", error.getMessage())); }
        catch (SecurityException error) { respond(exchange, 401, errorResponse("REPORT_ACCESS_DENIED", "Report access denied.")); }
        catch (RuntimeException error) {
            logUnexpectedFailure("problem report", error);
            respond(exchange, 500, errorResponse("SERVER_ERROR", "Problem report operation failed."));
        }
    }

    private void v19(HttpExchange exchange) throws IOException {
        try {
            AuthenticatedUser actor = authStore.authenticate(token(exchange));
            String[] segments = exchange.getRequestURI().getPath().split("/");
            String method = exchange.getRequestMethod();
            if (segments.length == 5 && "operations-health".equals(segments[4]) && "GET".equals(method)) {
                respond(exchange, 200, v19Store.health(actor));
                return;
            }
            if (segments.length == 7 && "courses".equals(segments[4])) {
                String courseId = segments[5];
                if ("objectives".equals(segments[6])) {
                    if ("GET".equals(method)) {
                        respond(exchange, 200, Map.of("objectives", v19Store.listObjectives(actor, courseId)));
                        return;
                    }
                    if ("POST".equals(method)) {
                        Map<String, Object> body = objectRequest(exchange);
                        respond(exchange, 201, v19Store.createObjective(actor, courseId, string(body, "title"),
                            string(body, "description"), string(body, "completionCriteria"),
                            integer(body, "sortOrder", 0)));
                        return;
                    }
                }
                if ("study-plan".equals(segments[6]) && "GET".equals(method)) {
                    respond(exchange, 200, v19Store.studyPlan(actor, courseId));
                    return;
                }
                if ("interventions".equals(segments[6]) && "POST".equals(method)) {
                    Map<String, Object> body = objectRequest(exchange);
                    respond(exchange, 201, v19Store.createInterventionDraft(actor, courseId,
                        string(body, "classroomId"), string(body, "objectiveId"), string(body, "reasonCode"),
                        string(body, "action"), v14Store));
                    return;
                }
            }
            if (segments.length == 9 && "courses".equals(segments[4])
                && "objectives".equals(segments[6]) && "POST".equals(method)) {
                Map<String, Object> body = objectRequest(exchange);
                if ("prerequisites".equals(segments[8])) {
                    respond(exchange, 201, v19Store.addPrerequisite(actor, segments[5], segments[7],
                        string(body, "prerequisiteObjectiveId")));
                    return;
                }
                if ("resources".equals(segments[8])) {
                    ObjectiveResourceType type = ObjectiveResourceType.valueOf(
                        string(body, "resourceType").toUpperCase(Locale.ROOT));
                    respond(exchange, 201, v19Store.addResource(actor, segments[5], segments[7], type,
                        string(body, "resourceId")));
                    return;
                }
            }
            if (segments.length == 8 && "courses".equals(segments[4])
                && "objectives".equals(segments[6]) && "POST".equals(method)) {
                Map<String, Object> body = objectRequest(exchange);
                respond(exchange, 200, v19Store.updateObjective(actor, segments[5], segments[7],
                    string(body, "title"), string(body, "description"), string(body, "completionCriteria"),
                    integer(body, "sortOrder", 0),
                    ContentStatus.valueOf(string(body, "status").toUpperCase(Locale.ROOT)),
                    longValue(body, "expectedVersion", 0)));
                return;
            }
            if (segments.length == 10 && "courses".equals(segments[4])
                && "study-plan".equals(segments[6]) && "actions".equals(segments[7])
                && "state".equals(segments[9]) && "POST".equals(method)) {
                Map<String, Object> body = objectRequest(exchange);
                respond(exchange, 200, v19Store.updateActionState(actor, segments[5], segments[8],
                    StudyPlanActionState.valueOf(string(body, "state").toUpperCase(Locale.ROOT)),
                    longValue(body, "expectedVersion", 0), string(body, "operationId")));
                return;
            }
            if (segments.length == 9 && "courses".equals(segments[4])
                && "classrooms".equals(segments[6]) && "objective-summary".equals(segments[8])
                && "GET".equals(method)) {
                respond(exchange, 200, Map.of("objectives",
                    v19Store.objectiveSummaries(actor, segments[5], segments[7], v14Store)));
                return;
            }
            if (segments.length == 9 && "courses".equals(segments[4])
                && "interventions".equals(segments[6]) && "confirm".equals(segments[8])
                && "POST".equals(method)) {
                Map<String, Object> body = objectRequest(exchange);
                respond(exchange, 200, v19Store.confirmIntervention(actor, segments[5], segments[7],
                    string(body, "confirmationToken")));
                return;
            }
            respond(exchange, 404, errorResponse("NOT_FOUND", "API endpoint was not found."));
        } catch (V19VersionConflictException error) {
            respond(exchange, 409, errorResponse("PLANNING_VERSION_CONFLICT", error.getMessage()));
        } catch (SecurityException error) {
            respond(exchange, 403, errorResponse("FORBIDDEN", "You do not have access to this resource."));
        } catch (IllegalArgumentException error) {
            respond(exchange, 400, errorResponse("INVALID_REQUEST", error.getMessage()));
        } catch (RuntimeException error) {
            logUnexpectedFailure("v1.9 operation", error);
            respond(exchange, 500, errorResponse("SERVER_ERROR", "v1.9 operation failed."));
        }
    }

    private static void logUnexpectedFailure(String operation, RuntimeException error) {
        log.error("Cloud API operation failed, operation={}, exceptionType={}",
            operation, error.getClass().getSimpleName());
    }

    private static void requireAdmin(AuthenticatedUser actor) {
        if (!actor.hasRole(UserRole.ADMIN)) throw new SecurityException("administrator role required");
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
        Map<String, Object> decoded = JSON.readValue(requestBytes(exchange), new TypeReference<>() { });
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
        return JSON.readValue(requestBytes(exchange), new TypeReference<>() { });
    }

    private static byte[] requestBytes(HttpExchange exchange) throws IOException {
        return requestBytes(exchange, 64 * 1024);
    }

    private static byte[] requestBytes(HttpExchange exchange, int maxBytes) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) throw new PayloadTooLargeException();
        return bytes;
    }

    private static final class PayloadTooLargeException extends IllegalArgumentException { }


    /** Applies a fixed-window quota; the limit is intentionally coarse and never echoed to the caller. */
    private void enforceQuota(String key, int limit, Duration window) {
        authRateLimiter.checkQuota(key, limit);
        authRateLimiter.recordEvent(key, window);
    }

    private static void respondRateLimited(HttpExchange exchange, AuthRateLimiter.RateLimitedException error) throws IOException {
        exchange.getResponseHeaders().set("Retry-After", Long.toString(error.retryAfterSeconds()));
        respond(exchange, 429, errorResponse("RATE_LIMITED", "尝试过于频繁，请稍后再试"));
    }

    private static String normalizedEmailKey(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Resolves the rate-limit principal for a request. The production service is loopback-bound
     * behind Nginx, which appends the real client to {@code X-Forwarded-For}; the last entry is
     * therefore the trustworthy client address. Direct non-loopback peers keep the previous
     * behavior of using the direct address. The result feeds hashed rate-limit keys only and is
     * never logged with user identity data.
     */
    private static String clientPrincipal(HttpExchange exchange) {
        if (exchange.getRemoteAddress() == null || exchange.getRemoteAddress().getAddress() == null) return "unknown";
        InetAddress direct = exchange.getRemoteAddress().getAddress();
        return resolveClientAddress(direct.isLoopbackAddress(), direct.getHostAddress(),
            exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
    }

    static String resolveClientAddress(boolean directPeerIsLoopback, String directAddress, String forwardedFor) {
        if (!directPeerIsLoopback) return directAddress == null || directAddress.isBlank() ? "unknown" : directAddress;
        return lastForwardedEntry(forwardedFor);
    }

    static String lastForwardedEntry(String forwardedFor) {
        if (forwardedFor == null || forwardedFor.isBlank()) return "unknown";
        String[] entries = forwardedFor.split(",");
        for (int index = entries.length - 1; index >= 0; index--) {
            String candidate = entries[index].trim();
            if (!candidate.isEmpty()) return candidate;
        }
        return "unknown";
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

    private static char[] password(Map<String, String> body) {
        return body.getOrDefault("password", "").toCharArray();
    }

    private static char[] password(Map<String, String> body, String key) {
        return body.getOrDefault(key, "").toCharArray();
    }

    private static void clearPassword(HttpExchange ignored) {
        /* request body is released after this handler returns */
    }

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

    private static void respondJsonBytes(HttpExchange exchange, int status, byte[] bytes, String cacheControl) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", cacheControl);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
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

    private static Map<String, Object> errorResponse(String code, String message) {
        return Map.of("code", code, "message", message);
    }

    /** Streams a file from disk as an attachment (v3.4.3 OKB-4 knowledge bundle download). */
    private static void respondFile(HttpExchange exchange, Path file, String contentType, String filename)
        throws IOException {
        long length = Files.size(file);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=" + filename);
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=300");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, length);
        try (var body = exchange.getResponseBody(); var in = Files.newInputStream(file)) {
            in.transferTo(body);
        }
        exchange.close();
    }

    private static Map<String, Object> sessionResponse(SessionData session) {
        return Map.of("accessToken", session.token(), "expiresAt", session.expiresAt().toString(),
            "refreshToken", session.refreshToken(), "user", session.user());
    }

    private record SyncUpload(List<CloudSyncItem> items) {
        private SyncUpload {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

}
