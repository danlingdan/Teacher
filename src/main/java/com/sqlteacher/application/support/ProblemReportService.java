package com.sqlteacher.application.support;

import java.util.Map;

public interface ProblemReportService {
    ProblemReportReceipt submit(ProblemReportDraft draft, Map<String, Object> previewedDiagnostics, String accessToken);
    ProblemReportReceipt status(String reportId, String queryToken);
}
