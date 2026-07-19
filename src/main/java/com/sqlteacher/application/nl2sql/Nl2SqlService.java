package com.sqlteacher.application.nl2sql;

public interface Nl2SqlService {
    Nl2SqlPlan generate(Nl2SqlRequest request);

    SqlErrorExplanation explainSqlError(String connectionId, String sql, String errorMessage);
}
