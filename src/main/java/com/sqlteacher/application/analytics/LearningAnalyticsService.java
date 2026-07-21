package com.sqlteacher.application.analytics;

public interface LearningAnalyticsService {
    LearningAnalyticsReport analyze(AnalyticsFilter filter);

    AnalyticsCsvExport exportCsv(AnalyticsFilter filter);
}
