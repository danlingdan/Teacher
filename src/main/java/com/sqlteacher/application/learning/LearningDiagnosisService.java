package com.sqlteacher.application.learning;

public interface LearningDiagnosisService {
    LearningDashboard refresh();

    void dismissAction(String actionId);

    void restoreAction(String actionId);

    boolean isActionDismissed(String actionId);

    String exportCsv();
}
