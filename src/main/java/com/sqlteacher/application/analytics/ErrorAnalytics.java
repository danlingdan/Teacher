package com.sqlteacher.application.analytics;

public record ErrorAnalytics(String errorCode, int count) {
    public ErrorAnalytics {
        if (errorCode == null || errorCode.isBlank() || count < 1) {
            throw new IllegalArgumentException("error analytics values are invalid");
        }
    }
}
