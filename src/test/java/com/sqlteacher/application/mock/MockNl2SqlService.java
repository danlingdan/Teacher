package com.sqlteacher.application.mock;

import com.sqlteacher.application.nl2sql.Nl2SqlPlan;
import com.sqlteacher.application.nl2sql.Nl2SqlRequest;
import com.sqlteacher.application.nl2sql.Nl2SqlService;
import com.sqlteacher.application.nl2sql.SqlErrorExplanation;

import java.util.Objects;

public final class MockNl2SqlService implements Nl2SqlService {
    @Override
    public Nl2SqlPlan generate(Nl2SqlRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.naturalLanguage() == null || request.naturalLanguage().isBlank()) {
            throw new IllegalArgumentException("naturalLanguage must not be blank");
        }
        if (request.connectionId() == null || request.connectionId().isBlank()) {
            throw new IllegalArgumentException("connectionId must not be blank");
        }

        return new Nl2SqlPlan(
            "SELECT id, name, score FROM student ORDER BY id LIMIT 100",
            "QUERY",
            "Mock draft: list students in identifier order.",
            "mock-model",
            "mock-v1"
        );
    }

    @Override
    public SqlErrorExplanation explainSqlError(String connectionId, String sql, String errorMessage) {
        Objects.requireNonNull(connectionId, "connectionId must not be null");
        Objects.requireNonNull(sql, "sql must not be null");
        Objects.requireNonNull(errorMessage, "errorMessage must not be null");
        if (connectionId.isBlank()) {
            throw new IllegalArgumentException("connectionId must not be blank");
        }
        if (sql.isBlank()) {
            throw new IllegalArgumentException("sql must not be blank");
        }
        if (errorMessage.isBlank()) {
            throw new IllegalArgumentException("errorMessage must not be blank");
        }

        return SqlErrorExplanation.success(
            "Mock error cause: " + errorMessage,
            "Mock suggestion: check your SQL syntax",
            sql + " LIMIT 500",
            "mock-model"
        );
    }
}
