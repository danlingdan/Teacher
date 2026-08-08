package com.sqlteacher.application.activity;

import com.sqlteacher.domain.activity.ActivityType;
import com.sqlteacher.domain.activity.LearningActivityDefinition;
import com.sqlteacher.domain.activity.QuizActivityArtifact;
import com.sqlteacher.domain.activity.QuizActivitySpecification;

import java.time.Duration;
import java.util.ArrayList;

public final class QuizActivityEvaluator implements ActivityEvaluator<QuizActivitySpecification, QuizActivityArtifact> {
    public static final String VERSION = "quiz-deterministic-v1";
    public static final String EVIDENCE_VERSION = "activity-evidence-v2";

    @Override public ActivityType activityType() { return ActivityType.QUIZ; }
    @Override public Class<QuizActivitySpecification> specificationType() { return QuizActivitySpecification.class; }
    @Override public Class<QuizActivityArtifact> artifactType() { return QuizActivityArtifact.class; }
    @Override public String evaluatorVersion() { return VERSION; }

    @Override
    public ActivityEvaluationResult evaluate(LearningActivityDefinition definition,
                                             QuizActivitySpecification specification,
                                             QuizActivityArtifact artifact) {
        long started = System.nanoTime();
        var criteria = new ArrayList<ActivityCriterionResult>();
        int correct = 0;
        int answered = 0;
        for (var question : specification.questions()) {
            String selected = artifact.selectedOptionIds().get(question.id());
            boolean present = selected != null;
            boolean passed = question.correctOptionId().equals(selected);
            if (present) answered++;
            if (passed) correct++;
            criteria.add(new ActivityCriterionResult(question.id(), passed,
                passed ? "" : present ? "QUIZ_INCORRECT" : "QUIZ_INCOMPLETE",
                passed ? "回答正确。" : present ? question.explanation() : "请完成此题后再次提交。"));
        }
        int score = correct * 100 / specification.questions().size();
        boolean passed = score >= specification.passPercent();
        String reason = passed ? "QUIZ_PASSED" : answered < specification.questions().size()
            ? "QUIZ_INCOMPLETE" : "QUIZ_BELOW_PASS_SCORE";
        return new ActivityEvaluationResult(
            passed ? ActivityEvaluationStatus.PASSED : ActivityEvaluationStatus.FAILED,
            criteria,
            "测验得分 " + score + "%（通过线 " + specification.passPercent() + "%）。",
            reason,
            VERSION,
            EVIDENCE_VERSION,
            ActivityResourceUsage.evaluationOnly(Duration.ofNanos(System.nanoTime() - started))
        );
    }
}
