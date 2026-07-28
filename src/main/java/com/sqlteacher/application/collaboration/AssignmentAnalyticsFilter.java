package com.sqlteacher.application.collaboration;

import java.time.Instant;

public record AssignmentAnalyticsFilter(AssignmentStudentStatus status, Instant from, Instant to,
                                        int page, int pageSize) {
    public AssignmentAnalyticsFilter {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        if (page < 0) throw new IllegalArgumentException("page must not be negative");
        if (pageSize < 1 || pageSize > 200) {
            throw new IllegalArgumentException("pageSize must be between 1 and 200");
        }
    }

    public static AssignmentAnalyticsFilter firstPage() {
        return new AssignmentAnalyticsFilter(null, null, null, 0, 50);
    }
}
