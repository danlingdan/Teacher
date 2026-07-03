package com.sqlteacher.application.ai;

import com.sqlteacher.infrastructure.environment.VerificationStatus;

public record AiStatus(
    VerificationStatus status,
    String provider,
    String endpoint,
    int modelCount,
    String message
) {
    public boolean available() {
        return status == VerificationStatus.PASS;
    }
}
