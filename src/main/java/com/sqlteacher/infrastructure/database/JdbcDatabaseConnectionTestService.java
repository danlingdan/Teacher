package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.connection.DatabaseConnectionProfile;
import com.sqlteacher.application.connection.DatabaseConnectionTestResult;
import com.sqlteacher.application.connection.DatabaseConnectionTestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
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

        try (Connection connection = connectionFactory.open(profile, password, timeout)) {
            DatabaseMetaData metadata = connection.getMetaData();
            return new DatabaseConnectionTestResult(
                true,
                SUCCESS_MESSAGE,
                safeMetadata(metadata.getDatabaseProductName()),
                safeMetadata(metadata.getDatabaseProductVersion()),
                elapsedSince(startedAt)
            );
        } catch (Exception error) {
            log.warn(
                "Database connection test failed, connectionId={}, dialect={}, failureType={}",
                profile.id(),
                profile.dialect(),
                error.getClass().getSimpleName()
            );
            return failure(FAILURE_MESSAGE, startedAt);
        }
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
