package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.connection.DatabaseConnectionProfile;
import com.sqlteacher.application.connection.DatabaseConnectionTarget;
import com.sqlteacher.application.connection.DatabaseCredentialSession;
import com.sqlteacher.application.connection.DatabaseConnectionTestService;
import com.sqlteacher.application.connection.DatabaseDialect;
import com.sqlteacher.application.connection.FileDatabaseConnectionTarget;
import com.sqlteacher.application.connection.GenericJdbcConnectionTarget;
import com.sqlteacher.application.connection.ServerConnectionTarget;
import com.sqlteacher.application.connection.SqliteConnectionTarget;
import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.execution.SqlExecutionRequest;
import com.sqlteacher.application.execution.SqlExecutionResult;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.application.execution.SqlHistoryEntry;
import com.sqlteacher.application.execution.SqlHistoryService;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.risk.DeveloperSqlExecutionPolicy;
import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.application.risk.SqlSafetyModeService;
import com.sqlteacher.infrastructure.execution.SqlCsvExporter;
import com.sqlteacher.domain.SqlTeacherException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * v3.4.0 REF-8: connection management plus the Java-enforced SQL path. Risk analysis and the
 * execution text stay aligned; high-risk statements execute only behind a one-shot confirmation
 * token backed by {@link SqlConfirmationCache}. The model never touches a Connection.
 */
final class DataSqlApiSection extends ApiSection {

    private static final int RESULT_TTL_SECONDS = 600;
    private static final int CONFIRMATION_TTL_SECONDS = 300;

    private final SqlConfirmationCache sqlCache;

    DataSqlApiSection(ApiSectionHost host, SqlConfirmationCache sqlCache) {
        super(host);
        this.sqlCache = sqlCache;
    }

    @Override
    public Set<String> supportedMethods() {
        return Set.of(
            "data.connections", "data.connection.dialects", "data.connection.save",
            "data.connection.test", "data.connection.select", "data.connection.delete", "data.schema",
            "sql.analyze", "sql.execute", "sql.result.page", "sql.history", "sql.history.clear",
            "sql.result.export"
        );
    }

    @Override
    public JsonNode handle(String method, JsonNode params, CancellationToken cancellation,
                           Consumer<LocalAppEvent> events) throws Exception {
        return switch (method) {
            case "data.connections" -> dataConnections(cancellation);
            case "data.connection.dialects" -> dataConnectionDialects(cancellation);
            case "data.connection.save" -> dataConnectionSave(params, cancellation);
            case "data.connection.test" -> dataConnectionTest(params, cancellation);
            case "data.connection.select" -> dataConnectionSelect(params, cancellation);
            case "data.connection.delete" -> dataConnectionDelete(params, cancellation);
            case "data.schema" -> dataSchema(params, cancellation);
            case "sql.analyze" -> sqlAnalyze(params, cancellation);
            case "sql.execute" -> sqlExecute(params, cancellation);
            case "sql.result.page" -> sqlResultPage(params, cancellation);
            case "sql.history" -> sqlHistory(params, cancellation);
            case "sql.history.clear" -> sqlHistoryClear(cancellation);
            case "sql.result.export" -> sqlResultExport(params, cancellation);
            default -> throw new IllegalStateException("Method whitelist and dispatcher are inconsistent");
        };
    }

    private JsonNode dataConnections(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        ConnectionManagementService service = context().getBean(ConnectionManagementService.class);
        String current = service.currentProfile().map(profile -> profile.id()).orElse("");
        ArrayNode items = mapper.createArrayNode();
        service.listProfiles().forEach(profile -> {
            ObjectNode item = items.addObject();
            item.put("id", profile.id());
            item.put("displayName", profile.displayName());
            item.put("dialect", profile.dialect().name());
            item.put("readOnly", profile.readOnly());
            item.put("enabled", profile.enabled());
            item.put("builtIn", profile.builtIn());
            item.put("selected", profile.id().equals(current));
            appendConnectionTarget(item, profile.target());
        });
        return mapper.createObjectNode().set("items", items);
    }

    private JsonNode dataConnectionSave(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        DatabaseConnectionProfile profile = connectionProfile(params);
        return mapper.valueToTree(context().getBean(ConnectionManagementService.class).saveProfile(profile));
    }

