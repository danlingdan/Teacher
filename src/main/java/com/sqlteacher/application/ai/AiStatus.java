package com.sqlteacher.application.ai;

public record AiStatus(
    AiAvailability status,
    String provider,
    String endpoint,
    int modelCount,
    String message
) {
    public boolean available() {
        return status == AiAvailability.AVAILABLE;
    }
}
