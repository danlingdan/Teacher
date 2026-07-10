package com.sqlteacher.application.mock;

import com.sqlteacher.application.nl2sql.Nl2SqlPlan;
import com.sqlteacher.application.nl2sql.Nl2SqlRequest;
import com.sqlteacher.application.nl2sql.Nl2SqlService;

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
}
