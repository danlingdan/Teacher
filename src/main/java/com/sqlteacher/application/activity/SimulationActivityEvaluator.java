package com.sqlteacher.application.activity;

import com.sqlteacher.domain.activity.ActivityType;
import com.sqlteacher.domain.activity.LearningActivityDefinition;
import com.sqlteacher.domain.activity.SimulationAction;
import com.sqlteacher.domain.activity.SimulationActivityArtifact;
import com.sqlteacher.domain.activity.SimulationActivitySpecification;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;

public final class SimulationActivityEvaluator
        implements ActivityEvaluator<SimulationActivitySpecification, SimulationActivityArtifact> {
    public static final String VERSION = "simulation-deterministic-alpha5-r1";
    public static final String EVIDENCE_VERSION = "activity-evidence-v2";

    @Override public ActivityType activityType() { return ActivityType.SIMULATION; }
    @Override public Class<SimulationActivitySpecification> specificationType() {
        return SimulationActivitySpecification.class;
    }
    @Override public Class<SimulationActivityArtifact> artifactType() { return SimulationActivityArtifact.class; }
    @Override public String evaluatorVersion() { return VERSION; }

    @Override
    public ActivityEvaluationResult evaluate(LearningActivityDefinition definition,
                                             SimulationActivitySpecification specification,
                                             SimulationActivityArtifact artifact) {
        long started = System.nanoTime();
        String currentState = specification.initialStateId();
        var visitedStates = new HashSet<String>();
        visitedStates.add(currentState);
        var criteria = new ArrayList<ActivityCriterionResult>();
        var actions = specification.actionsById();

        for (int index = 0; index < artifact.actionIds().size(); index++) {
            String actionId = artifact.actionIds().get(index);
            SimulationAction action = actions.get(actionId);
            if (action == null) {
                criteria.add(new ActivityCriterionResult("action-" + (index + 1), false,
                    "SIMULATION_ACTION_UNKNOWN", "第 " + (index + 1) + " 步不是课程定义中的模拟动作。"));
                return failed(criteria, "模拟包含未知动作。", "SIMULATION_ACTION_UNKNOWN", started);
            }
            if (!action.fromStateId().equals(currentState)) {
                criteria.add(new ActivityCriterionResult("action-" + (index + 1), false,
                    "SIMULATION_TRANSITION_INVALID", "“" + action.label() + "”不能从当前状态执行。"));
                return failed(criteria, "模拟状态转换顺序需要调整。", "SIMULATION_TRANSITION_INVALID", started);
            }
            currentState = action.toStateId();
            visitedStates.add(currentState);
            criteria.add(new ActivityCriterionResult("action-" + (index + 1), true, "",
                "已完成：" + action.explanation()));
        }

        String firstFailure = "";
        int reached = 0;
        for (var checkpoint : specification.checkpoints()) {
            boolean passed = visitedStates.contains(checkpoint.stateId());
            if (passed) reached++;
            if (!passed && firstFailure.isEmpty()) firstFailure = checkpoint.failureReasonCode();
            criteria.add(new ActivityCriterionResult("checkpoint-" + checkpoint.id(), passed,
                passed ? "" : checkpoint.failureReasonCode(),
                passed ? checkpoint.successMessage() : "尚未达到检查点：“" + checkpoint.title() + "”。"));
        }
        boolean goalReached = currentState.equals(specification.goalStateId());
        criteria.add(new ActivityCriterionResult("goal-state", goalReached,
            goalReached ? "" : "SIMULATION_GOAL_NOT_REACHED",
            goalReached ? "已到达模拟目标状态。" : "当前状态尚不是实验目标状态。"));
        boolean passed = reached == specification.checkpoints().size() && goalReached;
        String reason = passed ? "SIMULATION_PASSED"
            : firstFailure.isEmpty() ? "SIMULATION_GOAL_NOT_REACHED" : firstFailure;
        String summary = passed ? "模拟实验的全部步骤已完成。"
            : "已达到 " + reached + "/" + specification.checkpoints().size() + " 个检查点。";
        return new ActivityEvaluationResult(
            passed ? ActivityEvaluationStatus.PASSED : ActivityEvaluationStatus.FAILED,
            criteria, summary, reason, VERSION, EVIDENCE_VERSION,
            ActivityResourceUsage.evaluationOnly(Duration.ofNanos(System.nanoTime() - started))
        );
    }

    private static ActivityEvaluationResult failed(ArrayList<ActivityCriterionResult> criteria, String summary,
                                                   String reason, long started) {
        return new ActivityEvaluationResult(ActivityEvaluationStatus.FAILED, criteria, summary, reason,
            VERSION, EVIDENCE_VERSION,
            ActivityResourceUsage.evaluationOnly(Duration.ofNanos(System.nanoTime() - started)));
    }
}
