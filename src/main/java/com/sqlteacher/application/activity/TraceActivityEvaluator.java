package com.sqlteacher.application.activity;

import com.sqlteacher.domain.activity.ActivityType;
import com.sqlteacher.domain.activity.LearningActivityDefinition;
import com.sqlteacher.domain.activity.TraceActivityArtifact;
import com.sqlteacher.domain.activity.TraceActivitySpecification;

import java.time.Duration;
import java.util.ArrayList;

public final class TraceActivityEvaluator implements ActivityEvaluator<TraceActivitySpecification, TraceActivityArtifact> {
    public static final String VERSION = "trace-deterministic-v1";
    public static final String EVIDENCE_VERSION = "activity-evidence-v2";

    @Override public ActivityType activityType() { return ActivityType.TRACE; }
    @Override public Class<TraceActivitySpecification> specificationType() { return TraceActivitySpecification.class; }
    @Override public Class<TraceActivityArtifact> artifactType() { return TraceActivityArtifact.class; }
    @Override public String evaluatorVersion() { return VERSION; }

    @Override
    public ActivityEvaluationResult evaluate(LearningActivityDefinition definition,
                                             TraceActivitySpecification specification,
                                             TraceActivityArtifact artifact) {
        long started = System.nanoTime();
        var criteria = new ArrayList<ActivityCriterionResult>();
        int compared = Math.max(specification.expectedNodeIds().size(), artifact.visitedNodeIds().size());
        for (int index = 0; index < compared; index++) {
            String expected = index < specification.expectedNodeIds().size()
                ? specification.expectedNodeIds().get(index) : "—";
            String actual = index < artifact.visitedNodeIds().size() ? artifact.visitedNodeIds().get(index) : "—";
            boolean matched = expected.equals(actual);
            criteria.add(new ActivityCriterionResult("step-" + (index + 1), matched,
                matched ? "" : actual.equals("—") ? "TRACE_INCOMPLETE" : "TRACE_ORDER_INCORRECT",
                matched ? "第 " + (index + 1) + " 步正确。"
                    : "第 " + (index + 1) + " 步应访问 " + expected + "，实际为 " + actual + "。"));
        }
        boolean passed = artifact.visitedNodeIds().equals(specification.expectedNodeIds());
        String reason = passed ? "TRACE_PASSED"
            : artifact.visitedNodeIds().size() < specification.expectedNodeIds().size()
                ? "TRACE_INCOMPLETE" : "TRACE_ORDER_INCORRECT";
        return new ActivityEvaluationResult(
            passed ? ActivityEvaluationStatus.PASSED : ActivityEvaluationStatus.FAILED,
            criteria,
            passed ? specification.traversal() + "遍历顺序正确。" : specification.traversal() + "遍历顺序需要调整。",
            reason,
            VERSION,
            EVIDENCE_VERSION,
            ActivityResourceUsage.evaluationOnly(Duration.ofNanos(System.nanoTime() - started))
        );
    }
}
