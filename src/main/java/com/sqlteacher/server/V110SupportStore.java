package com.sqlteacher.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sqlteacher.application.support.ProblemReportExport;
import com.sqlteacher.application.support.ProblemReportReceipt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class V110SupportStore {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Set<String> TYPES = Set.of("BUG", "UPDATE_PROBLEM", "USABILITY", "SUGGESTION", "OTHER");
    private static final Set<String> SEVERITIES = Set.of("DATA_OR_STARTUP_RISK", "MAIN_FLOW_BLOCKED", "PARTIAL_FAILURE", "MINOR");
    private final String url;
    private final byte[] hashSecret;
    private final SecureRandom random = new SecureRandom();

    V110SupportStore(java.nio.file.Path database) throws SQLException {
        url = "jdbc:sqlite:" + database.toAbsolutePath().normalize();
        String secret = System.getenv("SQLTEACHER_FEEDBACK_HASH_SECRET");
        if (secret == null || secret.length() < 32) {
            byte[] generated = new byte[32]; random.nextBytes(generated); hashSecret = generated;
        } else hashSecret = secret.getBytes(StandardCharsets.UTF_8);
        initialize();
    }

    ProblemReportReceipt submit(Map<String, Object> body, String authenticatedUserId, String remoteAddress) {
        String idempotency = required(body, "idempotencyKey", 80);
        String installId = required(body, "installId", 80);
        String type = enumeration(body, "type", TYPES);
        String severity = enumeration(body, "severity", SEVERITIES);
        String summary = required(body, "summary", 160);
        String description = required(body, "description", 4000);
        String reproduction = optional(body, "reproductionSteps", 4000);
        String expected = optional(body, "expectedResult", 2000);
        String actual = optional(body, "actualResult", 2000);
        String contact = optional(body, "contact", 254);
        String applicationJson = safeJson(body.get("application"), 4096);
        String diagnosticsJson = safeDiagnostics(body.get("diagnostics"));
        Screenshot screenshot = parseScreenshot(body.get("screenshot"));
        String principal = authenticatedUserId == null ? "anon:" + digest(remoteAddress + ":" + installId) : "user:" + authenticatedUserId;
        try (Connection connection = open()) {
            try (PreparedStatement existing = connection.prepareStatement(
                "select id,query_token_hash,status,created_at from problem_reports where principal_key=? and idempotency_key=?")) {
                existing.setString(1, principal); existing.setString(2, idempotency);
                try (ResultSet row = existing.executeQuery()) {
                    if (row.next()) {
                        String queryToken = randomToken();
                        try (PreparedStatement rotate = connection.prepareStatement("update problem_reports set query_token_hash=?,updated_at=? where id=?")) {
                            rotate.setString(1, digest(queryToken)); rotate.setString(2, Instant.now().toString()); rotate.setString(3, row.getString("id")); rotate.executeUpdate();
                        }
                        return new ProblemReportReceipt(row.getString("id"), queryToken, ProblemReportReceipt.Status.valueOf(row.getString("status")), Instant.parse(row.getString("created_at")));
                    }
                }
            }
            enforceRate(principal);
            String id = "FB-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
            String queryToken = randomToken(); Instant now = Instant.now();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                "insert into problem_reports(id,principal_key,user_id,idempotency_key,install_id_hash,type,severity,summary,description,reproduction_steps,expected_result,actual_result,application_json,diagnostics_json,status,query_token_hash,created_at,updated_at,expires_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                int index = 1; statement.setString(index++, id); statement.setString(index++, principal);
                if (authenticatedUserId == null) statement.setNull(index++, Types.VARCHAR); else statement.setString(index++, authenticatedUserId);
                statement.setString(index++, idempotency); statement.setString(index++, digest(installId)); statement.setString(index++, type);
                statement.setString(index++, severity); statement.setString(index++, summary); statement.setString(index++, description);
                statement.setString(index++, reproduction); statement.setString(index++, expected); statement.setString(index++, actual);
                statement.setString(index++, applicationJson); statement.setString(index++, diagnosticsJson); statement.setString(index++, "RECEIVED");
                statement.setString(index++, digest(queryToken)); statement.setString(index++, now.toString()); statement.setString(index++, now.toString());
                statement.setString(index, now.plus(365, ChronoUnit.DAYS).toString()); statement.executeUpdate();
            }
            if (!contact.isBlank()) try (PreparedStatement statement = connection.prepareStatement(
                "insert into problem_report_contacts(report_id,contact) values(?,?)")) { statement.setString(1, id); statement.setString(2, contact); statement.executeUpdate(); }
            if (screenshot != null) try (PreparedStatement statement = connection.prepareStatement(
                "insert into problem_report_screenshots(report_id,mime_type,filename,data_blob,created_at) values(?,?,?,?,?)")) {
                statement.setString(1, id); statement.setString(2, screenshot.mimeType()); statement.setString(3, screenshot.filename());
                statement.setBytes(4, screenshot.data()); statement.setString(5, now.toString()); statement.executeUpdate();
            }
            try (PreparedStatement history = connection.prepareStatement(
                "insert into problem_report_status_history(report_id,status,reason_code,created_at) values(?,'RECEIVED','USER_SUBMITTED',?)")) {
                history.setString(1, id); history.setString(2, now.toString()); history.executeUpdate();
            }
            connection.commit();
            return new ProblemReportReceipt(id, queryToken, ProblemReportReceipt.Status.RECEIVED, now);
        } catch (SQLException error) { throw database(error); }
    }

    /** Withdraws a report that has not been processed yet. Already-withdrawn reports succeed idempotently. */
    ProblemReportReceipt withdraw(String id, String queryToken) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            String status = authenticate(connection, id, queryToken);
            if ("WITHDRAWN".equals(status)) { connection.commit(); return new ProblemReportReceipt(id, queryToken, ProblemReportReceipt.Status.WITHDRAWN, Instant.parse(createdAt(connection, id))); }
            if (!"RECEIVED".equals(status)) throw new IllegalArgumentException("report can no longer be withdrawn");
            String now = Instant.now().toString();
            try (PreparedStatement update = connection.prepareStatement("update problem_reports set status='WITHDRAWN',updated_at=? where id=?")) {
                update.setString(1, now); update.setString(2, id); update.executeUpdate();
            }
            try (PreparedStatement history = connection.prepareStatement(
                "insert into problem_report_status_history(report_id,status,reason_code,created_at) values(?,'WITHDRAWN','USER_WITHDRAWN',?)")) {
                history.setString(1, id); history.setString(2, now); history.executeUpdate();
            }
            connection.commit();
            return new ProblemReportReceipt(id, queryToken, ProblemReportReceipt.Status.WITHDRAWN, Instant.parse(createdAt(connection, id)));
        } catch (SQLException error) { throw database(error); }
    }

    /** Exports the caller's own report metadata, excluding internal audit data and other users' content. */
    ProblemReportExport export(String id, String queryToken) {
        try (Connection connection = open()) {
            String status = authenticate(connection, id, queryToken);
            try (PreparedStatement statement = connection.prepareStatement(
                "select type,summary,created_at,updated_at from problem_reports where id=?")) {
                statement.setString(1, id);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new SecurityException("report access denied");
                    List<ProblemReportExport.StatusChange> history = new ArrayList<>();
                    try (PreparedStatement historyStatement = connection.prepareStatement(
                        "select status,reason_code,created_at from problem_report_status_history where report_id=? order by created_at")) {
                        historyStatement.setString(1, id);
                        try (ResultSet historyRow = historyStatement.executeQuery()) {
                            while (historyRow.next()) history.add(new ProblemReportExport.StatusChange(historyRow.getString("status"),
                                historyRow.getString("reason_code"), Instant.parse(historyRow.getString("created_at"))));
                        }
                    }
                    return new ProblemReportExport(id, row.getString("type"), status, row.getString("summary"),
                        Instant.parse(row.getString("created_at")), Instant.parse(row.getString("updated_at")), List.copyOf(history));
                }
            }
        } catch (SQLException error) { throw database(error); }
    }

    private String authenticate(Connection connection, String id, String queryToken) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select status,query_token_hash from problem_reports where id=?")) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || !MessageDigest.isEqual(digest(queryToken).getBytes(StandardCharsets.US_ASCII), row.getString("query_token_hash").getBytes(StandardCharsets.US_ASCII))) {
                    throw new SecurityException("report access denied");
                }
                return row.getString("status");
            }
        }
    }
    private String createdAt(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select created_at from problem_reports where id=?")) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) { if (row.next()) return row.getString("created_at"); }
        }
        throw new SecurityException("report access denied");
    }

    private static Screenshot parseScreenshot(Object value) {
        if (value == null) return null;
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("screenshot is invalid");
        Map<?, ?> map = (Map<?, ?>) value;
        String filename = String.valueOf(map.get("filename")).strip();
        String mimeType = String.valueOf(map.get("mimeType")).strip().toLowerCase(Locale.ROOT);
        String data = String.valueOf(map.get("data")).strip();
        if (filename.isEmpty() || filename.length() > 160) throw new IllegalArgumentException("screenshot filename is invalid");
        if (!"image/png".equals(mimeType) && !"image/jpeg".equals(mimeType)) throw new IllegalArgumentException("screenshot format must be PNG or JPEG");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("screenshot data is invalid", error);
        }
        if (bytes.length == 0 || bytes.length > 2 * 1024 * 1024) throw new IllegalArgumentException("screenshot must be 1 byte..2 MiB");
        return new Screenshot(filename, mimeType, bytes);
    }
    private record Screenshot(String filename, String mimeType, byte[] data) { }

    ProblemReportReceipt status(String id, String queryToken) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select status,created_at,query_token_hash from problem_reports where id=?")) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || !MessageDigest.isEqual(digest(queryToken).getBytes(StandardCharsets.US_ASCII), row.getString("query_token_hash").getBytes(StandardCharsets.US_ASCII))) {
                    throw new SecurityException("report access denied");
                }
                return new ProblemReportReceipt(id, queryToken, ProblemReportReceipt.Status.valueOf(row.getString("status")), Instant.parse(row.getString("created_at")));
            }
        } catch (SQLException error) { throw database(error); }
    }

    private void enforceRate(String principal) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select count(*) from problem_reports where principal_key=? and created_at>=?")) {
            statement.setString(1, principal); statement.setString(2, Instant.now().minus(1, ChronoUnit.HOURS).toString());
            try (ResultSet row = statement.executeQuery()) { if (row.next() && row.getInt(1) >= 5) throw new RateLimitException(); }
        } catch (SQLException error) { throw database(error); }
    }

    private void initialize() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table if not exists problem_reports(id text primary key,principal_key text not null,user_id text,idempotency_key text not null,install_id_hash text not null,type text not null,severity text not null,summary text not null,description text not null,reproduction_steps text not null,expected_result text not null,actual_result text not null,application_json text not null,diagnostics_json text not null,status text not null,query_token_hash text not null,created_at text not null,updated_at text not null,expires_at text not null,unique(principal_key,idempotency_key))");
            statement.executeUpdate("create table if not exists problem_report_contacts(report_id text primary key references problem_reports(id) on delete cascade,contact text not null)");
            statement.executeUpdate("create table if not exists problem_report_screenshots(report_id text primary key references problem_reports(id) on delete cascade,mime_type text not null,filename text not null,data_blob blob not null,created_at text not null)");
            statement.executeUpdate("create table if not exists problem_report_status_history(id integer primary key autoincrement,report_id text not null references problem_reports(id) on delete cascade,status text not null,reason_code text not null,created_at text not null)");
            statement.executeUpdate("create index if not exists idx_problem_reports_status_time on problem_reports(status,created_at desc)");
            statement.executeUpdate("create index if not exists idx_problem_reports_expiry on problem_reports(expires_at)");
        }
    }
    private Connection open() throws SQLException { Connection connection = DriverManager.getConnection(url); try (Statement s = connection.createStatement()) { s.execute("pragma foreign_keys=on"); s.execute("pragma busy_timeout=5000"); } return connection; }
    private String digest(String value) { try { MessageDigest digest = MessageDigest.getInstance("SHA-256"); digest.update(hashSecret); return HexFormat.of().formatHex(digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8))); } catch (Exception error) { throw new IllegalStateException(error); } }
    private String randomToken() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String required(Map<String, Object> body, String key, int max) { String value = optional(body, key, max); if (value.isBlank()) throw new IllegalArgumentException(key + " is required"); return value; }
    private static String optional(Map<String, Object> body, String key, int max) { Object raw = body.get(key); String value = raw == null ? "" : String.valueOf(raw).strip(); if (value.length() > max || value.matches("(?s).*[\\p{Cntrl}&&[^\\r\\n\\t]].*")) throw new IllegalArgumentException(key + " is invalid"); return value; }
    private static String enumeration(Map<String, Object> body, String key, Set<String> allowed) { String value = required(body, key, 40).toUpperCase(Locale.ROOT); if (!allowed.contains(value)) throw new IllegalArgumentException(key + " is invalid"); return value; }
    private static String safeJson(Object value, int max) { try { byte[] bytes = JSON.writeValueAsBytes(value == null ? Map.of() : value); if (bytes.length > max) throw new IllegalArgumentException("application metadata is too large"); return new String(bytes, StandardCharsets.UTF_8); } catch (java.io.IOException error) { throw new IllegalArgumentException("metadata is invalid", error); } }
    private static String safeDiagnostics(Object value) {
        String json = safeJson(value, 16 * 1024);
        try {
            rejectForbiddenKeys(JSON.readTree(json));
            return json;
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("diagnostics metadata is invalid", error);
        }
    }
    private static void rejectForbiddenKeys(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) node.fields().forEachRemaining(field -> {
            String key = field.getKey().replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
            if (Set.of("password", "authorization", "bearer", "apikey", "refreshtoken", "accesstoken", "sql", "prompt").contains(key)) {
                throw new IllegalArgumentException("diagnostics contains a forbidden category");
            }
            rejectForbiddenKeys(field.getValue());
        });
        else if (node.isArray()) node.forEach(V110SupportStore::rejectForbiddenKeys);
    }
    private static IllegalStateException database(SQLException error) { return new IllegalStateException("Support database operation failed", error); }
    static final class RateLimitException extends RuntimeException { }
}
