package com.sqlteacher.application.support;

public record DiagnosticSelection(boolean environment, boolean recentErrors, boolean networkSummary,
                                  boolean updateState) {
    public static DiagnosticSelection minimum() { return new DiagnosticSelection(false, false, false, false); }
}
