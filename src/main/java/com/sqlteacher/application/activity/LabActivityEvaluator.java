package com.sqlteacher.application.activity;

import com.sqlteacher.domain.activity.ActivityType;
import com.sqlteacher.domain.activity.LabActivityArtifact;
import com.sqlteacher.domain.activity.LabActivitySpecification;
import com.sqlteacher.domain.activity.LearningActivityDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;

/** Deterministic structural evaluation for multi-step labs; observations remain student-authored evidence. */
public final class LabActivityEvaluator
        implements ActivityEvaluator<LabActivitySpecification, LabActivityArtifact> {
    public static final String VERSION = "lab-structured-beta1-r1";
    public static final String EVIDENCE_VERSION = "activity-evidence-v4";

    @Override public ActivityType activityType() { return ActivityType.LAB; }
    @Override public Class<LabActivitySpecification> specificationType() { return LabActivitySpecification.class; }
    @Override public Class<LabActivityArtifact> artifactType() { return LabActivityArtifact.class; }
    @Override public String evaluatorVersion() { return VERSION; }

    @Override
    public ActivityEvaluationResult evaluate(LearningActivityDefinition definition,
                                             LabActivitySpecification specification,
                                             LabActivityArtifact artifact) {
        long started = System.nanoTime();
        var criteria = new ArrayList<ActivityCriterionResult>();
        var knownSteps = new HashSet<>(specification.steps().stream().map(step -> step.id()).toList());
        boolean known = knownSteps.containsAll(artifact.completedStepIds());
        criteria.add(new ActivityCriterionResult("known-steps", known,
            known ? "" : "LAB_STEP_UNKNOWN", known ? "实验步骤均来自当前版本。" : "提交包含未知实验步骤。"));
        var completed = new HashSet<>(artifact.completedStepIds());
        for (var step : specification.steps()) {
            boolean stepDone = completed.contains(step.id());
            criteria.add(new ActivityCriterionResult("step-" + step.id(), stepDone,
                stepDone ? "" : "LAB_STEP_INCOMPLETE", stepDone ? "已完成：“" + step.title() + "”。"
                    : "尚未完成：“" + step.title() + "”。"));
            String observation = artifact.observations().getOrDefault(step.observationKey(), "").trim();
            boolean observed = !observation.isEmpty();
            criteria.add(new ActivityCriterionResult("observation-" + step.observationKey(), observed,
                observed ? "" : "LAB_OBSERVATION_MISSING", observed ? "已记录结构化观测。" : "缺少本步骤的观测记录。"));
        }
        boolean conclusion = artifact.conclusion().length() >= specification.minimumConclusionCharacters();
        criteria.add(new ActivityCriterionResult("conclusion", conclusion,
            conclusion ? "" : "LAB_CONCLUSION_TOO_SHORT", conclusion ? "已提交满足要求的实验结论。" : "实验结论不足。"));
        boolean passed = criteria.stream().allMatch(ActivityCriterionResult::passed);
        String reason = passed ? "LAB_PASSED" : criteria.stream().filter(item -> !item.passed())
            .map(ActivityCriterionResult::reasonCode).findFirst().orElse("LAB_INCOMPLETE");
        return new ActivityEvaluationResult(passed ? ActivityEvaluationStatus.PASSED : ActivityEvaluationStatus.FAILED,
            criteria, passed ? "实验步骤、观测和结论均已完整提交。" : "实验记录尚未满足全部结构化门禁。",
            reason, VERSION, EVIDENCE_VERSION,
            ActivityResourceUsage.evaluationOnly(Duration.ofNanos(System.nanoTime() - started)));
    }
}
