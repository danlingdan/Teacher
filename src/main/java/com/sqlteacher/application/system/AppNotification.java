package com.sqlteacher.application.system;

import java.time.Instant;

public record AppNotification(String id, Category category, String title, String message, String target,
                              boolean read, Instant createdAt) {
    public enum Category { UPDATE, TASK, BACKUP, SUPPORT, RECOVERY, SECURITY }
}
