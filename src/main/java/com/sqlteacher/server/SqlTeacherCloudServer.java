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
 * Small deployable cloud API for v1.1. It intentionally exposes only account and class APIs;
 * desktop database credentials and BYO-AI keys never cross this boundary.
 */
public final class SqlTeacherCloudServer {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final int TOKEN_BYTES = 32;
    private static final int SALT_BYTES = 16;
    private static final int PBKDF2_ITERATIONS = 310_000;
    private static final int HASH_BITS = 256;

    private final CloudStore store;
    private final HttpServer server;

    private SqlTeacherCloudServer(Path databasePath, int port) throws IOException, SQLException {
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
            store.logout(token(exchange));
            exchange.sendResponseHeaders(204, -1);
        } catch (SecurityException error) { respond(exchange, 401, errorResponse("UNAUTHORIZED", "Login is required.")); }
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
                    respond(exchange,201,store.createAssignment(actor,segments[4],body.get("exerciseId"),body.get("title")));return;
                }
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

    private static Map<String, String> request(HttpExchange exchange) throws IOException {
        Map<String, Object> decoded = JSON.readValue(exchange.getRequestBody(), new TypeReference<>() { });
        Map<String, String> result = new LinkedHashMap<>();
        decoded.forEach((key, value) -> result.put(key, value == null ? "" : String.valueOf(value)));
        return result;
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

    private static Map<String, Object> errorResponse(String code, String message) { return Map.of("code", code, "message", message); }
    private static Map<String, Object> sessionResponse(SessionData session) {
        return Map.of("accessToken", session.token(), "expiresAt", session.expiresAt().toString(), "user", session.user());
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
        @Override public void logout(String accessToken) { revoke(accessToken); }

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

        ClassAssignment createAssignment(AuthenticatedUser actor,String classroomId,String exerciseId,String title){requireTeacher(actor,classroomId);if(exerciseId==null||exerciseId.isBlank()||title==null||title.isBlank())throw new IllegalArgumentException("exerciseId and title must not be blank");String id=UUID.randomUUID().toString();Instant now=Instant.now();try(Connection c=open();PreparedStatement s=c.prepareStatement("insert into class_assignments(id,classroom_id,exercise_id,title,created_at) values(?,?,?,?,?)")){s.setString(1,id);s.setString(2,classroomId);s.setString(3,exerciseId.trim());s.setString(4,title.trim());s.setString(5,now.toString());s.executeUpdate();return new ClassAssignment(id,classroomId,exerciseId.trim(),title.trim(),now);}catch(SQLException e){throw database(e);}}
        List<ClassAssignment> listAssignments(AuthenticatedUser actor,String classroomId){requireMember(actor,classroomId);List<ClassAssignment> result=new ArrayList<>();try(Connection c=open();PreparedStatement s=c.prepareStatement("select id,exercise_id,title,created_at from class_assignments where classroom_id=? order by created_at desc")){s.setString(1,classroomId);try(ResultSet r=s.executeQuery()){while(r.next())result.add(new ClassAssignment(r.getString(1),classroomId,r.getString(2),r.getString(3),Instant.parse(r.getString(4))));}return List.copyOf(result);}catch(SQLException e){throw database(e);}}

        private AuthenticatedUser authenticateData(String token) {
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("select user_id from access_tokens where token_hash=? and expires_at>? and revoked_at is null")) {
                statement.setBytes(1, tokenHash(token)); statement.setString(2, Instant.now().toString());
                try (ResultSet result = statement.executeQuery()) { if (!result.next()) throw new SecurityException("invalid token"); return user(result.getString(1)); }
            } catch (SQLException error) { throw database(error); }
        }
        private void revoke(String token) { try (Connection c=open(); PreparedStatement s=c.prepareStatement("update access_tokens set revoked_at=? where token_hash=?")) { s.setString(1,Instant.now().toString()); s.setBytes(2,tokenHash(token)); if(s.executeUpdate()==0) throw new SecurityException("invalid token"); } catch(SQLException e){throw database(e);} }
        private SessionData issue(AuthenticatedUser user) { String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(TOKEN_BYTES)); Instant expiry=Instant.now().plus(8, ChronoUnit.HOURS); try(Connection c=open(); PreparedStatement s=c.prepareStatement("insert into access_tokens(token_hash,user_id,expires_at,created_at) values(?,?,?,?)")){s.setBytes(1,tokenHash(token));s.setString(2,user.id());s.setString(3,expiry.toString());s.setString(4,Instant.now().toString());s.executeUpdate();}catch(SQLException e){throw database(e);} return new SessionData(token,expiry,user); }
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
        private void requireMember(AuthenticatedUser actor,String classId){if(actor.hasRole(UserRole.ADMIN))return;try(Connection c=open();PreparedStatement s=c.prepareStatement("select 1 from classroom_members where classroom_id=? and user_id=?")){s.setString(1,classId);s.setString(2,actor.id());try(ResultSet r=s.executeQuery()){if(!r.next())throw new SecurityException("not classroom member");}}catch(SQLException e){throw database(e);}}
        private Connection open() throws SQLException { return DriverManager.getConnection("jdbc:sqlite:"+database); }
        private void initialize() throws SQLException { try(Connection c=open(); Statement s=c.createStatement()){s.executeUpdate("pragma foreign_keys=on");s.executeUpdate("create table if not exists users(id text primary key,email text not null unique,display_name text not null,password_hash blob not null,password_salt blob not null,disabled integer not null default 0,created_at text not null)");s.executeUpdate("create table if not exists user_roles(user_id text not null references users(id),role text not null check(role in ('ADMIN','TEACHER','STUDENT')),primary key(user_id,role))");s.executeUpdate("create table if not exists access_tokens(token_hash blob primary key,user_id text not null references users(id),expires_at text not null,created_at text not null,revoked_at text)");s.executeUpdate("create table if not exists classrooms(id text primary key,name text not null,created_at text not null)");s.executeUpdate("create table if not exists classroom_members(classroom_id text not null references classrooms(id),user_id text not null references users(id),role text not null check(role in ('TEACHER','STUDENT')),primary key(classroom_id,user_id))");s.executeUpdate("create table if not exists class_assignments(id text primary key,classroom_id text not null references classrooms(id),exercise_id text not null,title text not null,created_at text not null)");s.executeUpdate("create table if not exists sync_events(version integer primary key autoincrement,user_id text not null references users(id),event_id text not null,event_type text not null,payload_json text not null,occurred_at text not null,unique(user_id,event_id))");} }
        private byte[] bytes(int count){byte[] value=new byte[count];random.nextBytes(value);return value;} private byte[] hash(char[] password,byte[] salt){try{KeySpec spec=new PBEKeySpec(password,salt,PBKDF2_ITERATIONS,HASH_BITS);return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}catch(GeneralSecurityException e){throw new IllegalStateException("Password hashing unavailable",e);}} private byte[] tokenHash(String token){try{return java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));}catch(GeneralSecurityException e){throw new IllegalStateException(e);}} private static boolean constantTimeEquals(byte[] a,byte[] b){return java.security.MessageDigest.isEqual(a,b);} private static String validateEmail(String e){if(e==null||!e.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")||e.length()>254)throw new IllegalArgumentException("email must be valid");return e.trim().toLowerCase(Locale.ROOT);} private static void validatePassword(char[] p){if(p==null||p.length<12||p.length>128)throw new IllegalArgumentException("password must contain 12 to 128 characters");} private static IllegalStateException database(SQLException e){return new IllegalStateException("Cloud database operation failed",e);} private static CloudAuthenticationService.Session toSession(SessionData s){return new CloudAuthenticationService.Session(s.token(),s.expiresAt(),s.user());}
    }
    private record SessionData(String token, Instant expiresAt, AuthenticatedUser user) { }
}
