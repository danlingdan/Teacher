package com.sqlteacher.application.support;

import java.util.Map;

public interface ProblemReportService {
    ProblemReportReceipt submit(ProblemReportDraft draft, Map<String, Object> previewedDiagnostics, String accessToken);
    ProblemReportReceipt status(String reportId, String queryToken);

    /** Withdraws the caller's own report when it has not been processed yet. Idempotent for an already-withdrawn report. */
    void withdraw(String reportId, String queryToken);

    /** Exports the caller's own report metadata; never exposes internal audit or other users' content. */
    ProblemReportExport export(String reportId, String queryToken);
}
