package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.application.collaboration.AssignmentAnalyticsFilter;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** v3.4.0 REF-8: cloud analytics filter parsing keeps its frozen clamps and defaults. */
class CloudApiSectionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void assignmentAnalyticsFilterAppliesDefaultsAndBounds() {
        assertEquals(new AssignmentAnalyticsFilter(null, null, null, 0, 50),
            CloudApiSection.assignmentAnalyticsFilter(mapper.createObjectNode()));
    }

    @Test
    void assignmentAnalyticsFilterParsesStatusAndInstantRange() {
        ObjectNode params = mapper.createObjectNode()
            .put("status", "SUBMITTED")
            .put("from", "2026-01-01T00:00:00Z")
            .put("to", "2026-02-01T00:00:00Z")
            .put("page", 3);
        AssignmentAnalyticsFilter filter = CloudApiSection.assignmentAnalyticsFilter(params);
        assertEquals(com.sqlteacher.application.collaboration.AssignmentStudentStatus.SUBMITTED, filter.status());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), filter.from());
        assertEquals(Instant.parse("2026-02-01T00:00:00Z"), filter.to());
        assertEquals(3, filter.page());
        assertEquals(50, filter.pageSize());
    }

    @Test
    void assignmentAnalyticsFilterClampsPageAndPageSize() {
        ObjectNode params = mapper.createObjectNode().put("page", -5).put("pageSize", 5000);
        AssignmentAnalyticsFilter filter = CloudApiSection.assignmentAnalyticsFilter(params);
        assertEquals(0, filter.page());
        assertEquals(200, filter.pageSize());

        ObjectNode low = mapper.createObjectNode().put("pageSize", 0);
        assertEquals(1, CloudApiSection.assignmentAnalyticsFilter(low).pageSize());
    }

    @Test
    void assignmentAnalyticsFilterRejectsUnknownStatus() {
        ObjectNode params = mapper.createObjectNode().put("status", "NOT_A_STATUS");
        assertThrows(IllegalArgumentException.class,
            () -> CloudApiSection.assignmentAnalyticsFilter(params));
    }
}
