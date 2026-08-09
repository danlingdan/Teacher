package com.sqlteacher.application.activity;

import com.sqlteacher.domain.activity.ActivityType;
import com.sqlteacher.domain.activity.LearningActivityDefinition;
import com.sqlteacher.domain.activity.ReadingActivityArtifact;
import com.sqlteacher.domain.activity.ReadingActivitySpecification;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Locale;

/** Reading completion is recorded, but only active-recall checks can produce a passing result. */
public final class ReadingActivityEvaluator
        implements ActivityEvaluator<ReadingActivitySpecification, ReadingActivityArtifact> {
    public static final String VERSION = "reading-recall-beta1-r1";
    public static final String EVIDENCE_VERSION = "activity-evidence-v4";

    @Override public ActivityType activityType() { return ActivityType.READING; }
    @Override public Class<ReadingActivitySpecification> specificationType() { return ReadingActivitySpecification.class; }
    @Override public Class<ReadingActivityArtifact> artifactType() { return ReadingActivityArtifact.class; }
    @Override public String evaluatorVersion() { return VERSION; }

    @Override
    public ActivityEvaluationResult evaluate(LearningActivityDefinition definition,
                                             ReadingActivitySpecification specification,
                                             ReadingActivityArtifact artifact) {
        long started = System.nanoTime();
        var criteria = new ArrayList<ActivityCriterionResult>();
        criteria.add(new ActivityCriterionResult("read-to-end", artifact.readToEnd(),
            artifact.readToEnd() ? "" : "READING_NOT_COMPLETED",
            artifact.readToEnd() ? "已确认完成阅读。" : "请先完成阅读，再进行主动回忆。"));
        int correct = 0;
        for (var check : specification.checks()) {
            String answer = artifact.answers().getOrDefault(check.id(), "");
            boolean passed = normalize(check.expectedAnswer()).equals(normalize(answer));
            if (passed) correct++;
            criteria.add(new ActivityCriterionResult("recall-" + check.id(), passed,
                passed ? "" : answer.isBlank() ? "READING_RECALL_INCOMPLETE" : "READING_RECALL_INCORRECT",
                passed ? "主动回忆正确。" : check.explanation()));
        }
        int score = correct * 100 / specification.checks().size();
        boolean passed = artifact.readToEnd() && score >= specification.passPercent();
        String reason = passed ? "READING_RECALL_PASSED" : !artifact.readToEnd() ? "READING_NOT_COMPLETED"
            : artifact.answers().size() < specification.checks().size() ? "READING_RECALL_INCOMPLETE"
            : "READING_RECALL_BELOW_PASS_SCORE";
        return new ActivityEvaluationResult(passed ? ActivityEvaluationStatus.PASSED : ActivityEvaluationStatus.FAILED,
            criteria, "主动回忆得分 " + score + "%（通过线 " + specification.passPercent() + "%）；阅读完成本身不等于掌握。",
            reason, VERSION, EVIDENCE_VERSION,
            ActivityResourceUsage.evaluationOnly(Duration.ofNanos(System.nanoTime() - started)));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
