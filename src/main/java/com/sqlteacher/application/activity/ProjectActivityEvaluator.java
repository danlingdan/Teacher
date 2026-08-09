package com.sqlteacher.application.activity;

import com.sqlteacher.domain.activity.ActivityType;
import com.sqlteacher.domain.activity.LearningActivityDefinition;
import com.sqlteacher.domain.activity.ProjectActivityArtifact;
import com.sqlteacher.domain.activity.ProjectActivitySpecification;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;

/** Evaluates only objective submission gates; the rubric remains an explicit teacher decision. */
public final class ProjectActivityEvaluator
        implements ActivityEvaluator<ProjectActivitySpecification, ProjectActivityArtifact> {
    public static final String VERSION = "project-gates-alpha7-r1";
    public static final String EVIDENCE_VERSION = "activity-evidence-v3";

    @Override public ActivityType activityType() { return ActivityType.PROJECT; }
    @Override public Class<ProjectActivitySpecification> specificationType() { return ProjectActivitySpecification.class; }
    @Override public Class<ProjectActivityArtifact> artifactType() { return ProjectActivityArtifact.class; }
    @Override public String evaluatorVersion() { return VERSION; }

    @Override
    public ActivityEvaluationResult evaluate(LearningActivityDefinition definition,
                                             ProjectActivitySpecification specification,
                                             ProjectActivityArtifact artifact) {
        long started = System.nanoTime();
        var criteria = new ArrayList<ActivityCriterionResult>();
        var allowed = new HashSet<>(specification.milestones().stream().map(item -> item.id()).toList());
        boolean known = allowed.containsAll(artifact.completedMilestoneIds());
        criteria.add(new ActivityCriterionResult("known-milestones", known,
            known ? "" : "PROJECT_MILESTONE_UNKNOWN",
            known ? "里程碑标识均来自当前项目版本。" : "提交包含当前项目版本中不存在的里程碑。"));
        var completed = new HashSet<>(artifact.completedMilestoneIds());
        for (var milestone : specification.milestones()) {
            boolean passed = completed.contains(milestone.id());
            criteria.add(new ActivityCriterionResult("milestone-" + milestone.id(), passed,
                passed ? "" : "PROJECT_MILESTONE_INCOMPLETE",
                passed ? "已声明完成：“" + milestone.title() + "”。" : "尚未完成：“" + milestone.title() + "”。"));
        }
        boolean evidence = artifact.evidenceSummary().length() >= specification.minimumEvidenceCharacters();
        criteria.add(new ActivityCriterionResult("evidence-summary", evidence,
            evidence ? "" : "PROJECT_EVIDENCE_TOO_SHORT",
            evidence ? "已提供满足最小长度的交付证据摘要。" : "交付证据摘要不足。"));
        boolean reflection = artifact.reflection().length() >= specification.minimumReflectionCharacters();
        criteria.add(new ActivityCriterionResult("reflection", reflection,
            reflection ? "" : "PROJECT_REFLECTION_TOO_SHORT",
            reflection ? "已提交学习反思。" : "学习反思不足。"));
        boolean passed = criteria.stream().allMatch(ActivityCriterionResult::passed);
        return new ActivityEvaluationResult(
            passed ? ActivityEvaluationStatus.PASSED : ActivityEvaluationStatus.FAILED,
            criteria,
            passed ? "自动提交门禁已通过；教师量规评审仍为必需步骤。" : "项目提交尚未满足全部自动门禁。",
            passed ? "PROJECT_READY_FOR_TEACHER_REVIEW" : criteria.stream().filter(item -> !item.passed())
                .map(ActivityCriterionResult::reasonCode).findFirst().orElse("PROJECT_GATE_FAILED"),
            VERSION, EVIDENCE_VERSION,
            ActivityResourceUsage.evaluationOnly(Duration.ofNanos(System.nanoTime() - started))
        );
    }
}
