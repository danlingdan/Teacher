package com.sqlteacher.desktop.viewmodel;

/**
 * UI-facing status level used by every desktop ViewModel.
 *
 * <p>This enum isolates the desktop layer from the application enum
 * {@code com.sqlteacher.application.ai.AiAvailability}. ViewModels never import that enum
 * directly; instead they map through the string-based {@link #fromStatusName(String)} bridge
 * so that a change in the upstream enum only affects this single conversion point.
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
     * Maps an {@code AiAvailability} enum name to a UI level without importing the enum type.
     * Callers pass {@code someStatus.name()} so no compile-time dependency on the application
     * enum leaks into the ViewModel layer.
     *
     * <p><b>[改动点 · P1 AI 枚举映射]</b> develop 最新 {@code AiStatus.status()} 返回
     * {@code AiAvailability}（{@code AVAILABLE} / {@code UNAVAILABLE}），已移除旧的
     * {@code PASS / WARNING / FAIL} 匹配：
     * <ul>
     *   <li>{@code AVAILABLE} → {@link #SUCCESS}；</li>
     *   <li>{@code UNAVAILABLE} → {@link #WARNING}（AI 不可用属可降级的警告而非硬错误，
     *       符合「AI 缺失不崩溃」语义）；</li>
     *   <li>其它 / {@code null} → {@link #UNKNOWN}。</li>
     * </ul>
     * 该映射保证 AI 可用状态不会在界面显示为「未知」。
     */
    public static UiStatusLevel fromStatusName(String availabilityName) {
        if (availabilityName == null) {
            return UNKNOWN;
        }
        return switch (availabilityName) {
            case "AVAILABLE" -> SUCCESS;
            case "UNAVAILABLE" -> WARNING;
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
