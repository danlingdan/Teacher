package com.sqlteacher.application.collaboration;

import java.time.Instant;
import java.util.List;

public record AssignmentAnalyticsReport(String classroomId, String assignmentId, int totalStudents,
                                        int submittedStudents, int passedStudents, int totalAttempts,
                                        double completionRate, double passRate, List<AssignmentErrorCount> commonErrors,
                                        List<AssignmentAnalyticsRow> rows, int page, int pageSize, int totalRows,
                                        Instant generatedAt) {
    public AssignmentAnalyticsReport {
        commonErrors = commonErrors == null ? List.of() : List.copyOf(commonErrors);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
