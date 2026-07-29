package com.sqlteacher.application.collaboration;

public enum FeedbackDraftStyle {
    CONCISE("简洁"),
    GUIDED("引导式"),
    STEP_BY_STEP("分步解释");

    private final String displayName;
    FeedbackDraftStyle(String displayName) { this.displayName = displayName; }
    public String displayName() { return displayName; }
    @Override public String toString() { return displayName; }
}
