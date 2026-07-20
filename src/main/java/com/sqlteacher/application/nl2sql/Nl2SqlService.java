package com.sqlteacher.application.nl2sql;

@FunctionalInterface
public interface Nl2SqlService {
    Nl2SqlPlan generate(Nl2SqlRequest request);

    default SqlErrorExplanation explainSqlError(String connectionId, String sql, String errorMessage) {
        return SqlErrorExplanation.failure("SQL error explanation is unavailable", "");
    }
}
