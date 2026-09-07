package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.connection.DatabaseConnectionProfile;
import com.sqlteacher.application.connection.DatabaseConnectionTestResult;
import com.sqlteacher.application.connection.DatabaseConnectionTestService;
import com.sqlteacher.application.connection.FileDatabaseConnectionTarget;
import com.sqlteacher.application.connection.SqliteConnectionTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Objects;

public final class JdbcDatabaseConnectionTestService implements DatabaseConnectionTestService {
    private static final Logger log = LoggerFactory.getLogger(JdbcDatabaseConnectionTestService.class);
    private static final String SUCCESS_MESSAGE = "连接成功。";
    private static final String FAILURE_MESSAGE = "连接失败，请检查数据库地址、凭据和服务状态。";
    private static final String DISABLED_MESSAGE = "连接配置已禁用，请启用后重试。";

    private final JdbcConnectionFactory connectionFactory;
    private final Duration timeout;

    public JdbcDatabaseConnectionTestService(JdbcConnectionFactory connectionFactory, Duration timeout) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory must not be null");
        this.timeout = requirePositive(timeout);
    }

    @Override
    public DatabaseConnectionTestResult testConnection(
        DatabaseConnectionProfile profile,
        char[] password
    ) {
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(password, "password must not be null");
        long startedAt = System.nanoTime();
        if (!profile.enabled()) {
            return failure(DISABLED_MESSAGE, startedAt);
        }

        // 文件型数据库的 JDBC 驱动会在打开连接时静默创建缺失的库文件，所以
        // 必须在 open 之前判断文件是否存在；提示避免教师因路径笔误得到一个
        // “连接成功”的空库而不自知。消息不含路径本身。
        boolean fileWillBeCreated = fileBasedTargetMissing(profile);
        try (Connection connection = connectionFactory.open(profile, password, timeout)) {
            DatabaseMetaData metadata = connection.getMetaData();
            return new DatabaseConnectionTestResult(
                true,
                fileWillBeCreated
                    ? SUCCESS_MESSAGE + "数据库文件当前不存在，首次使用将创建新的空数据库。"
                    : SUCCESS_MESSAGE,
                safeMetadata(metadata.getDatabaseProductName()),
                safeMetadata(metadata.getDatabaseProductVersion()),
                elapsedSince(startedAt)
            );
        } catch (Exception error) {
            JdbcFailureClassifier.JdbcFailure failure = JdbcFailureClassifier.classify(error);
            log.warn(
                "Database connection test failed, connectionId={}, dialect={}, failureType={}, sqlState={}, vendorCode={}",
                profile.id(),
                profile.dialect(),
                failure,
                JdbcFailureClassifier.sqlState(error),
                JdbcFailureClassifier.vendorCode(error)
            );
            return failure(connectionTestMessage(failure), startedAt);
        }
    }

    private static boolean fileBasedTargetMissing(DatabaseConnectionProfile profile) {
        if (profile.target() instanceof SqliteConnectionTarget target) {
            return Files.notExists(target.databasePath());
        }
        return profile.target() instanceof FileDatabaseConnectionTarget target
            && Files.notExists(target.databasePath());
    }

    private static DatabaseConnectionTestResult failure(String message, long startedAt) {
        return new DatabaseConnectionTestResult(
            false,
            message,
            "",
            "",
            elapsedSince(startedAt)
        );
    }

    private static String connectionTestMessage(JdbcFailureClassifier.JdbcFailure failure) {
        return failure == JdbcFailureClassifier.JdbcFailure.SQL ? FAILURE_MESSAGE : failure.userMessage();
    }

    private static String safeMetadata(String value) {
        return value == null ? "" : value.trim();
    }

    private static Duration elapsedSince(long startedAt) {
        long elapsedNanos = Math.max(0, System.nanoTime() - startedAt);
        return Duration.ofNanos(elapsedNanos);
    }

    private static Duration requirePositive(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return timeout;
    }
}
