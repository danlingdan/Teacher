package com.sqlteacher.application.collaboration;

import java.time.Instant;

public record CloudNotification(String id, NotificationType type, String resourceType, String resourceId,
                                String title, String message, Instant readAt, Instant createdAt) {
    public CloudNotification {
        if (id == null || id.isBlank() || type == null || resourceType == null || resourceType.isBlank()
            || resourceId == null || resourceId.isBlank() || title == null || title.isBlank()
            || message == null || message.isBlank() || createdAt == null) {
            throw new IllegalArgumentException("Notification fields are invalid");
        }
    }

    public boolean unread() {
        return readAt == null;
    }
}
