package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.execution.SqlExecutionRequest;
import com.sqlteacher.application.execution.SqlExecutionResult;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.domain.SqlTeacherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final SqlRiskAnalysisService riskAnalysisService;
    private static final Logger log = LoggerFactory.getLogger(JdbcSqlExecutionService.class);

    public JdbcSqlExecutionService(
            JdbcConnectionFactory connectionFactory,
            SqlResultMapper resultMapper,
            SqlRiskAnalysisService riskAnalysisService
    ) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory);
        this.resultMapper = Objects.requireNonNull(resultMapper);
        this.riskAnalysisService = Objects.requireNonNull(riskAnalysisService);
    }

    @Override
    public SqlExecutionResult execute(SqlExecutionRequest request) {
        
        validate(request);

         log.debug("Executing SQL on connection '{}': {}",
                request.connectionId(),
                request.sql().length() > 200 ? request.sql().substring(0, 200) + "..." : request.sql()
        );

        SqlRiskAnalysis risk = riskAnalysisService.analyze(request.sql());

        log.debug("Risk analysis result: level={}, executable={}, confirmationRequired={}, type={}",
                risk.level(),
                risk.executable(),
                risk.confirmationRequired(),
                risk.statementType()
        );

        if (!risk.executable()) {
            throw new SqlTeacherException(
                    "SQL_BLOCKED",
                    risk.reasons().isEmpty()
                            ? "SQL execution blocked."
                            : risk.reasons().getFirst()
            );
        }

        if (risk.confirmationRequired() && !request.riskConfirmed()) {
            throw new SqlTeacherException(
                    "SQL_CONFIRMATION_REQUIRED",
                    "This SQL requires user confirmation before execution."
            );
        }

        Instant start = Instant.now();

        log.debug("Starting SQL execution");

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
                    log.debug("Query execution completed in {} ms", duration.toMillis());
                    return resultMapper.mapQueryResult(
                            resultSet,
                            duration,
                            request.maxRows()
                    );

                }

            }

            log.debug("Update execution completed in {} ms, affectedRows={}", duration.toMillis(), statement.getUpdateCount());
            return resultMapper.mapUpdateResult(
                    statement.getUpdateCount(),
                    duration
            );

        } catch (SQLException exception) {
            log.error("SQL execution failed on connection '{}': {}",
                    request.connectionId(),
                    exception.getMessage(),
                    exception
            );
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
