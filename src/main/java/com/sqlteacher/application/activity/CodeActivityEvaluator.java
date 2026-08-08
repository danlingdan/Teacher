package com.sqlteacher.application.activity;

import com.sqlteacher.application.runner.CodeRunRequest;
import com.sqlteacher.application.runner.CodeRunResult;
import com.sqlteacher.application.runner.CodeRunner;
import com.sqlteacher.application.runner.RunnerCancellation;
import com.sqlteacher.application.runner.RunnerFailureReason;
import com.sqlteacher.domain.activity.CodeActivityArtifact;
import com.sqlteacher.domain.activity.CodeActivitySpecification;
import com.sqlteacher.domain.activity.ActivityType;
import com.sqlteacher.domain.activity.LearningActivityDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;

/** Deterministic code evaluator. Only the injected isolated Runner may compile or execute source. */
public final class CodeActivityEvaluator implements CancellableActivityEvaluator<CodeActivitySpecification, CodeActivityArtifact> {
    public static final String VERSION = "code-tests-v1";
    public static final String EVIDENCE_VERSION = "activity-evidence-v3";
    private final CodeRunner runner;

    public CodeActivityEvaluator(CodeRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
    }

    @Override public ActivityType activityType() { return ActivityType.CODE; }
    @Override public Class<CodeActivitySpecification> specificationType() { return CodeActivitySpecification.class; }
    @Override public Class<CodeActivityArtifact> artifactType() { return CodeActivityArtifact.class; }
    @Override public String evaluatorVersion() { return VERSION; }

    @Override
    public ActivityEvaluationResult evaluate(LearningActivityDefinition definition,
                                             CodeActivitySpecification specification,
                                             CodeActivityArtifact artifact,
                                             RunnerCancellation cancellation) {
        long started = System.nanoTime();
        if (artifact.language() != specification.language()) {
            return failure("CODE_LANGUAGE_MISMATCH", "提交语言与活动要求不一致。",
                Duration.ofNanos(System.nanoTime() - started), 0, 0);
        }

        var criteria = new ArrayList<ActivityCriterionResult>();
        long outputBytes = 0;
        long filesCreated = 0;
        for (var test : specification.tests()) {
            CodeRunResult result = runner.run(new CodeRunRequest(specification.language(), artifact.sourceCode(),
                test.input(), specification.limits()), cancellation);
            outputBytes += result.resourceUsage().outputBytes();
            filesCreated += result.resourceUsage().filesCreated();
            if (!result.succeeded()) {
                String reason = reasonCode(result.failureReason());
                criteria.add(new ActivityCriterionResult(test.id(), false, reason,
                    feedback(result.failureReason())));
                return new ActivityEvaluationResult(ActivityEvaluationStatus.FAILED, criteria,
                    "代码未通过受控运行。", reason, VERSION, EVIDENCE_VERSION,
                    new ActivityResourceUsage(Duration.ofNanos(System.nanoTime() - started), outputBytes, filesCreated));
            }
            boolean passed = normalize(result.standardOutput()).equals(normalize(test.expectedOutput()));
            criteria.add(new ActivityCriterionResult(test.id(), passed, passed ? "" : "CODE_OUTPUT_MISMATCH",
                passed ? "测试通过。" : "输出与测试期望不一致。"));
            if (!passed) {
                return new ActivityEvaluationResult(ActivityEvaluationStatus.FAILED, criteria,
                    "代码未通过测试 " + test.id() + "。", "CODE_TEST_FAILED", VERSION, EVIDENCE_VERSION,
                    new ActivityResourceUsage(Duration.ofNanos(System.nanoTime() - started), outputBytes, filesCreated));
            }
        }
        return new ActivityEvaluationResult(ActivityEvaluationStatus.PASSED, criteria,
            "全部 " + criteria.size() + " 个受控测试通过。", "CODE_PASSED", VERSION, EVIDENCE_VERSION,
            new ActivityResourceUsage(Duration.ofNanos(System.nanoTime() - started), outputBytes, filesCreated));
    }

    private static ActivityEvaluationResult failure(String reason, String summary, Duration wallTime,
                                                     long outputBytes, long filesCreated) {
        return new ActivityEvaluationResult(ActivityEvaluationStatus.FAILED,
            java.util.List.of(new ActivityCriterionResult("runner", false, reason, summary)),
            summary, reason, VERSION, EVIDENCE_VERSION,
            new ActivityResourceUsage(wallTime, outputBytes, filesCreated));
    }

    private static String reasonCode(RunnerFailureReason reason) {
        return "CODE_RUNNER_" + reason.name();
    }

    private static String feedback(RunnerFailureReason reason) {
        return switch (reason) {
            case TOOLCHAIN_UNAVAILABLE -> "当前安全 Runner 未安装此语言工具链。";
            case SANDBOX_UNAVAILABLE -> "安全隔离能力不可用，已拒绝执行。";
            case COMPILE_ERROR -> "编译失败，请检查源码。";
            case RUNTIME_ERROR -> "程序运行失败。";
            case TIME_LIMIT -> "程序超过时间限制。";
            case MEMORY_LIMIT -> "程序超过内存限制。";
            case WORKSPACE_LIMIT -> "程序超过工作区文件或空间限制。";
            case PROCESS_LIMIT -> "程序超过进程数量限制。";
            case OUTPUT_LIMIT -> "程序超过输出限制。";
            case CANCELLED -> "运行已取消。";
            case INTERNAL_ERROR -> "Runner 内部失败。";
            case NONE -> "";
        };
    }

    private static String normalize(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
    }
}
