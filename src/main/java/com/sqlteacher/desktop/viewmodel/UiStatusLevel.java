package com.sqlteacher.desktop.viewmodel;

/**
 * UI-facing status level used by every desktop ViewModel.
 *
 * <p>This enum isolates the desktop layer from the infrastructure enum
 * {@code com.sqlteacher.infrastructure.environment.VerificationStatus}. ViewModels never
 * import that infrastructure enum directly; instead they map through the string-based
 * {@link #fromStatusName(String)} bridge so that a change in the infrastructure enum only
 * affects this single conversion point.
 */
public enum UiStatusLevel {
    SUCCESS("正常"),
    WARNING("警告"),
    ERROR("异常"),
    UNKNOWN("未知");

    private final String displayLabel;

    UiStatusLevel(String displayLabel) {
        this.displayLabel = displayLabel;
    }

    public String displayLabel() {
        return displayLabel;
    }

    /**
     * Maps an infrastructure {@code VerificationStatus} name to a UI level without importing
     * the infrastructure enum type. Callers pass {@code someStatus.name()} so no compile-time
     * dependency on the infrastructure enum leaks into the ViewModel layer.
     */
    public static UiStatusLevel fromStatusName(String verificationStatusName) {
        if (verificationStatusName == null) {
            return UNKNOWN;
        }
        return switch (verificationStatusName) {
            case "PASS" -> SUCCESS;
            case "WARNING" -> WARNING;
            case "FAIL" -> ERROR;
            default -> UNKNOWN;
        };
    }

    /**
     * Maps a boolean success flag (e.g. {@code SqlExecutionResult.success()}) to a UI level.
     */
    public static UiStatusLevel fromSuccessFlag(boolean success) {
        return success ? SUCCESS : ERROR;
    }
}
