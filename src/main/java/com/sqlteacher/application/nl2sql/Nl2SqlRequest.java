package com.sqlteacher.application.nl2sql;

public record Nl2SqlRequest(
    String naturalLanguage,
    String connectionId
) {
}
