package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.knowledge.CourseKnowledgeArticle;
import com.sqlteacher.application.knowledge.KnowledgeVisibility;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeCenterControllerTest {

    @Test
    void shouldInferTutorContextWithoutRequiringAdvancedIdentifiers() {
        CourseKnowledgeArticle article = new CourseKnowledgeArticle(
            "article-42", "document-42", "数据库原理", "索引", "B+ 树索引",
            KnowledgeVisibility.PUBLISHED, 2, List.of("数据库索引"), "hash", Instant.EPOCH
        );

        assertEquals("数据库原理", KnowledgeCenterController.tutorCourseScope("", article));
        assertEquals("article-42", KnowledgeCenterController.tutorObjective("", article, "数据库索引"));
        assertEquals("自定义课程", KnowledgeCenterController.tutorCourseScope(" 自定义课程 ", article));
        assertEquals("goal-7", KnowledgeCenterController.tutorObjective(" goal-7 ", article, "数据库索引"));
    }

    @Test
    void shouldUseSafeGeneralTutorContextWhenNothingIsSelected() {
        assertEquals("all-courses", KnowledgeCenterController.tutorCourseScope("", null));
        assertEquals("数据库索引", KnowledgeCenterController.tutorObjective("", null, " 数据库索引 "));
        assertEquals("general-course-knowledge", KnowledgeCenterController.tutorObjective("", null, ""));
    }
}
