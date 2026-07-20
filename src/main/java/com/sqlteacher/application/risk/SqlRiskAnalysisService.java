package com.sqlteacher.application.risk;

import com.sqlteacher.application.connection.DatabaseDialect;

public interface SqlRiskAnalysisService {
    SqlRiskAnalysis analyze(String sql);

    default SqlRiskAnalysis analyze(String sql, DatabaseDialect dialect) {
        return analyze(sql);
    }
}
