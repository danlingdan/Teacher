package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.application.collaboration.AssignmentAnalyticsFilter;
import com.sqlteacher.application.collaboration.AssignmentStatus;
import com.sqlteacher.application.collaboration.CloudClassroomApi;
import com.sqlteacher.application.collaboration.CloudPlanningApi;
import com.sqlteacher.application.collaboration.CloudLearningSyncService;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.CloudSyncPreferences;
import com.sqlteacher.application.collaboration.DesktopAccessProfile;
import com.sqlteacher.application.collaboration.UserRole;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * v3.4.0 REF-8: cloud classroom surface. Cloud failures degrade to local state; the offline
 * learning flow and the pending sync queue must never depend on cloud availability.
 */
final class CloudApiSection extends ApiSection {

    CloudApiSection(ApiSectionHost host) {
        super(host);
    }

    @Override
    public Set<String> supportedMethods() {
        return Set.of(
            "cloud.workspace", "cloud.sync", "cloud.sync.preferences", "cloud.sync.preferences.update",
            "cloud.class.create", "cloud.class.member.add",
            "cloud.class.roster", "cloud.class.join", "cloud.class.join-code", "cloud.class.join-code.rotate",
            "cloud.assignments", "cloud.assignment.create", "cloud.assignment.update",
            "cloud.assignment.copy", "cloud.assignment.status", "cloud.class.analytics",
            "cloud.class.analytics.export", "cloud.class.analytics.overview", "cloud.class.events",
            "cloud.assignment.analytics", "cloud.assignment.analytics.export",
            "cloud.assignment.snapshot", "cloud.assignment.submit", "cloud.feedback.list",
            "cloud.feedback.save", "cloud.feedback.draft", "cloud.mastery",
            "cloud.notifications", "cloud.notification.read",
            "cloud.courses", "cloud.course.create", "cloud.course.content", "cloud.course.section.create",
            "cloud.course.knowledge.create", "cloud.course.exercise.publish", "cloud.assignment.create-versioned",
            "cloud.course.export", "cloud.course.import", "cloud.course.package.preview",
            "cloud.course.package.import"
        );
    }

    @Override
    public JsonNode handle(String method, JsonNode params, CancellationToken cancellation,
                           Consumer<LocalAppEvent> events) throws Exception {
        return switch (method) {
            case "cloud.workspace" -> cloudWorkspace(params, cancellation);
            case "cloud.sync" -> cloudSync(cancellation, events);
            case "cloud.sync.preferences" -> cloudSyncPreferencesGet(params, cancellation);
            case "cloud.sync.preferences.update" -> cloudSyncPreferencesUpdate(params, cancellation);
            case "cloud.class.create" -> cloudClassCreate(params, cancellation);
            case "cloud.class.member.add" -> cloudClassMemberAdd(params, cancellation);
            case "cloud.class.roster" -> cloudClassRoster(params, cancellation);
            case "cloud.class.join" -> cloudClassJoin(params, cancellation);
            case "cloud.class.join-code" -> cloudClassJoinCode(params, cancellation);
            case "cloud.class.join-code.rotate" -> cloudClassJoinCodeRotate(params, cancellation);
            case "cloud.assignments" -> cloudAssignments(params, cancellation);
            case "cloud.assignment.create" -> cloudAssignmentCreate(params, cancellation);
            case "cloud.assignment.update" -> cloudAssignmentUpdate(params, cancellation);
            case "cloud.assignment.copy" -> cloudAssignmentCopy(params, cancellation);
            case "cloud.assignment.status" -> cloudAssignmentStatus(params, cancellation);
            case "cloud.class.analytics" -> cloudClassAnalytics(params, cancellation);
            case "cloud.class.analytics.export" -> cloudClassAnalyticsExport(params, cancellation);
            case "cloud.class.analytics.overview" -> cloudClassAnalyticsOverview(params, cancellation);
            case "cloud.class.events" -> cloudClassEvents(params, cancellation);
            case "cloud.assignment.analytics" -> cloudAssignmentAnalytics(params, cancellation);
            case "cloud.assignment.analytics.export" -> cloudAssignmentAnalyticsExport(params, cancellation);
            case "cloud.assignment.snapshot" -> cloudAssignmentSnapshot(params, cancellation);
            case "cloud.assignment.submit" -> cloudAssignmentSubmit(params, cancellation);
            case "cloud.feedback.list" -> cloudFeedbackList(params, cancellation);
            case "cloud.feedback.save" -> cloudFeedbackSave(params, cancellation);
            case "cloud.feedback.draft" -> cloudFeedbackDraft(params, cancellation);
            case "cloud.mastery" -> cloudMastery(params, cancellation);
            case "cloud.notifications" -> cloudNotifications(params, cancellation);
            case "cloud.notification.read" -> cloudNotificationRead(params, cancellation);
            case "cloud.courses" -> cloudCourses(params, cancellation);
            case "cloud.course.create" -> cloudCourseCreate(params, cancellation);
            case "cloud.course.content" -> cloudCourseContent(params, cancellation);
            case "cloud.course.section.create" -> cloudCourseSectionCreate(params, cancellation);
            case "cloud.course.knowledge.create" -> cloudCourseKnowledgeCreate(params, cancellation);
            case "cloud.course.exercise.publish" -> cloudCourseExercisePublish(params, cancellation);
            case "cloud.assignment.create-versioned" -> cloudAssignmentCreateVersioned(params, cancellation);
            case "cloud.course.export" -> cloudCourseExport(params, cancellation);
            case "cloud.course.import" -> cloudCourseImport(params, cancellation);
            case "cloud.course.package.preview" -> cloudCoursePackagePreview(params, cancellation);
            case "cloud.course.package.import" -> cloudCoursePackageImport(params, cancellation);
            default -> throw new IllegalStateException("Method whitelist and dispatcher are inconsistent");
        };
    }

