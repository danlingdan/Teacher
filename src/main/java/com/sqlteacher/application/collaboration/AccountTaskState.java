package com.sqlteacher.application.collaboration;

import java.time.Instant;

/** Observable state of an asynchronous account task (cloud data export or account deletion). */
public record AccountTaskState(String taskId, String kind, Status status, Instant createdAt,
                               Instant updatedAt, Instant cancelBefore) {
    public enum Status { PENDING, READY, CANCELLED, COMPLETED, FAILED }
    public AccountTaskState {
        kind = kind == null ? "" : kind;
        status = status == null ? Status.PENDING : status;
    }
}
