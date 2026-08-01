package com.sqlteacher.application.system;

import java.time.Instant;

public record TaskSnapshot(String id, String type, String title, Status status, double progress,
                           boolean cancellable, boolean retryable, String errorCode,
                           Instant startedAt, Instant finishedAt) {
    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLING, CANCELLED }
}
