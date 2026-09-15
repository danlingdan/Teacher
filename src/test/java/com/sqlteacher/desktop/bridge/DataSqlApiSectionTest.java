package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.connection.DatabaseConnectionProfile;
import com.sqlteacher.application.connection.DatabaseDialect;
import com.sqlteacher.application.connection.SqliteConnectionTarget;
import com.sqlteacher.application.execution.SqlExecutionRequest;
import com.sqlteacher.application.execution.SqlExecutionResult;
import com.sqlteacher.application.execution.SqlExecutionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.4.0 REF-8: the data/SQL section's result paging and export path hardening work without a
 * Spring core: the cache is pre-populated directly, proving the section logic is unit-testable.
 */
class DataSqlApiSectionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDirectory;

    private static ApiSectionHost hostWithoutCore() {
        return new ApiSectionHost() {
            @Override public ObjectMapper mapper() {
                return new ObjectMapper();
            }

            @Override public org.springframework.context.annotation.AnnotationConfigApplicationContext context() {
                throw new IllegalStateException("test host has no Spring core");
            }

            @Override public boolean coreInitialized() {
                return false;
            }
        };
    }

    private static SqlExecutionResult result(int rows) {
        return new SqlExecutionResult(
            true,
            List.of("Sno", "Sname"),
            java.util.stream.IntStream.range(0, rows)
                .mapToObj(i -> Map.<String, Object>of("Sno", String.valueOf(20180000 + i), "Sname", "n" + i))
                .toList(),
            0, false, "", Duration.ofMillis(5));
    }

    private DataSqlApiSection sectionWithCachedResult(SqlConfirmationCache cache, String resultId) {
        cache.rememberResult(resultId, new SqlConfirmationCache.CachedResult(
            result(7), Instant.now().plusSeconds(600)), () -> { });
        return new DataSqlApiSection(hostWithoutCore(), cache);
    }

    @Test
    void sqlResultPageSlicesAndShapesWithoutCore() throws Exception {
        DataSqlApiSection section = sectionWithCachedResult(new SqlConfirmationCache(), "result-1");

        JsonNode page = section.handle("sql.result.page", mapper.createObjectNode()
            .put("resultId", "result-1").put("page", 1).put("pageSize", 3), () -> false, ignored -> { });

        assertEquals("result-1", page.path("resultId").asText());
        assertEquals(1, page.path("page").asInt());
        assertEquals(3, page.path("pageSize").asInt());
        assertEquals(7, page.path("totalRows").asInt());
        assertTrue(page.path("hasMore").asBoolean());
        assertEquals(3, page.path("rows").size());
        assertEquals("n3", page.path("rows").get(0).path("Sname").asText());
        assertEquals(true, page.path("auditRecorded").asBoolean());

        // 7 rows, pageSize 3: last full page has 1 row and no continuation.
        JsonNode lastPage = section.handle("sql.result.page", mapper.createObjectNode()
            .put("resultId", "result-1").put("page", 2).put("pageSize", 3), () -> false, ignored -> { });
        assertEquals(1, lastPage.path("rows").size());
        assertFalse(lastPage.path("hasMore").asBoolean());

        // A page beyond the end is empty but well-formed.
        JsonNode emptyPage = section.handle("sql.result.page", mapper.createObjectNode()
            .put("resultId", "result-1").put("page", 9).put("pageSize", 4), () -> false, ignored -> { });
        assertEquals(0, emptyPage.path("rows").size());
        assertFalse(emptyPage.path("hasMore").asBoolean());
    }

    @Test
    void sqlResultPageRejectsUnknownResultIds() {
        DataSqlApiSection section = new DataSqlApiSection(hostWithoutCore(), new SqlConfirmationCache());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> section.handle("sql.result.page", mapper.createObjectNode()
                .put("resultId", "missing"), () -> false, ignored -> { }));
        assertEquals("SQL result page has expired", error.getMessage());
    }

    /** v3.5.0 SCH-2：样例预览只收表名，SQL 由 Java 侧构造为只读 SELECT LIMIT，参数可控可断言。 */
    @Test
    void dataTableSampleBuildsReadOnlySelectFromTableNameOnly() throws Exception {
        AtomicReference<SqlExecutionRequest> captured = new AtomicReference<>();
        SqlExecutionServiceFake execution = new SqlExecutionServiceFake(request -> {
            captured.set(request);
            return result(2);
        });
        ConnectionManagementService connections = new ConnectionManagementService() {
            @Override public List<DatabaseConnectionProfile> listProfiles() { return List.of(); }
            @Override public Optional<DatabaseConnectionProfile> findProfile(String connectionId) {
                return Optional.of(new DatabaseConnectionProfile(
                    "demo", "演示库", new SqliteConnectionTarget(Path.of("demo.db")), false, true, true));
            }
            @Override public DatabaseConnectionProfile saveProfile(DatabaseConnectionProfile profile) { return profile; }
            @Override public void removeProfile(String connectionId) { }
            @Override public Optional<DatabaseConnectionProfile> currentProfile() { return Optional.empty(); }
            @Override public DatabaseConnectionProfile selectProfile(String connectionId) { throw new UnsupportedOperationException(); }
        };
        DataSqlApiSection section = new DataSqlApiSection(
            ApiSectionTestSupport.hostWithBeans(connections, execution), new SqlConfirmationCache());

        JsonNode sample = section.handle("data.table.sample", mapper.createObjectNode()
            .put("connectionId", "demo").put("table", "Student"), () -> false, ignored -> { });

        SqlExecutionRequest request = captured.get();
        assertEquals("SELECT * FROM \"Student\" LIMIT 5", request.sql());
        assertEquals(5, request.maxRows());
        assertTrue(request.riskConfirmed());
        assertEquals(2, sample.path("rows").size());
        assertEquals(2, sample.path("columns").size());
        // 越界字符的表名在到达执行服务前就被拒绝。
        var rejected = assertThrows(IllegalArgumentException.class,
            () -> section.handle("data.table.sample", mapper.createObjectNode()
                .put("connectionId", "demo").put("table", "Student; DROP TABLE Student"), () -> false, ignored -> { }));
        assertEquals("Table name contains unsupported characters", rejected.getMessage());
    }

    @Test
    void sqlResultExportEnforcesCsvExtensionAndExistingParentDirectory() throws Exception {        DataSqlApiSection section = sectionWithCachedResult(new SqlConfirmationCache(), "result-1");

        var notCsv = assertThrows(IllegalArgumentException.class,
            () -> section.handle("sql.result.export", mapper.createObjectNode()
                .put("resultId", "result-1")
                .put("path", tempDirectory.resolve("export.txt").toString()), () -> false, ignored -> { }));
        assertEquals("导出文件必须是 .csv 扩展名", notCsv.getMessage());

        var missingParent = assertThrows(IllegalArgumentException.class,
            () -> section.handle("sql.result.export", mapper.createObjectNode()
                .put("resultId", "result-1")
                .put("path", tempDirectory.resolve("no-such-dir").resolve("out.csv").toString()),
                () -> false, ignored -> { }));
        assertEquals("导出目录不存在，请重新选择保存位置", missingParent.getMessage());

        Path target = tempDirectory.resolve("out").resolve("students.csv");
        Files.createDirectories(target.getParent());
        SqlConfirmationCache cache = new SqlConfirmationCache();
        cache.rememberResult("result-1", new SqlConfirmationCache.CachedResult(
            result(2), Instant.now().plusSeconds(600)), () -> { });
        DataSqlApiSection section2 = new DataSqlApiSection(hostWithoutCore(), cache);
        JsonNode exported = section2.handle("sql.result.export", mapper.createObjectNode()
            .put("resultId", "result-1").put("path", target.toString()), () -> false, ignored -> { });

        assertEquals(2, exported.path("rows").asInt());
        assertEquals(2, exported.path("columns").asInt());
        // The exporter writes a UTF-8 BOM before the header (see SqlResultExportFlowTest).
        assertTrue(Files.readString(target).startsWith("\uFEFFSno,Sname"));
    }

    /** 捕获请求的假执行服务：断言 Java 侧构造的语句与限额，而不真正连接数据库。 */
    private record SqlExecutionServiceFake(
        java.util.function.Function<SqlExecutionRequest, SqlExecutionResult> handler
    ) implements SqlExecutionService {
        @Override
        public SqlExecutionResult execute(SqlExecutionRequest request) {
            return handler.apply(request);
        }
    }
}
