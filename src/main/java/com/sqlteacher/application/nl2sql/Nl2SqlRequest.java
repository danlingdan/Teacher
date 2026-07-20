package com.sqlteacher.application.nl2sql;

import com.sqlteacher.application.connection.DatabaseDialect;

import java.util.Objects;

public record Nl2SqlRequest(
    String naturalLanguage,
    String connectionId,
    DatabaseDialect dialect
) {
    public Nl2SqlRequest {
        dialect = Objects.requireNonNull(dialect, "dialect");
    }

    public Nl2SqlRequest(String naturalLanguage, String connectionId) {
        this(naturalLanguage, connectionId, DatabaseDialect.SQLITE);
    }
}
