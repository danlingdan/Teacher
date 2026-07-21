package com.sqlteacher;

import com.sqlteacher.application.analytics.AnalyticsFilter;
import com.sqlteacher.application.analytics.LearningAnalyticsService;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.database.DatabaseInitializationService;
import com.sqlteacher.application.execution.SqlExecutionRequest;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.application.knowledge.KnowledgeDocumentService;
import com.sqlteacher.application.knowledge.KnowledgeSearchService;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.infrastructure.spring.SqlTeacherApplicationConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Comparator;

public final class ReleaseVerificationApp {
    private static final long STARTUP_LIMIT_MS = 5_000;
    private static final long OPERATION_LIMIT_MS = 2_000;

    private ReleaseVerificationApp() {
    }

    public static void main(String[] args) throws Exception {
        Path workspace = Files.createTempDirectory("sqlteacher-release-verification-");
        String previousDataDirectory = System.getProperty("sqlteacher.data.dir");
        System.setProperty("sqlteacher.data.dir", workspace.toString());
        try {
            long startupStarted = System.nanoTime();
            try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(SqlTeacherApplicationConfig.class)) {
                context.getBean(DatabaseInitializationService.class).initialize();
                long startupMs = elapsedMillis(startupStarted);
                SqlTeacherConfiguration configuration = context.getBean(SqlTeacherConfiguration.class);
                populateFiveHundredRows(configuration.database().demoDatabasePath());

                long metadataStarted = System.nanoTime();
                int tableCount = context.getBean(DatabaseMetadataService.class).listTables("demo").size();
                long metadataMs = elapsedMillis(metadataStarted);

                long queryStarted = System.nanoTime();
                var query = context.getBean(SqlExecutionService.class).execute(
                    new SqlExecutionRequest("demo", "select id, name, score from student order by id", 500,
                        Duration.ofSeconds(5))
                );
                long queryMs = elapsedMillis(queryStarted);

                long analyticsStarted = System.nanoTime();
                context.getBean(LearningAnalyticsService.class).analyze(AnalyticsFilter.all());
                long analyticsMs = elapsedMillis(analyticsStarted);

                Path knowledgeFile = workspace.resolve("release-verification.md");
                Files.writeString(knowledgeFile, "# SQL 聚合\nGROUP BY 用于分组，HAVING 用于过滤聚合结果。\n");
                KnowledgeDocumentService documents = context.getBean(KnowledgeDocumentService.class);
                KnowledgeSearchService search = context.getBean(KnowledgeSearchService.class);
                documents.importDocument(knowledgeFile);
                long searchStarted = System.nanoTime();
                int searchResults = search.search("GROUP BY", 5).size();
                long searchMs = elapsedMillis(searchStarted);

                check("startup", startupMs, STARTUP_LIMIT_MS, true);
                check("metadata", metadataMs, OPERATION_LIMIT_MS, tableCount > 0);
                check("500-row query", queryMs, OPERATION_LIMIT_MS, query.success() && query.rows().size() == 500);
                check("analytics", analyticsMs, OPERATION_LIMIT_MS, true);
                check("local search", searchMs, OPERATION_LIMIT_MS, searchResults > 0);
            }
        } finally {
            if (previousDataDirectory == null) {
                System.clearProperty("sqlteacher.data.dir");
            } else {
                System.setProperty("sqlteacher.data.dir", previousDataDirectory);
            }
            deleteTree(workspace);
        }
    }

    private static void populateFiveHundredRows(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(
                "insert into student(id, name, score) values (?, ?, ?)")) {
                for (int id = 3; id <= 500; id++) {
                    statement.setInt(1, id);
                    statement.setString(2, "Student " + id);
                    statement.setInt(3, id % 101);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            connection.commit();
        }
    }

    private static void check(String name, long elapsedMs, long limitMs, boolean correct) {
        boolean passed = correct && elapsedMs <= limitMs;
        System.out.printf("[%s] %s - %d ms (limit %d ms)%n", passed ? "PASS" : "FAIL", name, elapsedMs, limitMs);
        if (!passed) {
            throw new IllegalStateException("Release performance verification failed: " + name);
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private static void deleteTree(Path root) {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // Temporary release verification data is best-effort cleanup only.
                }
            });
        } catch (Exception ignored) {
            // Temporary release verification data is best-effort cleanup only.
        }
    }
}
