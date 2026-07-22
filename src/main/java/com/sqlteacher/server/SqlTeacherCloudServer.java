package com.sqlteacher.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.ClassroomService;
import com.sqlteacher.application.collaboration.CloudAuthenticationService;
import com.sqlteacher.application.collaboration.CloudSyncItem;
import com.sqlteacher.application.collaboration.ClassAssignment;
import com.sqlteacher.application.collaboration.UserRole;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
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
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final int TOKEN_BYTES = 32;
    private static final int SALT_BYTES = 16;
    private static final int PBKDF2_ITERATIONS = 310_000;
    private static final int HASH_BITS = 256;
    private static final long ACCESS_TOKEN_HOURS = 8;
    private static final long REFRESH_TOKEN_DAYS = 30;

    private final CloudStore store;
    private final HttpServer server;

    SqlTeacherCloudServer(Path databasePath, int port) throws IOException, SQLException {
        this.store = new CloudStore(databasePath);
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
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("SQLTEACHER_CLOUD_PORT", "8080"));
        Path database = Path.of(System.getenv().getOrDefault("SQLTEACHER_CLOUD_DB", "./data/cloud.db"))
            .toAbsolutePath().normalize();
        SqlTeacherCloudServer cloudServer = new SqlTeacherCloudServer(database, port);
        cloudServer.server.start();
        System.out.println("SQLTeacher cloud API started on port " + port);
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
        catch (RuntimeException error) { error.printStackTrace(); respond(exchange, 500, errorResponse("SERVER_ERROR", "Registration failed.")); }
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
        catch (RuntimeException error) { error.printStackTrace(); respond(exchange, 500, errorResponse("SERVER_ERROR", "Login failed.")); }
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
        catch (RuntimeException error) { error.printStackTrace(); respond(exchange, 500, errorResponse("SERVER_ERROR", "Session refresh failed.")); }
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
                    respond(exchange,200,Map.of("assignments",store.listAssignments(actor,segments[4])));return;
                }
                if ("POST".equals(exchange.getRequestMethod())) {
                    Map<String,String> body=request(exchange);
                    respond(exchange,201,store.createAssignment(actor,segments[4],body.get("exerciseId"),body.get("title"),instantOrNull(body.get("dueAt"))));return;
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
            if (segments.length == 8 && "assignments".equals(segments[5]) && "status".equals(segments[7])
                && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                respond(exchange, 200, store.changeAssignmentStatus(actor, segments[4], segments[6],
                    com.sqlteacher.application.collaboration.AssignmentStatus.valueOf(body.get("status").toUpperCase(Locale.ROOT))));
                return;
            }
            if (segments.length == 8 && "assignments".equals(segments[5]) && "due".equals(segments[7])
                && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                respond(exchange, 200, store.setAssignmentDueAt(actor, segments[4], segments[6], instantOrNull(body.get("dueAt"))));
                return;
            }
            if (segments.length == 8 && "assignments".equals(segments[5]) && "details".equals(segments[7])
                && "POST".equals(exchange.getRequestMethod())) {
                Map<String, String> body = request(exchange);
                respond(exchange, 200, store.updateAssignment(actor, segments[4], segments[6], body.get("title"),
                    instantOrNull(body.get("dueAt"))));
                return;
            }
            respond(exchange, 404, errorResponse("NOT_FOUND", "API endpoint was not found."));
        } catch (SecurityException error) { respond(exchange, 403, errorResponse("FORBIDDEN", "You do not have access to this resource.")); }
        catch (IllegalArgumentException error) { respond(exchange, 400, errorResponse("INVALID_REQUEST", error.getMessage())); }
        catch (RuntimeException error) { error.printStackTrace(); respond(exchange, 500, errorResponse("SERVER_ERROR", "Classroom operation failed.")); }
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
            error.printStackTrace();
            respond(exchange, 500, errorResponse("SERVER_ERROR", "Synchronization failed."));
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

    private static Instant instantOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        return Instant.parse(value);
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
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=class-learning-records.csv");
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
                    if (!result.next() || result.getInt("disabled") != 0 || !constantTimeEquals(result.getBytes("password_hash"), hash(password, result.getBytes("password_salt")))) throw new SecurityException("invalid credentials");
                    userId = result.getString("id");
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

        ClassAssignment createAssignment(AuthenticatedUser actor,String classroomId,String exerciseId,String title,Instant dueAt){requireTeacher(actor,classroomId);if(exerciseId==null||exerciseId.isBlank()||title==null||title.isBlank())throw new IllegalArgumentException("exerciseId and title must not be blank");String id=UUID.randomUUID().toString();Instant now=Instant.now();if(dueAt!=null&&dueAt.isBefore(now))throw new IllegalArgumentException("dueAt must be in the future");try(Connection c=open();PreparedStatement s=c.prepareStatement("insert into class_assignments(id,classroom_id,exercise_id,title,created_at,status,due_at,updated_at) values(?,?,?,?,?,'PUBLISHED',?,?)")){s.setString(1,id);s.setString(2,classroomId);s.setString(3,exerciseId.trim());s.setString(4,title.trim());s.setString(5,now.toString());s.setString(6,dueAt==null?null:dueAt.toString());s.setString(7,now.toString());s.executeUpdate();return new ClassAssignment(id,classroomId,exerciseId.trim(),title.trim(),now,com.sqlteacher.application.collaboration.AssignmentStatus.PUBLISHED,dueAt,now);}catch(SQLException e){throw database(e);}}
        ClassAssignment setAssignmentDueAt(AuthenticatedUser actor,String classroomId,String assignmentId,Instant dueAt){requireTeacher(actor,classroomId);if(dueAt==null||!dueAt.isAfter(Instant.now()))throw new IllegalArgumentException("dueAt must be in the future");try(Connection c=open();PreparedStatement s=c.prepareStatement("update class_assignments set due_at=?,updated_at=? where id=? and classroom_id=?")){s.setString(1,dueAt.toString());s.setString(2,Instant.now().toString());s.setString(3,assignmentId);s.setString(4,classroomId);if(s.executeUpdate()!=1)throw new IllegalArgumentException("Assignment was not found");return assignment(c,classroomId,assignmentId);}catch(SQLException e){throw database(e);}}
        ClassAssignment changeAssignmentStatus(AuthenticatedUser actor,String classroomId,String assignmentId,com.sqlteacher.application.collaboration.AssignmentStatus status){requireTeacher(actor,classroomId);Instant now=Instant.now();try(Connection c=open()){ClassAssignment current=assignment(c,classroomId,assignmentId);if(!validTransition(current.status(),status))throw new IllegalArgumentException("Assignment status transition is not allowed");try(PreparedStatement s=c.prepareStatement("update class_assignments set status=?,updated_at=? where id=? and classroom_id=?")){s.setString(1,status.name());s.setString(2,now.toString());s.setString(3,assignmentId);s.setString(4,classroomId);s.executeUpdate();}return assignment(c,classroomId,assignmentId);}catch(SQLException e){throw database(e);}}
        ClassAssignment updateAssignment(AuthenticatedUser actor,String classroomId,String assignmentId,String title,Instant dueAt){requireTeacher(actor,classroomId);if(title==null||title.isBlank()||title.length()>160)throw new IllegalArgumentException("title must be 1 to 160 characters");if(dueAt!=null&&!dueAt.isAfter(Instant.now()))throw new IllegalArgumentException("dueAt must be in the future");try(Connection c=open()){ClassAssignment current=assignment(c,classroomId,assignmentId);if(current.status()==com.sqlteacher.application.collaboration.AssignmentStatus.ARCHIVED)throw new IllegalArgumentException("Archived assignments cannot be edited");try(PreparedStatement s=c.prepareStatement("update class_assignments set title=?,due_at=?,updated_at=? where id=? and classroom_id=?")){s.setString(1,title.trim());s.setString(2,dueAt==null?null:dueAt.toString());s.setString(3,Instant.now().toString());s.setString(4,assignmentId);s.setString(5,classroomId);s.executeUpdate();}return assignment(c,classroomId,assignmentId);}catch(SQLException e){throw database(e);}}
        private boolean validTransition(com.sqlteacher.application.collaboration.AssignmentStatus from,com.sqlteacher.application.collaboration.AssignmentStatus to){if(from==to)return true;return switch(from){case DRAFT->to==com.sqlteacher.application.collaboration.AssignmentStatus.PUBLISHED||to==com.sqlteacher.application.collaboration.AssignmentStatus.WITHDRAWN;case PUBLISHED->to==com.sqlteacher.application.collaboration.AssignmentStatus.CLOSED||to==com.sqlteacher.application.collaboration.AssignmentStatus.WITHDRAWN;case CLOSED,WITHDRAWN->to==com.sqlteacher.application.collaboration.AssignmentStatus.ARCHIVED;case ARCHIVED->false;};}
        List<ClassAssignment> listAssignments(AuthenticatedUser actor,String classroomId){requireMember(actor,classroomId);closeExpiredAssignments(classroomId);List<ClassAssignment> result=new ArrayList<>();boolean teacher=actor.hasRole(UserRole.ADMIN)||isTeacher(actor,classroomId);String sql=teacher?"select id,exercise_id,title,created_at,status,due_at,updated_at from class_assignments where classroom_id=? order by created_at desc":"select id,exercise_id,title,created_at,status,due_at,updated_at from class_assignments where classroom_id=? and status in ('PUBLISHED','CLOSED') order by created_at desc";try(Connection c=open();PreparedStatement s=c.prepareStatement(sql)){s.setString(1,classroomId);try(ResultSet r=s.executeQuery()){while(r.next())result.add(assignment(r,classroomId));}return List.copyOf(result);}catch(SQLException e){throw database(e);}}
        private void closeExpiredAssignments(String classroomId){Instant now=Instant.now();try(Connection c=open();PreparedStatement s=c.prepareStatement("update class_assignments set status='CLOSED',updated_at=? where classroom_id=? and status='PUBLISHED' and due_at is not null and due_at<=?")){s.setString(1,now.toString());s.setString(2,classroomId);s.setString(3,now.toString());s.executeUpdate();}catch(SQLException e){throw database(e);}}
        com.sqlteacher.application.collaboration.ClassLearningSummary classLearningSummary(AuthenticatedUser actor,String classroomId){requireTeacher(actor,classroomId);int students=0;int active=0;int events=0;int success=0;try(Connection c=open();PreparedStatement s=c.prepareStatement("select m.user_id,e.payload_json from classroom_members m left join sync_events e on e.user_id=m.user_id where m.classroom_id=? and m.role='STUDENT'")){s.setString(1,classroomId);java.util.Set<String> seenStudents=new java.util.HashSet<>();java.util.Set<String> activeStudents=new java.util.HashSet<>();try(ResultSet r=s.executeQuery()){while(r.next()){String userId=r.getString(1);seenStudents.add(userId);String payload=r.getString(2);if(payload==null)continue;events++;activeStudents.add(userId);try{if(JSON.readTree(payload).path("successful").asBoolean(false))success++;}catch(IOException ignored){}}}students=seenStudents.size();active=activeStudents.size();}catch(SQLException e){throw database(e);}return new com.sqlteacher.application.collaboration.ClassLearningSummary(classroomId,students,active,events,success,Instant.now());}
        String exportClassLearningCsv(AuthenticatedUser actor,String classroomId){requireTeacher(actor,classroomId);StringBuilder csv=new StringBuilder("\uFEFFstudent_email,event_type,occurred_at,successful\r\n");int rows=0;try(Connection c=open();PreparedStatement s=c.prepareStatement("select u.email,e.event_type,e.occurred_at,e.payload_json from classroom_members m join users u on u.id=m.user_id join sync_events e on e.user_id=m.user_id where m.classroom_id=? and m.role='STUDENT' order by e.occurred_at")){s.setString(1,classroomId);try(ResultSet r=s.executeQuery()){while(r.next()){boolean successful=false;try{successful=JSON.readTree(r.getString(4)).path("successful").asBoolean(false);}catch(IOException ignored){}csv.append(csvCell(r.getString(1))).append(',').append(csvCell(r.getString(2))).append(',').append(csvCell(r.getString(3))).append(',').append(successful).append("\r\n");rows++;}}try(PreparedStatement audit=c.prepareStatement("insert into export_audit(id,user_id,classroom_id,row_count,created_at) values(?,?,?,?,?)")){audit.setString(1,UUID.randomUUID().toString());audit.setString(2,actor.id());audit.setString(3,classroomId);audit.setInt(4,rows);audit.setString(5,Instant.now().toString());audit.executeUpdate();}}catch(SQLException e){throw database(e);}return csv.toString();}
        private String csvCell(String value){String normalized=value==null?"":value;if(!normalized.isEmpty()&&"=+-@".indexOf(normalized.charAt(0))>=0)normalized="'"+normalized;return "\""+normalized.replace("\"","\"\"")+"\"";}
        private ClassAssignment assignment(Connection c,String classroomId,String assignmentId)throws SQLException{try(PreparedStatement s=c.prepareStatement("select id,exercise_id,title,created_at,status,due_at,updated_at from class_assignments where id=? and classroom_id=?")){s.setString(1,assignmentId);s.setString(2,classroomId);try(ResultSet r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Assignment was not found");return assignment(r,classroomId);}}}
        private ClassAssignment assignment(ResultSet r,String classroomId)throws SQLException{String due=r.getString("due_at");return new ClassAssignment(r.getString("id"),classroomId,r.getString("exercise_id"),r.getString("title"),Instant.parse(r.getString("created_at")),com.sqlteacher.application.collaboration.AssignmentStatus.valueOf(r.getString("status")),due==null?null:Instant.parse(due),Instant.parse(r.getString("updated_at")));}

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
        private Connection open() throws SQLException { return DriverManager.getConnection("jdbc:sqlite:"+database); }
        private void initialize() throws SQLException { try(Connection c=open(); Statement s=c.createStatement()){s.executeUpdate("pragma foreign_keys=on");s.executeUpdate("create table if not exists users(id text primary key,email text not null unique,display_name text not null,password_hash blob not null,password_salt blob not null,disabled integer not null default 0,created_at text not null)");s.executeUpdate("create table if not exists user_roles(user_id text not null references users(id),role text not null check(role in ('ADMIN','TEACHER','STUDENT')),primary key(user_id,role))");s.executeUpdate("create table if not exists access_tokens(token_hash blob primary key,user_id text not null references users(id),expires_at text not null,created_at text not null,revoked_at text)");s.executeUpdate("create table if not exists refresh_tokens(token_hash blob primary key,user_id text not null references users(id),expires_at text not null,created_at text not null,revoked_at text)");s.executeUpdate("create table if not exists classrooms(id text primary key,name text not null,created_at text not null)");s.executeUpdate("create table if not exists classroom_members(classroom_id text not null references classrooms(id),user_id text not null references users(id),role text not null check(role in ('TEACHER','STUDENT')),primary key(classroom_id,user_id))");s.executeUpdate("create table if not exists class_assignments(id text primary key,classroom_id text not null references classrooms(id),exercise_id text not null,title text not null,created_at text not null,status text not null default 'PUBLISHED',due_at text,updated_at text not null)");s.executeUpdate("create table if not exists sync_events(version integer primary key autoincrement,user_id text not null references users(id),event_id text not null,event_type text not null,payload_json text not null,occurred_at text not null,unique(user_id,event_id))");s.executeUpdate("create table if not exists export_audit(id text primary key,user_id text not null references users(id),classroom_id text not null references classrooms(id),row_count integer not null,created_at text not null)");addColumnIfMissing(s,"class_assignments","status text not null default 'PUBLISHED'");addColumnIfMissing(s,"class_assignments","due_at text");addColumnIfMissing(s,"class_assignments","updated_at text");s.executeUpdate("update class_assignments set updated_at=created_at where updated_at is null");} }
        private void addColumnIfMissing(Statement statement,String table,String definition)throws SQLException{try{statement.executeUpdate("alter table "+table+" add column "+definition);}catch(SQLException error){if(!error.getMessage().toLowerCase(Locale.ROOT).contains("duplicate column"))throw error;}}
        private byte[] bytes(int count){byte[] value=new byte[count];random.nextBytes(value);return value;} private byte[] hash(char[] password,byte[] salt){try{KeySpec spec=new PBEKeySpec(password,salt,PBKDF2_ITERATIONS,HASH_BITS);return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}catch(GeneralSecurityException e){throw new IllegalStateException("Password hashing unavailable",e);}} private byte[] tokenHash(String token){try{return java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));}catch(GeneralSecurityException e){throw new IllegalStateException(e);}} private static boolean constantTimeEquals(byte[] a,byte[] b){return java.security.MessageDigest.isEqual(a,b);} private static String validateEmail(String e){if(e==null||!e.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")||e.length()>254)throw new IllegalArgumentException("email must be valid");return e.trim().toLowerCase(Locale.ROOT);} private static void validatePassword(char[] p){if(p==null||p.length<12||p.length>128)throw new IllegalArgumentException("password must contain 12 to 128 characters");} private static IllegalStateException database(SQLException e){return new IllegalStateException("Cloud database operation failed",e);} private static CloudAuthenticationService.Session toSession(SessionData s){return new CloudAuthenticationService.Session(s.token(),s.expiresAt(),s.user(),s.refreshToken());}
    }
    private record SessionData(String token, Instant expiresAt, AuthenticatedUser user, String refreshToken) { }
}