    private JsonNode dataConnectionTest(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        DatabaseConnectionProfile profile = connectionProfile(params);
        char[] password = params.path("password").asText("").toCharArray();
        // 表单密码为空时回退到本进程已验证的会话凭据，让“仅测试/保存即测试”
        // 在编辑已有连接、未重新输入密码时也能走通。
        char[] working = resolveTestPassword(context().getBean(DatabaseCredentialSession.class), profile, password);
        try {
            var core = context();
            var result = core.getBean(DatabaseConnectionTestService.class).testConnection(profile, working);
            if (result.successful() && !profile.dialect().fileBased()) {
                core.getBean(DatabaseCredentialSession.class).remember(profile.id(), working);
            }
            cancellation.throwIfCancelled();
            return mapper.valueToTree(result);
        } finally {
            Arrays.fill(password, '\0');
            if (working != password) {
                Arrays.fill(working, '\0');
            }
        }
    }

    static char[] resolveTestPassword(
        DatabaseCredentialSession session,
        DatabaseConnectionProfile profile,
        char[] formPassword
    ) {
        if (formPassword.length > 0 || profile.dialect().fileBased()) {
            return formPassword;
        }
        return session.passwordFor(profile.id()).map(chars -> chars.clone()).orElse(formPassword);
    }

    private JsonNode dataConnectionDialects(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        ArrayNode items = mapper.createArrayNode();
        for (DatabaseDialect dialect : DatabaseDialect.values()) {
            ObjectNode item = items.addObject();
            item.put("name", dialect.name());
            item.put("displayName", dialect.displayName());
            item.put("defaultPort", dialect.defaultPort());
            item.put("fileBased", dialect.fileBased());
            item.put("generic", dialect.generic());
        }
        return mapper.createObjectNode().set("items", items);
    }

