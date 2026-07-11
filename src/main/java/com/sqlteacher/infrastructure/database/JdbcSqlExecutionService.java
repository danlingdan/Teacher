package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.execution.SqlExecutionRequest;
import com.sqlteacher.application.execution.SqlExecutionResult;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.domain.SqlTeacherException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class JdbcSqlExecutionService implements SqlExecutionService {
    private final JdbcConnectionFactory connectionFactory;
    private final SqlResultMapper resultMapper;

    public JdbcSqlExecutionService(
            JdbcConnectionFactory connectionFactory,
            SqlResultMapper resultMapper
    ) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory);
        this.resultMapper = Objects.requireNonNull(resultMapper);
    }

    @Override
    public SqlExecutionResult execute(SqlExecutionRequest request) {

        validate(request);

        Instant start = Instant.now();

        try (
                Connection connection =
                        connectionFactory.open(request.connectionId());

                Statement statement =
                        connection.createStatement()
        ) {

            statement.setQueryTimeout(
                    (int) request.timeout().toSeconds()
            );

            statement.setMaxRows(request.maxRows());

            boolean hasResult =
                    statement.execute(request.sql());

            Duration duration =
                    Duration.between(start, Instant.now());

            if (hasResult) {

                try (ResultSet resultSet = statement.getResultSet()) {

                    return resultMapper.mapQueryResult(
                            resultSet,
                            duration,
                            request.maxRows()
                    );

                }

            }

            return resultMapper.mapUpdateResult(
                    statement.getUpdateCount(),
                    duration
            );

        } catch (SQLException exception) {

            throw new SqlTeacherException(
                    "SQL_EXECUTION_FAILED",
                    exception.getMessage(),
                    exception
            );

        }

    }

    private static void validate(SqlExecutionRequest request) {

        Objects.requireNonNull(request, "request must not be null");

        if (request.connectionId() == null ||
                request.connectionId().isBlank()) {

            throw new IllegalArgumentException(
                    "connectionId must not be blank"
            );
        }

        if (request.sql() == null ||
                request.sql().isBlank()) {

            throw new IllegalArgumentException(
                    "sql must not be blank"
            );
        }

        if (request.maxRows() <= 0) {

            throw new IllegalArgumentException(
                    "maxRows must be positive"
            );
        }

        if (request.timeout() == null ||
                request.timeout().isNegative() ||
                request.timeout().isZero()) {

            throw new IllegalArgumentException(
                    "timeout must be positive"
            );
        }

    }

}
