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

    public DefaultStudentLearningQueueService(LearningDiagnosisService diagnosis, CloudApiClient api,
                                              CloudSessionService sessions) {
        this(diagnosis, api, sessions, Clock.systemUTC());
    }

    DefaultStudentLearningQueueService(LearningDiagnosisService diagnosis, CloudApiClient api,
                                       CloudSessionService sessions, Clock clock) {
        this.diagnosis = Objects.requireNonNull(diagnosis); this.api = Objects.requireNonNull(api);
        this.sessions = Objects.requireNonNull(sessions); this.clock = Objects.requireNonNull(clock);
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
            items.addAll(cloudItems);
            return new StudentLearningQueue(dashboard, limit(items), true);
        } catch (RuntimeException unavailable) {
            return new StudentLearningQueue(dashboard, limit(items), false);
        }
    }

    @Override
    public void dismiss(StudentLearningQueueItem item) {
        Objects.requireNonNull(item);
        diagnosis.dismissAction(item.action().id());
    }

    @Override
    public void complete(StudentLearningQueueItem item) {
        Objects.requireNonNull(item);
        if (!item.notificationId().isBlank()) {
            var current = sessions.current().orElseThrow(() -> new IllegalStateException("请先登录云端账号"));
            api.markNotificationRead(current.accessToken(), item.notificationId());
        }
        diagnosis.dismissAction(item.action().id());
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
        if (!item.action().exerciseId().isBlank()) return "exercise:" + item.action().exerciseId();
        return item.action().id();
    }
}
