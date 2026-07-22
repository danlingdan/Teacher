package com.sqlteacher.application.collaboration;

/** Server-enforced lifecycle state for a classroom assignment. */
public enum AssignmentStatus {
    DRAFT,
    PUBLISHED,
    CLOSED,
    WITHDRAWN,
    ARCHIVED
}
