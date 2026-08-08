package com.sqlteacher.application.activity;

import com.sqlteacher.domain.activity.ActivityDifficulty;
import com.sqlteacher.domain.activity.LearningActivityDefinition;
import com.sqlteacher.domain.activity.QuizActivityArtifact;
import com.sqlteacher.domain.activity.QuizActivitySpecification;
import com.sqlteacher.domain.activity.QuizOption;
import com.sqlteacher.domain.activity.QuizQuestion;
import com.sqlteacher.domain.activity.TraceActivityArtifact;
import com.sqlteacher.domain.activity.TraceActivitySpecification;
import com.sqlteacher.domain.activity.TraceNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NonSqlActivityEvaluatorTest {
    @Test
    void shouldEvaluateQuizWithoutModelOrSqlExecution() {
        var specification = quizSpecification();
        var definition = definition("quiz", specification);
        var dispatcher = new DefaultActivityEvaluationDispatcher(List.of(new QuizActivityEvaluator()));

        ActivityEvaluationResult passed = dispatcher.evaluate(definition,
            new QuizActivityArtifact(Map.of("q1", "preorder")));
        ActivityEvaluationResult failed = dispatcher.evaluate(definition,
            new QuizActivityArtifact(Map.of("q1", "inorder")));

        assertTrue(passed.passed());
        assertEquals("QUIZ_PASSED", passed.reasonCode());
        assertFalse(failed.passed());
        assertEquals("QUIZ_BELOW_PASS_SCORE", failed.reasonCode());
    }

    @Test
    void shouldEvaluateEveryBinaryTreeTraceStepDeterministically() {
        var specification = traceSpecification();
        var definition = definition("trace", specification);
        var dispatcher = new DefaultActivityEvaluationDispatcher(List.of(new TraceActivityEvaluator()));

        ActivityEvaluationResult passed = dispatcher.evaluate(definition,
            new TraceActivityArtifact(List.of("a", "b", "c")));
        ActivityEvaluationResult failed = dispatcher.evaluate(definition,
            new TraceActivityArtifact(List.of("a", "c", "b")));

        assertTrue(passed.passed());
        assertEquals(3, passed.criteria().size());
        assertEquals("TRACE_ORDER_INCORRECT", failed.reasonCode());
        assertFalse(failed.criteria().get(1).passed());
    }

    private static QuizActivitySpecification quizSpecification() {
        return new QuizActivitySpecification(1, List.of(new QuizQuestion(
            "q1", "根左右是哪种遍历？",
            List.of(new QuizOption("preorder", "前序"), new QuizOption("inorder", "中序")),
            "preorder", "根左右对应前序遍历。"
        )), 100);
    }

    private static TraceActivitySpecification traceSpecification() {
        return new TraceActivitySpecification(1, "按前序遍历", "前序",
            "a", List.of(new TraceNode("a", "A", "b", "c"),
                new TraceNode("b", "B", "", ""), new TraceNode("c", "C", "", "")),
            List.of("a", "b", "c"));
    }

    private static LearningActivityDefinition definition(String id,
                                                          com.sqlteacher.domain.activity.ActivitySpecification specification) {
        return new LearningActivityDefinition(id, "course", "section", id, id,
            List.of("tree-traversal"), ActivityDifficulty.BEGINNER, 10, 1, true,
            specification, Instant.EPOCH, Instant.EPOCH);
    }
}
