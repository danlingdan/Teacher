package com.sqlteacher.application.activity;

import com.sqlteacher.application.runner.CodeRunRequest;
import com.sqlteacher.application.runner.CodeRunResult;
import com.sqlteacher.application.runner.CodeRunner;
import com.sqlteacher.application.runner.RunnerCancellation;
import com.sqlteacher.application.runner.RunnerCapability;
import com.sqlteacher.application.runner.RunnerFailureReason;
import com.sqlteacher.domain.activity.ActivityDifficulty;
import com.sqlteacher.domain.activity.CodeActivityArtifact;
import com.sqlteacher.domain.activity.CodeActivitySpecification;
import com.sqlteacher.domain.activity.CodeExecutionLimits;
import com.sqlteacher.domain.activity.CodeLanguage;
import com.sqlteacher.domain.activity.CodeTestCase;
import com.sqlteacher.domain.activity.LearningActivityDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeActivityEvaluatorTest {
    @Test
    void shouldEvaluateTestsDeterministicallyThroughRunner() {
        CodeRunner runner = fakeRunner(request -> request.standardInput().toUpperCase());
        var evaluator = new CodeActivityEvaluator(runner);
        var specification = specification(List.of(
            new CodeTestCase("one", "alpha", "ALPHA"),
            new CodeTestCase("two", "beta", "BETA\n")
        ));

        var result = evaluator.evaluate(definition(specification), specification,
            new CodeActivityArtifact(CodeLanguage.PYTHON, "print(input().upper())"));

        assertTrue(result.passed());
        assertEquals("CODE_PASSED", result.reasonCode());
        assertEquals(2, result.criteria().size());
    }

    @Test
    void shouldFailClosedWhenSandboxIsUnavailable() {
        CodeRunner runner = fakeRunner(request -> null);
        var evaluator = new CodeActivityEvaluator(runner);
        var specification = specification(List.of(new CodeTestCase("one", "", "ok")));

        var result = evaluator.evaluate(definition(specification), specification,
            new CodeActivityArtifact(CodeLanguage.PYTHON, "print('ok')"));

        assertFalse(result.passed());
        assertEquals("CODE_RUNNER_SANDBOX_UNAVAILABLE", result.reasonCode());
        assertEquals("one", result.criteria().getFirst().criterion());
    }

    private static CodeRunner fakeRunner(java.util.function.Function<CodeRunRequest, String> output) {
        return new CodeRunner() {
            @Override public List<RunnerCapability> capabilities() {
                return List.of(new RunnerCapability(CodeLanguage.PYTHON, true, ""));
            }

            @Override
            public CodeRunResult run(CodeRunRequest request, RunnerCancellation cancellation) {
                String value = output.apply(request);
                if (value == null) {
                    return new CodeRunResult(RunnerFailureReason.SANDBOX_UNAVAILABLE, -1, "", "",
                        ActivityResourceUsage.evaluationOnly(Duration.ofMillis(1)));
                }
                return new CodeRunResult(RunnerFailureReason.NONE, 0, value, "",
                    new ActivityResourceUsage(Duration.ofMillis(1), value.length(), 0));
            }
        };
    }

    private static CodeActivitySpecification specification(List<CodeTestCase> tests) {
        return new CodeActivitySpecification(1, CodeLanguage.PYTHON, "Uppercase input", "", tests,
            CodeExecutionLimits.defaults());
    }

    private static LearningActivityDefinition definition(CodeActivitySpecification specification) {
        return new LearningActivityDefinition("code", "course", "section", "Code", "Code",
            List.of("programming"), ActivityDifficulty.BEGINNER, 10, 1, true,
            specification, Instant.EPOCH, Instant.EPOCH);
    }
}
