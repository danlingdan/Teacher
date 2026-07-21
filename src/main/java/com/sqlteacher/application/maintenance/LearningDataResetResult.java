package com.sqlteacher.application.maintenance;

public record LearningDataResetResult(int sessionsDeleted, int attemptsDeleted, int eventsDeleted) {
    public LearningDataResetResult {
        if (sessionsDeleted < 0 || attemptsDeleted < 0 || eventsDeleted < 0) {
            throw new IllegalArgumentException("deleted counts must not be negative");
        }
    }
}
