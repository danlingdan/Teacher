package com.sqlteacher.application.planning;

import com.sqlteacher.application.collaboration.ContentStatus;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DeterministicStudyPlanService implements StudyPlanService {
    public static final String POLICY_VERSION = "v1.9.0-r1";
    private static final int MAX_ACTIONS = 7;

    @Override
    public StudyPlanSnapshot generate(String ownerId, String courseId, List<CourseObjective> requestedObjectives,
                                      List<ObjectivePrerequisite> requestedPrerequisites,
                                      List<ObjectiveResourceLink> requestedResources,
                                      Set<String> requestedCompletedObjectiveIds, Instant generatedAt) {
        String normalizedOwner = required(ownerId, "ownerId");
        String normalizedCourse = required(courseId, "courseId");
        if (generatedAt == null) throw new IllegalArgumentException("generatedAt must not be null");
        List<CourseObjective> objectives = requestedObjectives == null ? List.of() : requestedObjectives.stream()
            .filter(item -> item.status() == ContentStatus.ACTIVE && normalizedCourse.equals(item.courseId()))
            .sorted(Comparator.comparingInt(CourseObjective::sortOrder).thenComparing(CourseObjective::id)).toList();
        Set<String> objectiveIds = objectives.stream().map(CourseObjective::id)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> completed = requestedCompletedObjectiveIds == null ? Set.of()
            : requestedCompletedObjectiveIds.stream().filter(objectiveIds::contains).collect(java.util.stream.Collectors.toUnmodifiableSet());

        Map<String, List<String>> prerequisites = new HashMap<>();
        if (requestedPrerequisites != null) {
            requestedPrerequisites.stream()
                .filter(item -> objectiveIds.contains(item.objectiveId()) && objectiveIds.contains(item.prerequisiteObjectiveId()))
                .forEach(item -> prerequisites.computeIfAbsent(item.objectiveId(), ignored -> new ArrayList<>())
                    .add(item.prerequisiteObjectiveId()));
        }
        Map<String, List<ObjectiveResourceLink>> resources = new HashMap<>();
        if (requestedResources != null) {
            requestedResources.stream().filter(item -> objectiveIds.contains(item.objectiveId()))
                .forEach(item -> resources.computeIfAbsent(item.objectiveId(), ignored -> new ArrayList<>()).add(item));
        }
        resources.values().forEach(items -> items.sort(Comparator.comparingInt(DeterministicStudyPlanService::resourceRank)
            .thenComparing(ObjectiveResourceLink::resourceId)));

        Map<String, CourseObjective> byId = objectives.stream()
            .collect(java.util.stream.Collectors.toMap(CourseObjective::id, item -> item));
        Map<String, StudyPlanAction> actionsByObjective = new HashMap<>();
        for (CourseObjective objective : objectives) {
            if (completed.contains(objective.id())) continue;
            String unmet = prerequisites.getOrDefault(objective.id(), List.of()).stream()
                .filter(id -> !completed.contains(id)).sorted().findFirst().orElse(null);
            CourseObjective target = unmet == null ? objective : byId.get(unmet);
            if (target == null) continue;
            ObjectiveResourceLink resource = resources.getOrDefault(target.id(), List.of()).stream().findFirst().orElse(null);
            if (resource == null) continue;
            StudyPlanReasonCode reason = unmet == null ? StudyPlanReasonCode.INSUFFICIENT_EVIDENCE
                : StudyPlanReasonCode.PREREQUISITE_GAP;
            int priority = unmet == null ? Math.max(40, 75 - objective.sortOrder()) : 90;
            StudyPlanAction candidate = new StudyPlanAction(stableId(normalizedOwner, normalizedCourse, target.id(), resource),
                target.id(), actionType(resource.resourceType()), target.title(), description(target, reason),
                resource.resourceType(), resource.resourceId(), reason, priority);
            actionsByObjective.merge(target.id(), candidate,
                (existing, replacement) -> replacement.priority() > existing.priority() ? replacement : existing);
        }
        List<StudyPlanAction> ordered = actionsByObjective.values().stream()
            .sorted(Comparator.comparingInt(StudyPlanAction::priority).reversed()
                .thenComparing(StudyPlanAction::objectiveId).thenComparing(StudyPlanAction::id))
            .limit(MAX_ACTIONS).toList();
        return new StudyPlanSnapshot(normalizedOwner, normalizedCourse, POLICY_VERSION, generatedAt,
            generatedAt.plus(Duration.ofDays(7)), ordered);
    }

    private static StudyPlanActionType actionType(ObjectiveResourceType type) {
        return type == ObjectiveResourceType.EXERCISE_VERSION ? StudyPlanActionType.PRACTICE_EXERCISE
            : StudyPlanActionType.REVIEW_KNOWLEDGE;
    }

    private static int resourceRank(ObjectiveResourceLink item) {
        return switch (item.resourceType()) {
            case EXERCISE_VERSION -> 0;
            case KNOWLEDGE_ARTICLE -> 1;
            case KNOWLEDGE_POINT -> 2;
        };
    }

    private static String description(CourseObjective objective, StudyPlanReasonCode reason) {
        return reason == StudyPlanReasonCode.PREREQUISITE_GAP
            ? "先完成先修目标：" + objective.title()
            : "当前证据不足，从课程目标的关联资源开始学习。";
    }

    private static String stableId(String ownerId, String courseId, String objectiveId,
                                   ObjectiveResourceLink resource) {
        String value = ownerId + ':' + courseId + ':' + objectiveId + ':' + resource.resourceType() + ':'
            + resource.resourceId() + ':' + POLICY_VERSION;
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
