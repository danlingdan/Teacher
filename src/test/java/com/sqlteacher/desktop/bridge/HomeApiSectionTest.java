package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.course.CourseMapService;
import com.sqlteacher.application.course.CourseMapSnapshot;
import com.sqlteacher.application.knowledge.CourseKnowledgeArticle;
import com.sqlteacher.application.knowledge.CourseKnowledgeService;
import com.sqlteacher.application.knowledge.KnowledgeVisibility;
import com.sqlteacher.application.learning.LearningAction;
import com.sqlteacher.application.learning.LearningActionType;
import com.sqlteacher.application.learning.LearningDashboard;
import com.sqlteacher.application.learning.LearningDiagnosisService;
import com.sqlteacher.application.learning.MasteryLevel;
import com.sqlteacher.application.learning.MasterySnapshot;
import com.sqlteacher.application.learning.DiagnosisReasonCode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.fake;
import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.hostWithBeans;
import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.hostWithoutCore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.4.0 REF-9: the home section delegates to the deterministic learning diagnosis and course
 * ports only: the dashboard stays local (cloudAvailable false), dismissals round-trip the
 * action id, and cancellation is honored before the Spring core is touched.
 */
class HomeApiSectionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private static MasterySnapshot mastery(String knowledgePoint, MasteryLevel level) {
        return new MasterySnapshot("local-owner", knowledgePoint, level, 3, 1, 2, 1, 35,
            List.of(DiagnosisReasonCode.REPEATED_FAILURE), List.of(), "policy-2", Instant.now());
    }

    @Test
    void homeSummaryMapsTheDeterministicLocalDashboard() throws Exception {
        LearningDashboard dashboard = new LearningDashboard(
            "local-owner",
            List.of(mastery("SELECT 基础", MasteryLevel.NEEDS_PRACTICE), mastery("JOIN 进阶", MasteryLevel.MASTERED)),
            List.of(new LearningAction("action-1", LearningActionType.RETRY_EXERCISE, "重练 SELECT",
                "连续失败，建议重练", "ex-1", "SELECT 基础",
                DiagnosisReasonCode.REPEATED_FAILURE, 5, Instant.now(), false)),
            Instant.now(),
            Duration.ofMillis(12),
            "policy-2");
        LearningDiagnosisService diagnosis = fake(LearningDiagnosisService.class,
            Map.of("refresh", args -> dashboard));
        try (var host = hostWithBeans(diagnosis)) {
            HomeApiSection section = new HomeApiSection(host);

            JsonNode result = section.handle("home.summary", mapper.createObjectNode(), () -> false, ignored -> { });

            assertEquals("local-owner", result.path("ownerId").asText());
            assertEquals("policy-2", result.path("policyVersion").asText());
            assertEquals(2, result.path("knowledgePointCount").asInt());
            assertEquals(1, result.path("needsPracticeCount").asInt());
            assertFalse(result.path("cloudAvailable").asBoolean());
            assertEquals(12, result.path("calculationMillis").asLong());
            JsonNode action = result.path("actions").get(0);
            assertEquals("action-1", action.path("id").asText());
            assertEquals("RETRY_EXERCISE", action.path("type").asText());
            assertEquals("ex-1", action.path("exerciseId").asText());
            assertEquals("REPEATED_FAILURE", action.path("reason").asText());
            assertEquals(5, action.path("priority").asInt());
        }
    }

    @Test
    void homeSummaryHonoursCancellationBeforeAnyWork() {
        try (var host = hostWithBeans(fake(LearningDiagnosisService.class, Map.of()))) {
            HomeApiSection section = new HomeApiSection(host);

            assertThrows(LocalAppCancelledException.class,
                () -> section.handle("home.summary", mapper.createObjectNode(), () -> true, ignored -> { }));
        }
    }

    @Test
    void homeActionDismissRejectsBlankActionIdWithoutTheCore() {
        HomeApiSection section = new HomeApiSection(hostWithoutCore());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> section.handle("home.action.dismiss", mapper.createObjectNode()
                .put("actionId", "   "), () -> false, ignored -> { }));
        assertEquals("actionId must contain at most 128 characters", error.getMessage());
    }

    @Test
    void homeActionDismissDelegatesAndEchoesTheActionId() throws Exception {
        List<String> dismissed = new ArrayList<>();
        LearningDiagnosisService diagnosis = fake(LearningDiagnosisService.class, Map.of(
            "dismissAction", args -> {
                dismissed.add((String) args[0]);
                return null;
            }));
        try (var host = hostWithBeans(diagnosis)) {
            HomeApiSection section = new HomeApiSection(host);

            JsonNode result = section.handle("home.action.dismiss", mapper.createObjectNode()
                .put("actionId", "action-9"), () -> false, ignored -> { });

            assertTrue(result.path("dismissed").asBoolean());
            assertEquals("action-9", result.path("actionId").asText());
        }
        assertEquals(List.of("action-9"), dismissed);
    }

    @Test
    void courseWorkspaceCombinesTheCourseMapAndKnowledgeArticles() throws Exception {
        CourseMapService courses = fake(CourseMapService.class,
            Map.of("load", args -> new CourseMapSnapshot(List.of())));
        CourseKnowledgeService knowledge = fake(CourseKnowledgeService.class, Map.of(
            "listArticles", args -> List.of(new CourseKnowledgeArticle("a-1", "d-1", "数据库课程",
                "第一章", "索引入门", KnowledgeVisibility.PUBLISHED, 1, List.of("索引"), "hash-1", Instant.now()))));
        try (var host = hostWithBeans(courses, knowledge)) {
            HomeApiSection section = new HomeApiSection(host);

            JsonNode result = section.handle("course.workspace", mapper.createObjectNode(), () -> false, ignored -> { });

            assertTrue(result.path("courses").isArray());
            assertEquals(0, result.path("courses").size());
            assertEquals(1, result.path("articleCount").asInt());
            assertEquals("a-1", result.path("articles").get(0).path("id").asText());
        }
    }
}