    private JsonNode dataConnectionSelect(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(ConnectionManagementService.class)
            .selectProfile(requiredText(params, "connectionId", 64)));
    }

    private JsonNode dataConnectionDelete(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String connectionId = requiredText(params, "connectionId", 64);
        var core = context();
        core.getBean(DatabaseCredentialSession.class).forget(connectionId);
        core.getBean(ConnectionManagementService.class).removeProfile(connectionId);
        return mapper.createObjectNode().put("deleted", true).put("connectionId", connectionId);
    }

    private DatabaseConnectionProfile connectionProfile(JsonNode params) {
        String id = requiredText(params, "id", 64);
        String displayName = requiredText(params, "displayName", 160);
        DatabaseDialect dialect = DatabaseDialect.valueOf(requiredText(params, "dialect", 32));
        DatabaseConnectionTarget target;
        if (dialect == DatabaseDialect.SQLITE) {
            target = new SqliteConnectionTarget(Path.of(requiredText(params, "databasePath", 4096)));
        } else if (dialect.fileBased()) {
            target = new FileDatabaseConnectionTarget(dialect,
                Path.of(requiredText(params, "databasePath", 4096)));
        } else if (dialect.generic()) {
            target = new GenericJdbcConnectionTarget(requiredText(params, "jdbcUrl", 4096),
                requiredText(params, "driverClass", 512),
                Path.of(requiredText(params, "driverJar", 4096)), params.path("username").asText(""));
        } else {
            target = new ServerConnectionTarget(dialect, requiredText(params, "host", 512),
                params.path("port").asInt(dialect.defaultPort()), requiredText(params, "databaseName", 512),
                requiredText(params, "username", 512));
        }
        return new DatabaseConnectionProfile(id, displayName, target,
            params.path("readOnly").asBoolean(), params.path("enabled").asBoolean(true), false);
    }

    private void appendConnectionTarget(ObjectNode item, DatabaseConnectionTarget target) {
        if (target instanceof SqliteConnectionTarget sqlite) {
            item.put("databasePath", sqlite.databasePath().toString());
        } else if (target instanceof FileDatabaseConnectionTarget file) {
            item.put("databasePath", file.databasePath().toString());
        } else if (target instanceof ServerConnectionTarget server) {
            item.put("host", server.host());
            item.put("port", server.port());
            item.put("databaseName", server.databaseName());
            item.put("username", server.username());
        } else if (target instanceof GenericJdbcConnectionTarget generic) {
            item.put("jdbcUrl", generic.jdbcUrl());
            item.put("driverClass", generic.driverClass());
            item.put("driverJar", generic.driverJar().toString());
            item.put("username", generic.username());
        }
    }

    private JsonNode dataSchema(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String connectionId = requiredText(params, "connectionId", 64);
        var tables = context().getBean(DatabaseMetadataService.class).listTables(connectionId);
        return mapper.createObjectNode().set("tables", mapper.valueToTree(tables));
    }

    private JsonNode sqlAnalyze(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        expireSqlState();
        String connectionId = requiredText(params, "connectionId", 64);
        String sql = requiredText(params, "sql", 256 * 1024);
        ConnectionManagementService connections = context().getBean(ConnectionManagementService.class);
        var profile = connections.findProfile(connectionId)
            .orElseThrow(() -> new IllegalArgumentException("Database connection does not exist"));
        // Apply the same safety-mode policy as SqlExecutionService so the preview the user
        // confirms and the gate that executes the statement always agree.
        SqlRiskAnalysis risk = analyzedRisk(sql, profile.dialect());
        ObjectNode result = mapper.valueToTree(risk);
        if (risk.executable() && risk.confirmationRequired()) {
            String token = UUID.randomUUID().toString();
            sqlCache.rememberConfirmation(token, new SqlConfirmationCache.Confirmation(
                connectionId, SqlConfirmationCache.hash(sql), Instant.now().plusSeconds(CONFIRMATION_TTL_SECONDS)));
            result.put("confirmationToken", token);
            result.put("confirmationExpiresAt", Instant.now().plusSeconds(CONFIRMATION_TTL_SECONDS).toString());
            // W6.1：令牌签发处记事件，闭环审计高风险确认链路。
            context().getBean(LearningEventService.class)
                .recordSqlConfirmationIssued(connectionId, risk.statementType());
        }
        result.put("enforcedBy", "java");
        result.put("maxRows", 500);
        result.put("timeoutSeconds", 10);
        return result;
    }

    private JsonNode sqlExecute(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        expireSqlState();
        String connectionId = requiredText(params, "connectionId", 64);
        String sql = requiredText(params, "sql", 256 * 1024);
        String confirmationToken = params.path("confirmationToken").asText("").trim();
        ConnectionManagementService connections = context().getBean(ConnectionManagementService.class);
        var profile = connections.findProfile(connectionId)
            .orElseThrow(() -> new IllegalArgumentException("Database connection does not exist"));
        SqlRiskAnalysis risk = analyzedRisk(sql, profile.dialect());
        if (!risk.executable()) throw new IllegalArgumentException("SQL is blocked by Java risk analysis");
        boolean confirmed = false;
        if (risk.confirmationRequired()) {
            SqlConfirmationCache.Confirmation confirmation = sqlCache.takeConfirmation(confirmationToken);
            confirmed = confirmation != null && confirmation.expiresAt().isAfter(Instant.now())
                && confirmation.connectionId().equals(connectionId)
                && confirmation.sqlHash().equals(SqlConfirmationCache.hash(sql));
            if (!confirmed) {
                // W6.1：消费失败（取消/过期/令牌不符）记取消事件。
                context().getBean(LearningEventService.class)
                    .recordSqlConfirmationCancelled(connectionId, "TOKEN_INVALID_OR_EXPIRED");
                throw new IllegalArgumentException("A current confirmation token is required");
            }
            context().getBean(LearningEventService.class)
                .recordSqlConfirmed(connectionId, risk.statementType());
        }
        int maxRows = Math.clamp(params.path("maxRows").asInt(500), 1, 500);
        SqlExecutionResult execution = context().getBean(SqlExecutionService.class).execute(
            new SqlExecutionRequest(connectionId, sql, maxRows, Duration.ofSeconds(10), confirmed));
        cancellation.throwIfCancelled();
        recordSqlHistory(connectionId, sql, execution);
        String resultId = UUID.randomUUID().toString();
        rememberSqlResult(resultId, new SqlConfirmationCache.CachedResult(
            execution, Instant.now().plusSeconds(RESULT_TTL_SECONDS)));
        return sqlPage(resultId, execution, 0, Math.clamp(params.path("pageSize").asInt(50), 1, 100));
    }

    private SqlRiskAnalysis analyzedRisk(String sql, com.sqlteacher.application.connection.DatabaseDialect dialect) {
        boolean developerMode = context().getBean(SqlSafetyModeService.class).isDeveloperModeEnabled();
        return DeveloperSqlExecutionPolicy.apply(
            context().getBean(SqlRiskAnalysisService.class).analyze(sql, dialect), developerMode);
    }

    private void rememberSqlResult(String resultId, SqlConfirmationCache.CachedResult cached) {
        sqlCache.rememberResult(resultId, cached, this::expireSqlState);
    }

    private JsonNode sqlResultPage(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        expireSqlState();
        String resultId = requiredText(params, "resultId", 128);
        SqlConfirmationCache.CachedResult cached = sqlCache.result(resultId);
        if (cached == null) throw new IllegalArgumentException("SQL result page has expired");
        return sqlPage(resultId, cached.result(), Math.max(0, params.path("page").asInt(0)),
            Math.clamp(params.path("pageSize").asInt(50), 1, 100));
    }

    private void recordSqlHistory(String connectionId, String sql, SqlExecutionResult execution) {
        try {
            int effectiveRows = execution.rows().isEmpty()
                ? execution.affectedRows()
                : execution.rows().size();
            context().getBean(SqlHistoryService.class).record(new SqlHistoryEntry(
                connectionId,
                "",
                sql,
                execution.success(),
                effectiveRows,
                execution.duration().toMillis(),
                Instant.now()
            ));
        } catch (RuntimeException error) {
            // 历史是非关键旁路，记录失败不影响已完成的执行结果。
        }
    }

    private JsonNode sqlHistory(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        List<SqlHistoryEntry> entries = context().getBean(SqlHistoryService.class)
            .list(params.path("limit").asInt(50));
        ArrayNode items = mapper.createArrayNode();
        for (SqlHistoryEntry entry : entries) {
            ObjectNode item = items.addObject();
            item.put("connectionId", entry.connectionId());
            item.put("connectionName", entry.connectionName());
            item.put("sql", entry.sqlText());
            item.put("successful", entry.successful());
            item.put("rowCount", entry.rowCount());
            item.put("durationMillis", entry.durationMillis());
            item.put("createdAt", entry.createdAt().toString());
        }
        return mapper.createObjectNode().set("items", items);
    }

    private JsonNode sqlHistoryClear(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        context().getBean(SqlHistoryService.class).clear();
        return mapper.createObjectNode().put("cleared", true);
    }

    private JsonNode sqlResultExport(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        expireSqlState();
        String resultId = requiredText(params, "resultId", 128);
        SqlConfirmationCache.CachedResult cached = sqlCache.result(resultId);
        if (cached == null) throw new IllegalArgumentException("SQL result page has expired");
        String path = requiredText(params, "path", 4096);
        // 导出目标必须显式为 .csv，且父目录已存在（W5.3 路径加固）。
        Path target = Path.of(path).toAbsolutePath().normalize();
        if (!target.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) {
            throw new IllegalArgumentException("导出文件必须是 .csv 扩展名");
        }
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IllegalArgumentException("导出目录不存在，请重新选择保存位置");
        }
        try {
            Files.writeString(
                target,
                SqlCsvExporter.toCsv(cached.result().columns(), cached.result().rows()),
                StandardCharsets.UTF_8
            );
        } catch (IOException error) {
            throw new SqlTeacherException("SQL_EXPORT_FAILED", "无法写入 CSV 文件，请检查保存位置。");
        }
        cancellation.throwIfCancelled();
        return mapper.createObjectNode()
            .put("rows", cached.result().rows().size())
            .put("columns", cached.result().columns().size());
    }

    private ObjectNode sqlPage(String resultId, SqlExecutionResult result, int page, int pageSize) {
        int from = Math.min(page * pageSize, result.rows().size());
        int to = Math.min(from + pageSize, result.rows().size());
        ObjectNode response = mapper.createObjectNode();
        response.put("resultId", resultId);
        response.put("success", result.success());
        response.set("columns", mapper.valueToTree(result.columns()));
        response.set("rows", mapper.valueToTree(result.rows().subList(from, to)));
        response.put("page", page);
        response.put("pageSize", pageSize);
        response.put("totalRows", result.rows().size());
        response.put("hasMore", to < result.rows().size());
        response.put("affectedRows", result.affectedRows());
        response.put("truncated", result.truncated());
        response.put("message", result.message());
        response.put("durationMillis", result.duration().toMillis());
        response.put("auditRecorded", true);
        return response;
    }

    private void expireSqlState() {
        List<String> expiredConnections = sqlCache.expire();
        if (!expiredConnections.isEmpty()) {
            LearningEventService events = context().getBean(LearningEventService.class);
            for (String connectionId : expiredConnections) {
                events.recordSqlConfirmationCancelled(connectionId, "EXPIRED");
            }
        }
    }
}
