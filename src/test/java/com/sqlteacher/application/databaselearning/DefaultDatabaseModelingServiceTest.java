package com.sqlteacher.application.databaselearning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DefaultDatabaseModelingServiceTest {
    private final DefaultDatabaseModelingService service = new DefaultDatabaseModelingService();

    @Test
    void createsReviewableEnrollmentDraftWithoutExecutingAnything() {
        var draft = service.draft("学生选择课程，不能重复选课");
        assertEquals("学生选课模型", draft.title());
        assertEquals(3, draft.tables().size());
        assertTrue(draft.ddl().contains("CREATE TABLE enrollment"));
        assertTrue(draft.ddl().contains("PRIMARY KEY (student_id, course_id)"));
    }

    @Test
    void asksForMoreInformationInsteadOfInventingUnknownSchema() {
        var draft = service.draft("做一个管理系统");
        assertTrue(draft.tables().isEmpty());
        assertTrue(draft.ddl().isEmpty());
    }
}