    private JsonNode cloudWorkspace(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var core = context();
        var sessions = core.getBean(CloudSessionService.class);
        var current = sessions.current();
        ObjectNode result = mapper.createObjectNode();
        var sync = core.getBean(CloudLearningSyncService.class).status();
        result.set("sync", mapper.valueToTree(sync));
        // v3.7.0 TFB-C1/C2：同步偏好随 workspace 下发，供同步卡渲染开关。
        var preferences = core.getBean(CloudSyncPreferences.class);
        result.put("syncPaused", preferences.uploadPaused());
        result.put("autoSyncEnabled", preferences.autoSyncEnabled());
        result.put("signedIn", current.isPresent());
        result.put("recoverable", true);
        ArrayNode classes = result.putArray("classes");
        if (current.isEmpty()) {
            result.put("state", "SIGNED_OUT");
            result.put("message", "登录后可查看班级、作业与云端同步状态。");
            return result;
        }
        DesktopAccessProfile profile = DesktopAccessProfile.from(current.get());
        result.put("state", "LOCAL_READY");
        result.put("displayName", profile.displayName());
        result.put("role", webRole(profile));
        if (!params.path("refreshRemote").asBoolean(false)) {
            result.put("message", "本地账号与同步队列已读取；手动刷新后再访问云端班级。");
            return result;
        }
        try {
            core.getBean(CloudClassroomApi.class).listClasses(current.get().accessToken())
                .forEach(item -> classes.add(mapper.valueToTree(item)));
            result.put("message", "云端状态已刷新。");
        } catch (RuntimeException error) {
            result.put("state", "DEGRADED");
            result.put("message", "云端暂时不可用；本地学习与待同步队列不受影响。");
            result.put("errorCode", "CLOUD_REFRESH_FAILED");
        }
        cancellation.throwIfCancelled();
        return result;
    }

    private JsonNode cloudSync(CancellationToken cancellation, Consumer<LocalAppEvent> events) {
        cancellation.throwIfCancelled();
        if (context().getBean(CloudSessionService.class).current().isEmpty()) {
            throw new SecurityException("Cloud synchronization requires an authenticated session");
        }
        emit(events, "progress", "phase", "cloud-sync-started");
        var result = context().getBean(CloudLearningSyncService.class).synchronize();
        cancellation.throwIfCancelled();
        emit(events, "progress", "phase", "cloud-sync-completed");
        return mapper.valueToTree(result);
    }

    /** v3.7.0 TFB-C1：读取同步偏好（暂停上传 / 自动同步开关）。 */
    private JsonNode cloudSyncPreferencesGet(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var preferences = context().getBean(CloudSyncPreferences.class);
        ObjectNode result = mapper.createObjectNode();
        result.put("uploadPaused", preferences.uploadPaused());
        result.put("autoSyncEnabled", preferences.autoSyncEnabled());
        return result;
    }

