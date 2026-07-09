package com.sqlteacher.application.nl2sql;

public record Nl2SqlPlan(
    String sqlDraft,
    String intent,
    String explanation,
    String model,
    String promptVersion
) {
}
