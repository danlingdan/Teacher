package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.application.activity.ActivityEvaluationResult;
import com.sqlteacher.application.activity.ActivityEvaluationStatus;
import com.sqlteacher.application.activity.ActivityLearningService;
import com.sqlteacher.application.activity.ActivityResourceUsage;
import com.sqlteacher.application.activity.ActivitySubmission;
import com.sqlteacher.application.activity.ProjectPortfolioService;
import com.sqlteacher.domain.activity.ActivityDifficulty;
import com.sqlteacher.domain.activity.LearningActivityDefinition;
import com.sqlteacher.domain.activity.QuizActivityArtifact;
import com.sqlteacher.domain.activity.QuizActivitySpecification;
import com.sqlteacher.domain.activity.QuizOption;
import com.sqlteacher.domain.activity.QuizQuestion;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.fake;
import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.hostWithBeans;
import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.hostWithoutCore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.4.0 REF-9: the activity section parses artifacts into typed domain objects before
 * delegating, redacts quiz answers from the definition sent to the UI, and keeps the
 * portfolio export behind an explicit user confirmation.
 */
class ActivityApiSectionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void activitySubmitRejectsUnknownActivityTypeWithoutTheCore() {
        ActivityApiSection section = new ActivityApiSection(hostWithoutCore());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> section.handle("activity.submit", mapper.createObjectNode()
                .put("activityId", "act-1")
                .put("type", "CHESS"), () -> false, ignored -> { }));
        assertEquals("Unsupported activity type: CHESS", error.getMessage());
    }

    @Test
    void activitySubmitRejectsMalformedQuizArtifactWithoutTheCore() {
        ActivityApiSection section = new ActivityApiSection(hostWithoutCore());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> section.handle("activity.submit", mapper.createObjectNode()
                .put("activityId", "act-1")
                .put("type", "QUIZ")
                .put("artifact", "not-an-object"), () -> false, ignored -> { }));
        assertEquals("Expected a bounded text map", error.getMessage());
    }

    @Test
    void activitySubmitMapsTheQuizArtifactAndDelegates() throws Exception {
        List<Object[]> submissions = new ArrayList<>();
        ActivitySubmission submission = new ActivitySubmission(
            "s-1", "e-1",
            new ActivityEvaluationResult(ActivityEvaluationStatus.PASSED, List.of(), "全部通过", "",
                "evaluator-1", "evidence-1", ActivityResourceUsage.evaluationOnly(Duration.ofMillis(1))),
            Instant.now());
        ActivityLearningService service = fake(ActivityLearningService.class, Map.of(
            "submit", args -> {
                submissions.add(args);
                return submission;
            }));
        ObjectNode params = mapper.createObjectNode()
            .put("activityId", "act-1")
            .put("type", "quiz");
        params.putObject("artifact").putObject("selectedOptionIds").put("q1", "b");
        try (var host = hostWithBeans(service)) {
            ActivityApiSection section = new ActivityApiSection(host);

            JsonNode result = section.handle("activity.submit", params, () -> false, ignored -> { });

            assertEquals("s-1", result.path("sessionId").asText());
            assertEquals("PASSED", result.path("evaluation").path("status").asText());
        }
        assertEquals(1, submissions.size());
        assertEquals("act-1", submissions.get(0)[0]);
        QuizActivityArtifact quiz = assertInstanceOf(QuizActivityArtifact.class, submissions.get(0)[1]);
        assertEquals(Map.of("q1", "b"), quiz.selectedOptionIds());
    }

    @Test
    void activityDefinitionRedactsQuizAnswersBeforeTheyReachTheUi() throws Exception {
        LearningActivityDefinition definition = new LearningActivityDefinition(
            "act-1", "course-1", "section-1", "小测验", "检验 SELECT 基础",
            List.of("kp-select"), ActivityDifficulty.BEGINNER, 10, 1, true,
            new QuizActivitySpecification(1, List.of(new QuizQuestion("q1", "哪个语句查询全部？",
                List.of(new QuizOption("a", "SELECT 1"), new QuizOption("b", "SELECT * FROM t")),
                "b", "因为 b 覆盖全表")), 60),
            Instant.now(), Instant.now());
        ActivityLearningService service = fake(ActivityLearningService.class, Map.of(
            "loadDefinition", args -> definition,
            "nextSubmissionVersion", args -> 2,
            "latestFeedback", args -> Optional.empty()));
        try (var host = hostWithBeans(service)) {
            ActivityApiSection section = new ActivityApiSection(host);

            JsonNode result = section.handle("activity.definition", mapper.createObjectNode()
                .put("activityId", "act-1"), () -> false, ignored -> { });

            assertEquals("QUIZ", result.path("type").asText());
            assertEquals(2, result.path("nextSubmissionVersion").asInt());
            assertFalse(result.has("latestFeedback"));
            JsonNode question = result.path("specification").path("questions").get(0);
            assertEquals("哪个语句查询全部？", question.path("prompt").asText());
            assertEquals(2, question.path("options").size());
            assertFalse(question.has("correctOptionId"));
            assertFalse(question.has("explanation"));
        }
    }

    @Test
    void learningPortfolioExportRequiresExplicitConfirmation() {
        ActivityApiSection section = new ActivityApiSection(hostWithoutCore());

        SecurityException error = assertThrows(SecurityException.class,
            () -> section.handle("learning.portfolio.export", mapper.createObjectNode(), () -> false, ignored -> { }));
        assertEquals("Portfolio export requires explicit confirmation", error.getMessage());
    }

    @Test
    void learningPortfolioExportForwardsTheUserConfirmation() throws Exception {
        List<Object[]> exports = new ArrayList<>();
        ProjectPortfolioService portfolio = fake(ProjectPortfolioService.class, Map.of(
            "exportOwnPortfolio", args -> {
                exports.add(args);
                return "CSV-CONTENT";
            }));
        try (var host = hostWithBeans(portfolio)) {
            ActivityApiSection section = new ActivityApiSection(host);

            JsonNode result = section.handle("learning.portfolio.export", mapper.createObjectNode()
                .put("confirmed", true), () -> false, ignored -> { });

            assertEquals("CSV-CONTENT", result.path("content").asText());
        }
        assertEquals(1, exports.size());
        assertTrue((Boolean) exports.get(0)[0]);
    }
}
