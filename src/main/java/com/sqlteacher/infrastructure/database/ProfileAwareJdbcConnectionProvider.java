package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.connection.DatabaseConnectionProfile;
import com.sqlteacher.application.connection.DatabaseCredentialSession;
import com.sqlteacher.application.connection.ServerConnectionTarget;
import com.sqlteacher.domain.SqlTeacherException;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

/** Opens JDBC connections from persisted non-sensitive profiles. */
public final class ProfileAwareJdbcConnectionProvider implements JdbcConnectionProvider {
    private final JdbcConnectionFactory connectionFactory;
    private final ConnectionManagementService connectionManagementService;
    private final DatabaseCredentialSession credentialSession;

    public ProfileAwareJdbcConnectionProvider(
        JdbcConnectionFactory connectionFactory,
        ConnectionManagementService connectionManagementService,
        DatabaseCredentialSession credentialSession
    ) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory must not be null");
        this.connectionManagementService = Objects.requireNonNull(
            connectionManagementService,
            "connectionManagementService must not be null"
        );
        this.credentialSession = Objects.requireNonNull(credentialSession, "credentialSession must not be null");
    }

    @Override
    public Connection open(String connectionId, Duration timeout) throws SQLException {
        DatabaseConnectionProfile profile = requireProfile(connectionId);
        if (!profile.enabled()) {
            throw new SqlTeacherException(
                "DATABASE_CONNECTION_DISABLED",
                "所选数据库连接已禁用，请在设置页启用或切换连接。"
            );
        }
        if (profile.target() instanceof ServerConnectionTarget) {
            if (!profile.readOnly()) {
                throw new SqlTeacherException(
                    "EXTERNAL_CONNECTION_READ_ONLY_REQUIRED",
                    "外部数据库连接必须启用只读模式。"
                );
            }
            char[] password = credentialSession.passwordFor(profile.id()).orElseThrow(() -> new SqlTeacherException(
                "DATABASE_CREDENTIALS_REQUIRED",
                "服务器连接缺少临时凭据，请先在设置页测试连接。"
            ));
            try {
                return connectionFactory.open(profile, password, timeout);
            } finally {
                Arrays.fill(password, '\0');
            }
        }
        return connectionFactory.open(profile, new char[0], timeout);
    }

    @Override
    public boolean isReadOnly(String connectionId) {
        return requireProfile(connectionId).readOnly();
    }

    private DatabaseConnectionProfile requireProfile(String connectionId) {
        return connectionManagementService.findProfile(connectionId)
            .orElseThrow(() -> new SqlTeacherException(
                "DATABASE_CONNECTION_NOT_FOUND",
                "找不到所选数据库连接，请在设置页重新选择。"
            ));
    }
}
