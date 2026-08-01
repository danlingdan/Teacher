package com.sqlteacher.application.update;

public record UpdateCheckResult(Status status, ApplicationBuildInfo current, UpdateManifest available, String message) {
    public enum Status { UP_TO_DATE, AVAILABLE, SKIPPED, UNSUPPORTED, FAILED }
    public UpdateCheckResult {
        message = message == null ? "" : message.strip();
    }
}