    /** v3.7.0 TFB-C1/C2：更新同步偏好；字段缺省表示不改动。 */
    private JsonNode cloudSyncPreferencesUpdate(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var preferences = context().getBean(CloudSyncPreferences.class);
        if (params.has("uploadPaused")) {
            preferences.uploadPaused(params.path("uploadPaused").asBoolean(false));
        }
        if (params.has("autoSyncEnabled")) {
            preferences.autoSyncEnabled(params.path("autoSyncEnabled").asBoolean(true));
        }
        return cloudSyncPreferencesGet(params, cancellation);
    }

    private JsonNode cloudClassCreate(JsonNode params, CancellationToken cancellation) {
        DesktopAccessProfile profile = requireTeacher();
        cancellation.throwIfCancelled();
        var core = context();
        var session = core.getBean(CloudSessionService.class).current()
            .orElseThrow(() -> new SecurityException("Class creation requires an authenticated session"));
        var classroom = core.getBean(CloudClassroomApi.class).createClass(
            session.accessToken(), requiredText(params, "name", 120));
        cancellation.throwIfCancelled();
        ObjectNode result = mapper.createObjectNode();
        result.set("classroom", mapper.valueToTree(classroom));
        result.put("role", webRole(profile));
        return result;
    }

    private JsonNode cloudClassMemberAdd(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudClassroomApi.class).addClassMember(session.accessToken(),
            requiredText(params, "classroomId", 128), requiredText(params, "email", 320),
            UserRole.valueOf(requiredText(params, "role", 32))));
    }

    private JsonNode cloudClassRoster(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.createObjectNode().set("members", mapper.valueToTree(context().getBean(CloudClassroomApi.class)
            .listClassRoster(session.accessToken(), requiredText(params, "classroomId", 128))));
    }

    // v3.4.1 CLS-3：学生凭班级码自助加入——只要求云会话，不得套 requireTeacher() 门禁。
    private JsonNode cloudClassJoin(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        var classroom = context().getBean(CloudClassroomApi.class).joinClassByCode(
            session.accessToken(), requiredText(params, "code", 32));
        return mapper.valueToTree(classroom);
    }

    private JsonNode cloudClassJoinCode(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.createObjectNode().put("joinCode", context().getBean(CloudClassroomApi.class)
            .classJoinCode(session.accessToken(), requiredText(params, "classroomId", 128)));
    }

    private JsonNode cloudClassJoinCodeRotate(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.createObjectNode().put("joinCode", context().getBean(CloudClassroomApi.class)
            .rotateClassJoinCode(session.accessToken(), requiredText(params, "classroomId", 128)));
    }

    private JsonNode cloudAssignments(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.createObjectNode().set("items", mapper.valueToTree(context().getBean(CloudClassroomApi.class)
            .listAssignments(session.accessToken(), requiredText(params, "classroomId", 128))));
    }

    private JsonNode cloudAssignmentCreate(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        Instant dueAt = optionalInstant(params, "dueAt");
        return mapper.valueToTree(context().getBean(CloudClassroomApi.class).createAssignmentDraft(session.accessToken(),
            requiredText(params, "classroomId", 128), requiredText(params, "exerciseId", 128),
            requiredText(params, "title", 240), params.path("description").asText(""), dueAt));
    }

    private JsonNode cloudAssignmentUpdate(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudClassroomApi.class).updateAssignment(session.accessToken(),
            requiredText(params, "classroomId", 128), requiredText(params, "assignmentId", 128),
            requiredText(params, "title", 240), params.path("description").asText(""),
            optionalInstant(params, "dueAt"), params.path("expectedVersion").asLong()));
    }

    private JsonNode cloudAssignmentCopy(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudClassroomApi.class).copyAssignment(session.accessToken(),
            requiredText(params, "classroomId", 128), requiredText(params, "assignmentId", 128),
            params.path("expectedVersion").asLong()));
    }

    private JsonNode cloudAssignmentStatus(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudClassroomApi.class).changeAssignmentStatus(session.accessToken(),
            requiredText(params, "classroomId", 128), requiredText(params, "assignmentId", 128),
            AssignmentStatus.valueOf(requiredText(params, "status", 32)), params.path("expectedVersion").asLong()));
    }

    private JsonNode cloudClassAnalytics(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudClassroomApi.class).getClassLearningSummary(
            session.accessToken(), requiredText(params, "classroomId", 128)));
    }

    private JsonNode cloudClassAnalyticsExport(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.createObjectNode().put("csv", context().getBean(CloudClassroomApi.class).exportClassLearningCsv(
            session.accessToken(), requiredText(params, "classroomId", 128)));
    }

    /** v3.7.0 TFB-S2: 班级学情总览（汇总 + 7 日活跃 + 类型分布 + 14 日趋势），仅教师。 */
    private JsonNode cloudClassAnalyticsOverview(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudClassroomApi.class).getClassLearningOverview(
            session.accessToken(), requiredText(params, "classroomId", 128)));
    }

    /** v3.7.0 TFB-S1: 教师分页读取本班指定学生的学习事件明细。 */
    private JsonNode cloudClassEvents(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        long cursor = params.path("cursor").asLong(-1);
        int limit = Math.max(1, Math.min(200, params.path("limit").asInt(50)));
        String eventType = params.path("eventType").asText("").trim();
        return mapper.valueToTree(context().getBean(CloudClassroomApi.class).getClassroomEvents(
            session.accessToken(), requiredText(params, "classroomId", 128),
            requiredText(params, "studentUserId", 128),
            eventType.isEmpty() ? null : eventType,
            optionalInstant(params, "from"), optionalInstant(params, "to"),
            cursor < 0 ? null : cursor, limit));
    }

    private JsonNode cloudAssignmentAnalytics(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudClassroomApi.class).getAssignmentAnalytics(session.accessToken(),
            requiredText(params, "classroomId", 128), requiredText(params, "assignmentId", 128),
            assignmentAnalyticsFilter(params)));
    }

    private JsonNode cloudAssignmentAnalyticsExport(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.createObjectNode().put("csv", context().getBean(CloudClassroomApi.class).exportAssignmentAnalyticsCsv(
            session.accessToken(), requiredText(params, "classroomId", 128),
            requiredText(params, "assignmentId", 128), assignmentAnalyticsFilter(params)));
    }

    static AssignmentAnalyticsFilter assignmentAnalyticsFilter(JsonNode params) {
        String rawStatus = params.path("status").asText("").trim();
        var status = rawStatus.isEmpty() ? null
            : com.sqlteacher.application.collaboration.AssignmentStudentStatus.valueOf(rawStatus);
        return new AssignmentAnalyticsFilter(status, optionalInstant(params, "from"), optionalInstant(params, "to"),
            Math.max(0, params.path("page").asInt(0)),
            Math.max(1, Math.min(200, params.path("pageSize").asInt(50))));
    }

    private JsonNode cloudAssignmentSnapshot(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudPlanningApi.class).getAssignmentContentSnapshot(
            session.accessToken(), requiredText(params, "classroomId", 128),
            requiredText(params, "assignmentId", 128)));
    }

    private JsonNode cloudAssignmentSubmit(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        requireCloudSession();
        var service = context().getBean(com.sqlteacher.application.collaboration.AssignmentDeliveryService.class);
        Instant completedAt = optionalInstant(params, "completedAt");
        // v3.7.0 TFB-S3：随提交上送截断 SQL 与得分（可选，客户端已白名单化）。
        String sqlText = params.path("sqlText").asText("").trim();
        Integer score = params.hasNonNull("score") && params.path("score").isInt()
            ? Math.max(0, Math.min(100, params.path("score").asInt())) : null;
        var result = service.deliver(requiredText(params, "classroomId", 128),
            requiredText(params, "assignmentId", 128), params.path("passed").asBoolean(false),
            optionalErrorCode(params), completedAt == null ? Instant.now() : completedAt,
            sqlText.isEmpty() ? null : sqlText, score);
        cancellation.throwIfCancelled();
        ObjectNode response = mapper.valueToTree(result);
        response.put("pending", service.pendingCount());
        return response;
    }

    private JsonNode cloudFeedbackList(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        String classroomId = requiredText(params, "classroomId", 128);
        String assignmentId = requiredText(params, "assignmentId", 128);
        var cache = context().getBean(com.sqlteacher.application.collaboration.TeachingContentCache.class);
        List<com.sqlteacher.application.collaboration.SubmissionFeedback> items;
        boolean cached = !params.path("refreshRemote").asBoolean(false);
        if (cached) {
            items = cache.loadFeedback(session.user().id(), assignmentId);
        } else {
            try {
                items = context().getBean(CloudClassroomApi.class).listSubmissionFeedback(
                    session.accessToken(), classroomId, assignmentId);
                cache.saveFeedback(session.user().id(), assignmentId, items);
            } catch (RuntimeException error) {
                items = cache.loadFeedback(session.user().id(), assignmentId);
                cached = true;
            }
        }
        ObjectNode response = mapper.createObjectNode();
        response.set("items", mapper.valueToTree(items));
        response.put("cached", cached);
        return response;
    }

    private JsonNode cloudFeedbackSave(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        List<String> knowledgePointIds = params.path("knowledgePointIds").isArray()
            ? mapper.convertValue(params.path("knowledgePointIds"),
                mapper.getTypeFactory().constructCollectionType(List.class, String.class))
            : List.of();
        return mapper.valueToTree(context().getBean(CloudClassroomApi.class).saveSubmissionFeedback(
            session.accessToken(), requiredText(params, "classroomId", 128),
            requiredText(params, "assignmentId", 128), requiredText(params, "submissionId", 128),
            com.sqlteacher.application.collaboration.FeedbackStatus.valueOf(requiredText(params, "status", 32)),
            params.path("comment").asText(""), knowledgePointIds, params.path("expectedVersion").asLong(0),
            UUID.randomUUID().toString()));
    }

    private JsonNode cloudFeedbackDraft(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudClassroomApi.class).draftSubmissionFeedback(
            session.accessToken(), requiredText(params, "classroomId", 128),
            requiredText(params, "assignmentId", 128), requiredText(params, "submissionId", 128)));
    }

    private JsonNode cloudMastery(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        String classroomId = requiredText(params, "classroomId", 128);
        String requestedStudent = params.path("studentUserId").asText("").trim();
        DesktopAccessProfile profile = currentAccessProfile();
        String studentId = profile.kind() == DesktopAccessProfile.Kind.STUDENT ? session.user().id()
            : (requestedStudent.isEmpty() ? session.user().id() : requestedStudent);
        var cache = context().getBean(com.sqlteacher.application.collaboration.TeachingContentCache.class);
        List<com.sqlteacher.application.collaboration.KnowledgeMastery> items;
        boolean cached = !params.path("refreshRemote").asBoolean(false);
        if (cached) {
            items = cache.loadMastery(session.user().id(), classroomId, studentId);
        } else {
            try {
                items = context().getBean(CloudClassroomApi.class).getKnowledgeMastery(
                    session.accessToken(), classroomId, studentId);
                cache.saveMastery(session.user().id(), classroomId, studentId, items);
            } catch (RuntimeException error) {
                items = cache.loadMastery(session.user().id(), classroomId, studentId);
                cached = true;
            }
        }
        ObjectNode response = mapper.createObjectNode();
        response.set("items", mapper.valueToTree(items));
        response.put("cached", cached);
        response.put("studentUserId", studentId);
        return response;
    }

    private JsonNode cloudNotifications(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        var cache = context().getBean(com.sqlteacher.application.collaboration.TeachingContentCache.class);
        List<com.sqlteacher.application.collaboration.CloudNotification> items;
        boolean cached = !params.path("refreshRemote").asBoolean(false);
        if (cached) {
            items = cache.loadNotifications(session.user().id());
        } else {
            try {
                int page = Math.max(0, params.path("page").asInt(0));
                int pageSize = Math.max(1, Math.min(100, params.path("pageSize").asInt(50)));
                items = context().getBean(CloudClassroomApi.class).listNotifications(
                    session.accessToken(), page, pageSize);
                cache.saveNotifications(session.user().id(), items);
            } catch (RuntimeException error) {
                items = cache.loadNotifications(session.user().id());
                cached = true;
            }
        }
        ObjectNode response = mapper.createObjectNode();
        response.set("items", mapper.valueToTree(items));
        response.put("cached", cached);
        response.put("unread", items.stream().filter(
            com.sqlteacher.application.collaboration.CloudNotification::unread).count());
        return response;
    }

    private JsonNode cloudNotificationRead(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        var notification = context().getBean(CloudClassroomApi.class).markNotificationRead(
            session.accessToken(), requiredText(params, "notificationId", 128));
        var cache = context().getBean(com.sqlteacher.application.collaboration.TeachingContentCache.class);
        var items = cache.loadNotifications(session.user().id()).stream()
            .map(item -> item.id().equals(notification.id()) ? notification : item).toList();
        cache.saveNotifications(session.user().id(), items);
        return mapper.valueToTree(notification);
    }

    private JsonNode cloudCourses(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        var cache = context().getBean(com.sqlteacher.application.collaboration.TeachingContentCache.class);
        List<com.sqlteacher.application.collaboration.CourseCatalog> items;
        boolean cached = !params.path("refreshRemote").asBoolean(false);
        if (cached) {
            items = cache.loadCourses(session.user().id());
        } else {
            try {
                items = context().getBean(CloudPlanningApi.class).listCourses(session.accessToken());
                cache.saveCourses(session.user().id(), items);
            } catch (RuntimeException error) {
                items = cache.loadCourses(session.user().id());
                cached = true;
            }
        }
        ObjectNode response = mapper.createObjectNode();
        response.set("items", mapper.valueToTree(items));
        response.put("cached", cached);
        return response;
    }

    private JsonNode cloudCourseCreate(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudPlanningApi.class).createCourse(session.accessToken(),
            requiredText(params, "name", 120), params.path("description").asText("")));
    }

    private JsonNode cloudCourseContent(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        String courseId = requiredText(params, "courseId", 128);
        var cache = context().getBean(com.sqlteacher.application.collaboration.TeachingContentCache.class);
        com.sqlteacher.application.collaboration.CachedCourseContent content;
        boolean cached = !params.path("refreshRemote").asBoolean(false);
        if (cached) {
            content = cache.loadCourseContent(session.user().id(), courseId);
        } else {
            try {
                var api = context().getBean(CloudPlanningApi.class);
                content = new com.sqlteacher.application.collaboration.CachedCourseContent(
                    api.listCourseSections(session.accessToken(), courseId),
                    api.listKnowledgePoints(session.accessToken(), courseId),
                    api.listSharedExercises(session.accessToken(), courseId, null));
                cache.saveCourseContent(session.user().id(), courseId, content);
            } catch (RuntimeException error) {
                content = cache.loadCourseContent(session.user().id(), courseId);
                cached = true;
            }
        }
        ObjectNode response = mapper.valueToTree(content);
        response.put("cached", cached);
        return response;
    }

    private JsonNode cloudCourseSectionCreate(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudPlanningApi.class).createCourseSection(
            session.accessToken(), requiredText(params, "courseId", 128), requiredText(params, "name", 120),
            Math.max(0, params.path("sortOrder").asInt(0))));
    }

    private JsonNode cloudCourseKnowledgeCreate(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudPlanningApi.class).createKnowledgePoint(
            session.accessToken(), requiredText(params, "courseId", 128),
            requiredText(params, "sectionId", 128), requiredText(params, "name", 160),
            params.path("description").asText(""), Math.max(0, params.path("sortOrder").asInt(0))));
    }

    private JsonNode cloudCourseExercisePublish(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        List<String> knowledgePointIds = params.path("knowledgePointIds").isArray()
            ? mapper.convertValue(params.path("knowledgePointIds"),
                mapper.getTypeFactory().constructCollectionType(List.class, String.class))
            : List.of();
        return mapper.valueToTree(context().getBean(CloudPlanningApi.class).publishSharedExercise(
            session.accessToken(), requiredText(params, "courseId", 128),
            requiredText(params, "exerciseId", 128), requiredText(params, "title", 240),
            requiredText(params, "prompt", 16_384), requiredText(params, "datasetVersion", 128),
            requiredText(params, "evaluationRule", 16_384), knowledgePointIds, UUID.randomUUID().toString()));
    }

    private JsonNode cloudAssignmentCreateVersioned(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudPlanningApi.class).createAssignmentFromVersion(
            session.accessToken(), requiredText(params, "classroomId", 128),
            requiredText(params, "exerciseVersionId", 128), requiredText(params, "title", 240),
            params.path("description").asText(""), optionalInstant(params, "dueAt"), UUID.randomUUID().toString()));
    }

    private JsonNode cloudCourseExport(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.createObjectNode().put("content", context().getBean(CloudPlanningApi.class).exportCourseBundle(
            session.accessToken(), requiredText(params, "courseId", 128)));
    }

    private JsonNode cloudCourseImport(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudPlanningApi.class).importCourseBundle(session.accessToken(),
            requiredText(params, "content", 900_000), UUID.randomUUID().toString()));
    }

    private JsonNode cloudCoursePackagePreview(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudPlanningApi.class).previewCoursePackage(
            session.accessToken(), requiredText(params, "content", 900_000)));
    }

    private JsonNode cloudCoursePackageImport(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        if (!params.path("licenseConfirmed").asBoolean(false)) {
            throw new SecurityException("Course package license confirmation is required");
        }
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudPlanningApi.class).importCoursePackage(
            session.accessToken(), requiredText(params, "content", 900_000), UUID.randomUUID().toString(),
            requiredText(params, "expectedSha256", 64), true));
    }
}
