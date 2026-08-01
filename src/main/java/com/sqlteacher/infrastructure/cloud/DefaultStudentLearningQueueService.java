package com.sqlteacher.infrastructure.cloud;

import com.sqlteacher.application.collaboration.AssignmentSubmissionStatus;
import com.sqlteacher.application.collaboration.AssignmentTaskContext;
import com.sqlteacher.application.collaboration.AssignmentStatus;
import com.sqlteacher.application.collaboration.CloudApiClient;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.NotificationType;
import com.sqlteacher.application.collaboration.UserRole;
import com.sqlteacher.application.learning.DiagnosisReasonCode;
import com.sqlteacher.application.learning.LearningAction;
import com.sqlteacher.application.learning.LearningActionType;
import com.sqlteacher.application.learning.LearningDiagnosisService;
import com.sqlteacher.application.learning.StudentLearningQueue;
import com.sqlteacher.application.learning.StudentLearningQueueItem;
import com.sqlteacher.application.learning.StudentLearningQueueService;
import com.sqlteacher.application.learning.StudyPlanActionContext;
import com.sqlteacher.application.planning.ObjectiveResourceType;
import com.sqlteacher.application.planning.StudyPlanAction;
import com.sqlteacher.application.planning.StudyPlanActionState;
import com.sqlteacher.application.planning.StudyPlanCache;
import com.sqlteacher.application.planning.LearningEvidenceRef;
import com.sqlteacher.application.planning.LearningEvidenceType;
import com.sqlteacher.application.planning.StudyPlanReasonCode;
import com.sqlteacher.application.planning.StudyPlanSnapshot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DefaultStudentLearningQueueService implements StudentLearningQueueService {
    private final LearningDiagnosisService diagnosis;
    private final CloudApiClient api;
    private final CloudSessionService sessions;
    private final Clock clock;
    private final StudyPlanCache planCache;

    public DefaultStudentLearningQueueService(LearningDiagnosisService diagnosis, CloudApiClient api,
                                              CloudSessionService sessions) {
        this(diagnosis, api, sessions, null, Clock.systemUTC());
    }

    public DefaultStudentLearningQueueService(LearningDiagnosisService diagnosis, CloudApiClient api,
                                              CloudSessionService sessions, StudyPlanCache planCache) {
        this(diagnosis, api, sessions, planCache, Clock.systemUTC());
    }

    DefaultStudentLearningQueueService(LearningDiagnosisService diagnosis, CloudApiClient api,
                                       CloudSessionService sessions, Clock clock) {
        this(diagnosis, api, sessions, null, clock);
    }

    DefaultStudentLearningQueueService(LearningDiagnosisService diagnosis, CloudApiClient api,
                                       CloudSessionService sessions, StudyPlanCache planCache, Clock clock) {
        this.diagnosis = Objects.requireNonNull(diagnosis); this.api = Objects.requireNonNull(api);
        this.sessions = Objects.requireNonNull(sessions); this.planCache = planCache;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public StudentLearningQueue refresh() {
        var dashboard = diagnosis.refresh();
        List<StudentLearningQueueItem> items = new ArrayList<>();
        dashboard.actions().forEach(action -> items.add(new StudentLearningQueueItem(action, null, "")));
        var current = sessions.current();
        if (current.isEmpty()) return new StudentLearningQueue(dashboard, limit(items), false);
        String token = current.orElseThrow().accessToken();
        String userId = current.orElseThrow().user().id();
        Instant now = clock.instant();
        List<StudentLearningQueueItem> cloudItems = new ArrayList<>();
        try {
            retryPending(token);
            for (var classroom : api.listClasses(token)) {
                boolean student = classroom.members().stream().anyMatch(member -> member.userId().equals(userId)
                    && member.role() == UserRole.STUDENT);
                if (!student) continue;
                for (var assignment : api.listAssignments(token, classroom.id())) {
                    if (assignment.status() != AssignmentStatus.PUBLISHED) continue;
                    boolean passed = api.listOwnAssignmentSubmissions(token, classroom.id(), assignment.id()).stream()
                        .anyMatch(item -> item.status() == AssignmentSubmissionStatus.PASSED);
                    if (passed) continue;
                    boolean overdue = assignment.dueAt() != null && !assignment.dueAt().isAfter(now);
                    String id = "assignment:" + assignment.id() + ":" + assignment.version();
                    if (diagnosis.isActionDismissed(id)) continue;
                    int priority = overdue ? 100 : assignment.dueAt() != null
                        && Duration.between(now, assignment.dueAt()).compareTo(Duration.ofDays(2)) <= 0 ? 95 : 80;
                    LearningAction action = new LearningAction(id, LearningActionType.COMPLETE_ASSIGNMENT,
                        (overdue ? "已逾期：" : "班级任务：") + assignment.title(),
                        overdue ? "任务已经截止，可前往班级页查看历史提交。" : "来自“" + classroom.name() + "”，可直接继续。",
                        overdue ? "" : assignment.exerciseId(), "", overdue
                            ? DiagnosisReasonCode.OVERDUE_TASK : DiagnosisReasonCode.PENDING_ASSIGNMENT,
                        priority, assignment.updatedAt(), false);
                    cloudItems.add(new StudentLearningQueueItem(action,
                        overdue ? null : new AssignmentTaskContext(classroom.id(), assignment), ""));
                }
            }
            for (var notification : api.listNotifications(token, 0, 50)) {
                if (!notification.unread() || notification.type() != NotificationType.FEEDBACK_PUBLISHED) continue;
                String id = "feedback:" + notification.id();
                if (diagnosis.isActionDismissed(id)) continue;
                LearningAction action = new LearningAction(id, LearningActionType.REVIEW_FEEDBACK,
                    notification.title(), notification.message(), "", "", DiagnosisReasonCode.UNREAD_FEEDBACK,
                    90, notification.createdAt(), false);
                cloudItems.add(new StudentLearningQueueItem(action, null, notification.id()));
            }
            for (var course : api.listCourses(token)) {
                if (course.status() != com.sqlteacher.application.collaboration.ContentStatus.ACTIVE) continue;
                var courseObjectives = api.listCourseObjectives(token, course.id());
                if (planCache != null) planCache.saveObjectives(course.id(), courseObjectives);
                var fetchedPlan = groundPlan(api.getStudyPlan(token, course.id()),
                    api.listKnowledgePoints(token, course.id()), dashboard);
                var plan = planCache == null ? fetchedPlan : planCache.save(fetchedPlan).snapshot();
                String planCourseId = plan.courseId();
                plan.actions().stream().filter(action -> action.state() != StudyPlanActionState.COMPLETED
                    && action.state() != StudyPlanActionState.DISMISSED).map(action -> planItem(planCourseId, action))
                    .forEach(cloudItems::add);
            }
            items.addAll(cloudItems);
            return new StudentLearningQueue(dashboard, limit(items), true);
        } catch (RuntimeException unavailable) {
            if (planCache != null) {
                planCache.currentPlans().forEach(plan -> plan.actions().stream()
                    .filter(action -> action.state() != StudyPlanActionState.COMPLETED
                        && action.state() != StudyPlanActionState.DISMISSED)
                    .map(action -> planItem(plan.courseId(), action)).forEach(items::add));
            }
            return new StudentLearningQueue(dashboard, limit(items), false);
        }
    }

    @Override
    public void dismiss(StudentLearningQueueItem item) {
        Objects.requireNonNull(item);
        if (item.studyPlanAction() != null) {
            updatePlan(item, StudyPlanActionState.DISMISSED);
            return;
        }
        diagnosis.dismissAction(item.action().id());
    }

    @Override
    public void complete(StudentLearningQueueItem item) {
        Objects.requireNonNull(item);
        if (item.studyPlanAction() != null) {
            updatePlan(item, StudyPlanActionState.COMPLETED);
            return;
        }
        if (!item.notificationId().isBlank()) {
            var current = sessions.current().orElseThrow(() -> new IllegalStateException("请先登录云端账号"));
            api.markNotificationRead(current.accessToken(), item.notificationId());
        }
        diagnosis.dismissAction(item.action().id());
    }

    @Override
    public void start(StudentLearningQueueItem item) {
        Objects.requireNonNull(item);
        if (item.studyPlanAction() != null && item.action().dismissed() == false) {
            updatePlan(item, StudyPlanActionState.STARTED);
        }
    }

    private void updatePlan(StudentLearningQueueItem item, StudyPlanActionState state) {
        StudyPlanActionContext context = item.studyPlanAction();
        var operation = planCache == null ? null : planCache.updateAction(context.courseId(), context.actionId(), state);
        var current = sessions.current();
        if (current.isEmpty()) return;
        try {
            var delivered = api.updateStudyPlanAction(current.orElseThrow().accessToken(), context.courseId(), context.actionId(),
                state, operation == null ? context.stateVersion() : operation.expectedVersion(),
                operation == null ? java.util.UUID.randomUUID().toString() : operation.operationId());
            if (planCache != null) planCache.markDelivered(operation.operationId(), context.actionId(), delivered.version());
        } catch (RuntimeException cloudUnavailable) {
            if (planCache != null && operation != null) {
                boolean retryable = !(cloudUnavailable instanceof com.sqlteacher.application.collaboration.CloudApiRequestException request)
                    || request.retryable();
                String code = cloudUnavailable instanceof com.sqlteacher.application.collaboration.CloudApiRequestException request
                    ? request.code() : "CLOUD_UNAVAILABLE";
                planCache.markFailed(operation.operationId(), code, retryable);
            }
            if (planCache == null) throw cloudUnavailable;
        }
    }

    private void retryPending(String token) {
        if (planCache == null) return;
        for (var operation : planCache.pending()) {
            try {
                var delivered = api.updateStudyPlanAction(token, operation.courseId(), operation.actionId(),
                    operation.state(), operation.expectedVersion(), operation.operationId());
                planCache.markDelivered(operation.operationId(), operation.actionId(), delivered.version());
            } catch (com.sqlteacher.application.collaboration.CloudApiRequestException error) {
                planCache.markFailed(operation.operationId(), error.code(), error.retryable());
                if (error.retryable()) throw error;
            }
        }
    }

    private static StudentLearningQueueItem planItem(String courseId, StudyPlanAction item) {
        boolean exercise = item.resourceType() == ObjectiveResourceType.EXERCISE_VERSION;
        LearningAction action = new LearningAction("plan:" + item.id(), exercise
            ? LearningActionType.RETRY_EXERCISE : LearningActionType.REVIEW_KNOWLEDGE,
            item.title(), item.description(), exercise ? item.resourceId() : "",
            exercise ? "" : item.title(), item.reasonCode() == com.sqlteacher.application.planning.StudyPlanReasonCode.PREREQUISITE_GAP
            ? DiagnosisReasonCode.PREREQUISITE_GAP : item.reasonCode() == StudyPlanReasonCode.NEEDS_PRACTICE
            ? DiagnosisReasonCode.REPEATED_FAILURE : item.reasonCode() == StudyPlanReasonCode.DEVELOPING_PROGRESS
            ? DiagnosisReasonCode.DEVELOPING_PROGRESS : DiagnosisReasonCode.INSUFFICIENT_EVIDENCE,
            item.priority(), java.time.Instant.EPOCH, item.state() == StudyPlanActionState.DISMISSED);
        return new StudentLearningQueueItem(action, null, "", new StudyPlanActionContext(courseId, item.id(),
            item.resourceType(), item.resourceId(), item.stateVersion()));
    }

    private static StudyPlanSnapshot groundPlan(StudyPlanSnapshot plan,
                                                List<com.sqlteacher.application.collaboration.KnowledgePoint> points,
                                                com.sqlteacher.application.learning.LearningDashboard dashboard) {
        Map<String, String> names = points.stream().collect(java.util.stream.Collectors.toMap(
            com.sqlteacher.application.collaboration.KnowledgePoint::id,
            com.sqlteacher.application.collaboration.KnowledgePoint::name));
        Map<String, com.sqlteacher.application.learning.MasterySnapshot> mastery = dashboard.mastery().stream()
            .collect(java.util.stream.Collectors.toMap(item -> item.knowledgePoint().toLowerCase(java.util.Locale.ROOT),
                item -> item, (left, right) -> left));
        List<StudyPlanAction> actions = plan.actions().stream().map(action -> {
            if (action.resourceType() != ObjectiveResourceType.KNOWLEDGE_POINT) return action;
            String name = names.get(action.resourceId());
            var evidence = name == null ? null : mastery.get(name.toLowerCase(java.util.Locale.ROOT));
            if (evidence == null) return action;
            StudyPlanReasonCode reason = switch (evidence.level()) {
                case NEEDS_PRACTICE -> StudyPlanReasonCode.NEEDS_PRACTICE;
                case DEVELOPING -> StudyPlanReasonCode.DEVELOPING_PROGRESS;
                case MASTERED -> StudyPlanReasonCode.MASTERY_MAINTENANCE;
                case UNKNOWN -> action.reasonCode();
            };
            int priority = switch (evidence.level()) {
                case NEEDS_PRACTICE -> Math.max(action.priority(), 85);
                case DEVELOPING -> Math.max(action.priority(), 65);
                case MASTERED -> Math.min(action.priority(), 30);
                case UNKNOWN -> action.priority();
            };
            List<LearningEvidenceRef> refs = evidence.evidence().stream().map(item -> new LearningEvidenceRef(
                LearningEvidenceType.EXERCISE_ATTEMPT, item.sourceId(), dashboard.policyVersion(),
                java.util.UUID.nameUUIDFromBytes((item.sourceId() + ':' + item.kind() + ':' + item.successful())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(), item.occurredAt())).toList();
            return new StudyPlanAction(action.id(), action.objectiveId(), action.type(), action.title(),
                evidence.level() == com.sqlteacher.application.learning.MasteryLevel.MASTERED
                    ? "当前证据达到掌握水平，可进行低频保持练习。" : action.description(), action.resourceType(),
                action.resourceId(), reason, priority, action.state(),
                "新的真实练习、任务反馈或阅读事实会触发重算", refs, action.stateVersion());
        }).toList();
        String watermarkSource = plan.factWatermark() + ':' + actions.stream().flatMap(item -> item.evidence().stream())
            .map(LearningEvidenceRef::contentHash).sorted().collect(java.util.stream.Collectors.joining(","));
        String watermark = java.util.UUID.nameUUIDFromBytes(watermarkSource
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return new StudyPlanSnapshot(plan.ownerId(), plan.courseId(), plan.policyVersion(), watermark,
            plan.generatedAt(), plan.expiresAt(), actions);
    }

    private static List<StudentLearningQueueItem> limit(List<StudentLearningQueueItem> source) {
        Map<String, StudentLearningQueueItem> unique = new LinkedHashMap<>();
        source.stream().sorted(Comparator.comparingInt((StudentLearningQueueItem item) -> item.action().priority())
                .reversed().thenComparing(item -> item.action().updatedAt(), Comparator.reverseOrder())
                .thenComparing(item -> item.action().id()))
            .forEach(item -> unique.putIfAbsent(dedupKey(item), item));
        return unique.values().stream().limit(7).toList();
    }

    private static String dedupKey(StudentLearningQueueItem item) {
        if (item.assignmentTask() != null) return "assignment:" + item.assignmentTask().assignment().id();
        if (item.studyPlanAction() != null) return "plan:" + item.studyPlanAction().actionId();
        if (!item.action().exerciseId().isBlank()) return "exercise:" + item.action().exerciseId();
        return item.action().id();
    }
}
