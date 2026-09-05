package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.application.config.ApplicationVersion;
import com.sqlteacher.application.collaboration.CloudApiClient;
import com.sqlteacher.application.collaboration.CloudLearningSyncService;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.DesktopAccessProfile;
import com.sqlteacher.application.collaboration.UserRole;
import com.sqlteacher.application.collaboration.AssignmentStatus;
import com.sqlteacher.application.collaboration.AssignmentAnalyticsFilter;
import com.sqlteacher.application.component.ManagedComponentService;
import com.sqlteacher.application.component.ManagedComponentId;
import com.sqlteacher.application.database.DatabaseInitializationService;
import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.connection.DatabaseConnectionProfile;
import com.sqlteacher.application.connection.DatabaseConnectionTarget;
import com.sqlteacher.application.connection.DatabaseConnectionTestService;
import com.sqlteacher.application.connection.DatabaseCredentialSession;
import com.sqlteacher.application.connection.DatabaseDialect;
import com.sqlteacher.application.connection.FileDatabaseConnectionTarget;
import com.sqlteacher.application.connection.GenericJdbcConnectionTarget;
import com.sqlteacher.application.connection.ServerConnectionTarget;
import com.sqlteacher.application.connection.SqliteConnectionTarget;
import com.sqlteacher.application.course.CourseMapService;
import com.sqlteacher.application.execution.SqlExecutionRequest;
import com.sqlteacher.application.execution.SqlExecutionResult;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.application.exercise.ExerciseAttemptResult;
import com.sqlteacher.application.exercise.ExerciseCatalogService;
import com.sqlteacher.application.exercise.ExerciseManagementService;
import com.sqlteacher.application.exercise.ExercisePracticeService;
import com.sqlteacher.application.exercise.ExerciseProgressService;
import com.sqlteacher.application.exercise.ExerciseDraft;
import com.sqlteacher.application.exercise.ExerciseTextDraftingService;
import com.sqlteacher.application.analytics.AnalyticsFilter;
import com.sqlteacher.application.analytics.LearningAnalyticsService;
import com.sqlteacher.application.activity.ActivityLearningService;
import com.sqlteacher.application.learning.InterventionService;
import com.sqlteacher.application.learning.InterventionStatus;
import com.sqlteacher.domain.exercise.ExerciseDifficulty;
import com.sqlteacher.domain.exercise.ExerciseEvaluationRule;
import com.sqlteacher.application.knowledge.CourseKnowledgeSearchFilter;
import com.sqlteacher.application.knowledge.CourseKnowledgeService;
import com.sqlteacher.application.knowledge.GroundedKnowledgeExplanationService;
import com.sqlteacher.application.knowledge.ObsidianVaultImportService;
import com.sqlteacher.application.knowledge.KnowledgeDocumentService;
import com.sqlteacher.application.knowledge.KnowledgeIndexService;
import com.sqlteacher.application.knowledge.KnowledgeReadStateService;
import com.sqlteacher.application.knowledge.KnowledgeVisibility;
import com.sqlteacher.application.learning.MasteryLevel;
import com.sqlteacher.application.learning.LearningDiagnosisService;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.nl2sql.Nl2SqlRequest;
import com.sqlteacher.application.nl2sql.Nl2SqlSafetyService;
import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.application.risk.SqlSafetyModeService;
import com.sqlteacher.application.runner.CodeRunRequest;
import com.sqlteacher.application.runner.LocalCodeRunner;
import com.sqlteacher.application.system.GeneralSoftwareService;
import com.sqlteacher.application.system.GeneralSoftwareSettings;
import com.sqlteacher.application.maintenance.ApplicationBackupService;
import com.sqlteacher.application.maintenance.DataMaintenanceService;
import com.sqlteacher.application.update.UpdateService;
import com.sqlteacher.domain.activity.CodeExecutionLimits;
import com.sqlteacher.domain.activity.CodeActivityArtifact;
import com.sqlteacher.domain.activity.CodeLanguage;
import com.sqlteacher.domain.activity.LabActivityArtifact;
import com.sqlteacher.domain.activity.ProjectActivityArtifact;
import com.sqlteacher.domain.activity.QuizActivityArtifact;
import com.sqlteacher.domain.activity.ReadingActivityArtifact;
import com.sqlteacher.domain.activity.SimulationActivityArtifact;
import com.sqlteacher.domain.activity.SqlActivityArtifact;
import com.sqlteacher.domain.activity.TraceActivityArtifact;
import com.sqlteacher.infrastructure.cloud.InMemoryLearningEventOwnerContext;
import com.sqlteacher.infrastructure.spring.SqlTeacherApplicationConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class DefaultLocalAppApi implements LocalAppApi {
    public static final String CONTRACT_VERSION = LocalAppContract.VERSION;

    private final ObjectMapper mapper;
    private final Map<String, SqlConfirmation> sqlConfirmations = new ConcurrentHashMap<>();
    private final Map<String, CachedSqlResult> sqlResults = new ConcurrentHashMap<>();
    private volatile AnnotationConfigApplicationContext context;

    public DefaultLocalAppApi(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public JsonNode invoke(String method, JsonNode params, CancellationToken cancellation,
                           Consumer<LocalAppEvent> events) throws Exception {
        if (!LocalAppContract.API_METHODS.contains(method)) {
            throw new IllegalArgumentException("Unknown local application method: " + method);
        }
        return switch (method) {
            case "system.health" -> health();
            case "session.current" -> currentSession();
            case "home.summary" -> homeSummary(cancellation);
            case "home.action.dismiss" -> homeActionDismiss(params, cancellation);
            case "course.workspace" -> courseWorkspace(cancellation);
            case "activity.definition" -> activityDefinition(params, cancellation);
            case "activity.submit" -> activitySubmit(params, cancellation);
            case "knowledge.article" -> knowledgeArticle(params, cancellation);
            case "knowledge.search" -> knowledgeSearch(params, cancellation);
            case "knowledge.read.mark" -> knowledgeReadMark(params, cancellation);
            case "knowledge.index.status" -> knowledgeIndexStatus(cancellation);
            case "knowledge.index.rebuild" -> knowledgeIndexRebuild(cancellation);
            case "knowledge.article.import" -> knowledgeArticleImport(params, cancellation);
            case "knowledge.article.revise" -> knowledgeArticleRevise(params, cancellation);
            case "knowledge.article.visibility" -> knowledgeArticleVisibility(params, cancellation);
            case "knowledge.article.delete" -> knowledgeArticleDelete(params, cancellation);
            case "knowledge.import.preview" -> knowledgeImportPreview(params, cancellation);
            case "knowledge.import.execute" -> knowledgeImportExecute(params, cancellation, events);
            case "practice.catalog" -> practiceCatalog(cancellation);
            case "practice.preview" -> practicePreview(params, cancellation);
            case "practice.start" -> practiceStart(params, cancellation);
            case "practice.run" -> practiceAttempt(params, cancellation, false);
            case "practice.submit" -> practiceAttempt(params, cancellation, true);
            case "practice.hint" -> practiceHint(params, cancellation);
            case "practice.reset" -> practiceReset(params, cancellation);
            case "practice.close" -> practiceClose(params, cancellation);
            case "runner.capabilities" -> runnerCapabilities(cancellation);
            case "runner.run" -> runnerRun(params, cancellation, events);
            case "data.connections" -> dataConnections(cancellation);
            case "data.connection.save" -> dataConnectionSave(params, cancellation);
            case "data.connection.test" -> dataConnectionTest(params, cancellation);
            case "data.connection.select" -> dataConnectionSelect(params, cancellation);
            case "data.connection.delete" -> dataConnectionDelete(params, cancellation);
            case "data.schema" -> dataSchema(params, cancellation);
            case "sql.analyze" -> sqlAnalyze(params, cancellation);
            case "sql.execute" -> sqlExecute(params, cancellation);
            case "sql.result.page" -> sqlResultPage(params, cancellation);
            case "ai.knowledge.ask" -> aiKnowledgeAsk(params, cancellation, events);
            case "ai.sql.preview" -> aiSqlPreview(params, cancellation);
            case "ai.sql.generate" -> aiSqlGenerate(params, cancellation, events);
            case "account.login" -> accountLogin(params, cancellation);
            case "account.register" -> accountRegister(params, cancellation);
            case "account.logout" -> accountLogout(cancellation);
            case "account.password.change" -> accountPasswordChange(params, cancellation);
            case "account.password.reset.request" -> accountPasswordResetRequest(params, cancellation);
            case "account.sessions" -> accountSessions(cancellation);
            case "account.session.revoke" -> accountSessionRevoke(params, cancellation);
            case "account.export.request" -> accountExportRequest(cancellation);
            case "account.export.get" -> accountExportGet(params, cancellation);
            case "account.deletion.request" -> accountDeletionRequest(cancellation);
            case "account.deletion.cancel" -> accountDeletionCancel(cancellation);
            case "account.deletion.status" -> accountDeletionStatus(cancellation);
            case "teaching.workspace" -> teachingWorkspace(cancellation);
            case "teaching.exercise.toggle" -> teachingExerciseToggle(params, cancellation);
            case "teaching.exercise.detail" -> teachingExerciseDetail(params, cancellation);
            case "teaching.exercise.save" -> teachingExerciseSave(params, cancellation);
            case "teaching.exercise.copy" -> teachingExerciseCopy(params, cancellation);
            case "teaching.exercise.import" -> teachingExerciseImport(params, cancellation);
            case "teaching.exercise.parse" -> teachingExerciseParse(params, cancellation);
            case "teaching.exercise.draft" -> teachingExerciseDraft(params, cancellation);
            case "teaching.exercise.export" -> teachingExerciseExport(params, cancellation);
            case "teaching.analytics" -> teachingAnalytics(cancellation);
            case "teaching.interventions" -> teachingInterventions(cancellation);
            case "teaching.intervention.update" -> teachingInterventionUpdate(params, cancellation);
            case "cloud.workspace" -> cloudWorkspace(params, cancellation);
            case "cloud.sync" -> cloudSync(cancellation, events);
            case "cloud.class.create" -> cloudClassCreate(params, cancellation);
            case "cloud.class.member.add" -> cloudClassMemberAdd(params, cancellation);
            case "cloud.assignments" -> cloudAssignments(params, cancellation);
            case "cloud.assignment.create" -> cloudAssignmentCreate(params, cancellation);
            case "cloud.assignment.update" -> cloudAssignmentUpdate(params, cancellation);
            case "cloud.assignment.copy" -> cloudAssignmentCopy(params, cancellation);
            case "cloud.assignment.status" -> cloudAssignmentStatus(params, cancellation);
            case "cloud.class.analytics" -> cloudClassAnalytics(params, cancellation);
            case "cloud.class.analytics.export" -> cloudClassAnalyticsExport(params, cancellation);
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
            case "learning.portfolio" -> learningPortfolio(cancellation);
            case "learning.portfolio.export" -> learningPortfolioExport(params, cancellation);
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
            case "settings.workspace" -> settingsWorkspace(cancellation);
            case "settings.preferences" -> settingsPreferences(cancellation);
            case "settings.environment" -> settingsEnvironment(cancellation);
            case "settings.storage" -> settingsStorage(cancellation);
            case "settings.update" -> settingsUpdate(params, cancellation);
            case "settings.component.install" -> settingsComponentInstall(params, cancellation, events);
            case "settings.component.cancel" -> settingsComponentCancel(params);
            case "settings.backups" -> settingsBackups(cancellation);
            case "settings.backup.create" -> settingsBackupCreate(cancellation);
            case "settings.backup.restore" -> settingsBackupRestore(params, cancellation);
            case "settings.demo.restore" -> settingsDemoRestore(cancellation);
            case "settings.learning.reset" -> settingsLearningReset(params, cancellation);
            case "settings.cache.clear" -> settingsCacheClear(cancellation);
            case "settings.update.check" -> settingsUpdateCheck(cancellation);
            case "settings.notifications.read" -> settingsNotificationsRead(cancellation);
            case "settings.help" -> settingsHelp(params, cancellation);
            case "editor.languages" -> editorLanguages();
            default -> throw new IllegalStateException("Method whitelist and dispatcher are inconsistent");
        };
    }

    private ObjectNode health() {
        ObjectNode result = mapper.createObjectNode();
        result.put("status", "ready");
        result.put("contractVersion", CONTRACT_VERSION);
        result.put("applicationVersion", ApplicationVersion.current());
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("javaVendor", System.getProperty("java.vendor"));
        result.put("coreInitialized", context != null);
        result.put("timestamp", Instant.now().toString());
        return result;
    }

    private ObjectNode currentSession() {
        DesktopAccessProfile profile = currentAccessProfile();
        ObjectNode result = mapper.createObjectNode();
        result.put("subjectId", profile.isGuest() ? "guest" : profile.userId());
        result.put("displayName", profile.displayName());
        result.put("role", webRole(profile));
        result.put("authenticated", !profile.isGuest());
        result.put("roleLabel", profile.roleLabel());
        ArrayNode permissions = result.putArray("permissions");
        profile.capabilities().stream().map(Enum::name).sorted().forEach(permissions::add);
        return result;
    }

    private JsonNode teachingWorkspace(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        DesktopAccessProfile profile = requireTeacher();
        var management = context().getBean(ExerciseManagementService.class);
        var progress = context().getBean(ExerciseProgressService.class);
        ObjectNode result = mapper.createObjectNode();
        result.put("role", webRole(profile));
        result.put("canPublish", true);
        result.set("exercises", mapper.valueToTree(management.listExercises(true)));
        result.set("progressOverview", mapper.valueToTree(progress.overview()));
        result.set("progressItems", mapper.valueToTree(progress.listExerciseProgress()));
        result.set("datasets", mapper.valueToTree(management.listDatasets()));
        result.put("authority", "java-and-cloud-server");
        return result;
    }

    private JsonNode accountLogin(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String email = requiredText(params, "email", 320);
        char[] password = requiredRawText(params, "password", 1_024).toCharArray();
        try {
            var core = context();
            var session = core.getBean(CloudApiClient.class).login(email, password);
            cancellation.throwIfCancelled();
            core.getBean(CloudSessionService.class).signIn(session);
            core.getBean(InMemoryLearningEventOwnerContext.class).useAuthenticatedUser(session.user().id());
            return currentSession();
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private JsonNode accountLogout(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var core = context();
        CloudSessionService sessions = core.getBean(CloudSessionService.class);
        var active = sessions.current();
        boolean remoteLogoutSucceeded = true;
        try {
            if (active.isPresent()) core.getBean(CloudApiClient.class).logout(active.get().accessToken());
        } catch (RuntimeException error) {
            remoteLogoutSucceeded = false;
        } finally {
            sessions.signOut();
            core.getBean(InMemoryLearningEventOwnerContext.class).useGuest();
        }
        ObjectNode result = currentSession();
        result.put("remoteLogoutSucceeded", remoteLogoutSucceeded);
        return result;
    }

    private JsonNode accountRegister(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        char[] password = requiredRawText(params, "password", 1_024).toCharArray();
        try {
            var core = context();
            var session = core.getBean(CloudApiClient.class).register(requiredText(params, "email", 320),
                requiredText(params, "displayName", 160), password);
            core.getBean(CloudSessionService.class).signIn(session);
            core.getBean(InMemoryLearningEventOwnerContext.class).useAuthenticatedUser(session.user().id());
            return currentSession();
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private JsonNode accountPasswordChange(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        char[] currentPassword = requiredRawText(params, "currentPassword", 1_024).toCharArray();
        char[] newPassword = requiredRawText(params, "newPassword", 1_024).toCharArray();
        try {
            var session = requireCloudSession();
            context().getBean(CloudApiClient.class).changePassword(session.accessToken(), currentPassword, newPassword);
            return mapper.createObjectNode().put("changed", true);
        } finally {
            Arrays.fill(currentPassword, '\0');
            Arrays.fill(newPassword, '\0');
        }
    }

    private JsonNode accountPasswordResetRequest(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        context().getBean(CloudApiClient.class).requestPasswordReset(requiredText(params, "email", 320));
        return mapper.createObjectNode().put("accepted", true);
    }

    private JsonNode accountSessions(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.createObjectNode().set("items", mapper.valueToTree(
            context().getBean(CloudApiClient.class).listSessions(session.accessToken())));
    }

    private JsonNode accountSessionRevoke(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        String sessionId = requiredText(params, "sessionId", 256);
        context().getBean(CloudApiClient.class).revokeSession(session.accessToken(), sessionId);
        return mapper.createObjectNode().put("revoked", true).put("sessionId", sessionId);
    }

    private JsonNode accountExportRequest(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudApiClient.class).requestAccountExport(session.accessToken()));
    }

    private JsonNode accountExportGet(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.createObjectNode().put("payload", context().getBean(CloudApiClient.class)
            .getAccountExport(session.accessToken(), requiredText(params, "taskId", 256)));
    }

    private JsonNode accountDeletionRequest(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudApiClient.class).requestAccountDeletion(session.accessToken()));
    }

    private JsonNode accountDeletionCancel(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudApiClient.class).cancelAccountDeletion(session.accessToken()));
    }

    private JsonNode accountDeletionStatus(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudApiClient.class).getAccountDeletionStatus(session.accessToken()));
    }

    private JsonNode teachingExerciseToggle(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        String exerciseId = requiredText(params, "exerciseId", 128);
        boolean enabled = params.path("enabled").asBoolean();
        int expectedVersion = params.path("expectedVersion").asInt(0);
        if (expectedVersion < 1) throw new IllegalArgumentException("expectedVersion must be positive");
        return mapper.valueToTree(context().getBean(ExerciseManagementService.class)
            .setEnabled(exerciseId, enabled, expectedVersion));
    }

    private JsonNode teachingExerciseDetail(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(ExerciseManagementService.class)
            .findDefinition(requiredText(params, "exerciseId", 128))
            .orElseThrow(() -> new IllegalArgumentException("Exercise does not exist")));
    }

    private JsonNode teachingExerciseSave(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        List<String> keywords = new java.util.ArrayList<>();
        params.path("requiredSqlKeywords").forEach(item -> keywords.add(item.asText()));
        List<String> hints = new java.util.ArrayList<>();
        params.path("hints").forEach(item -> { if (!item.asText().isBlank()) hints.add(item.asText()); });
        Integer expectedRows = params.path("expectedRowCount").isInt()
            ? params.path("expectedRowCount").asInt() : null;
        Integer expectedVersion = params.path("expectedVersion").isInt()
            ? params.path("expectedVersion").asInt() : null;
        ExerciseDraft draft = new ExerciseDraft(params.path("id").asText(""),
            requiredText(params, "title", 240), requiredText(params, "description", 8_000),
            requiredText(params, "knowledgePoint", 240),
            ExerciseDifficulty.valueOf(requiredText(params, "difficulty", 32)),
            requiredText(params, "datasetId", 128), requiredText(params, "referenceSql", 256 * 1024),
            new ExerciseEvaluationRule(params.path("compareColumns").asBoolean(true),
                params.path("compareRows").asBoolean(true), params.path("rowOrderMatters").asBoolean(false),
                expectedRows, keywords), hints, expectedVersion, params.path("enabled").asBoolean(true));
        return mapper.valueToTree(context().getBean(ExerciseManagementService.class).save(draft));
    }

    private JsonNode teachingExerciseCopy(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(ExerciseManagementService.class).copy(
            requiredText(params, "exerciseId", 128), requiredText(params, "title", 240)));
    }

    private JsonNode teachingExerciseImport(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(ExerciseManagementService.class)
            .importPackage(requiredText(params, "text", 1_000_000)));
    }

    private JsonNode teachingExerciseParse(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(ExerciseManagementService.class)
            .parsePackage(requiredText(params, "text", 1_000_000)));
    }

    private JsonNode teachingExerciseDraft(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(ExerciseTextDraftingService.class)
            .draft(requiredText(params, "text", 200_000)));
    }

    private JsonNode teachingExerciseExport(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        List<String> ids = new java.util.ArrayList<>();
        params.path("exerciseIds").forEach(item -> ids.add(item.asText()));
        if (ids.isEmpty()) throw new IllegalArgumentException("At least one exercise must be selected");
        return mapper.createObjectNode().put("text",
            context().getBean(ExerciseManagementService.class).exportPackage(ids));
    }

    private JsonNode teachingAnalytics(CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(LearningAnalyticsService.class).analyze(AnalyticsFilter.all()));
    }

    private JsonNode teachingInterventions(CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        return mapper.createObjectNode().set("items",
            mapper.valueToTree(context().getBean(InterventionService.class).refreshAuthorized()));
    }

    private JsonNode teachingInterventionUpdate(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        String id = requiredText(params, "candidateId", 128);
        InterventionStatus status = InterventionStatus.valueOf(requiredText(params, "status", 32));
        context().getBean(InterventionService.class).updateStatus(id, status);
        return mapper.createObjectNode().put("updated", true).put("candidateId", id).put("status", status.name());
    }

    private JsonNode cloudWorkspace(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var core = context();
        var sessions = core.getBean(CloudSessionService.class);
        var current = sessions.current();
        ObjectNode result = mapper.createObjectNode();
        var sync = core.getBean(CloudLearningSyncService.class).status();
        result.set("sync", mapper.valueToTree(sync));
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
            core.getBean(CloudApiClient.class).listClasses(current.get().accessToken())
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

    private JsonNode cloudClassCreate(JsonNode params, CancellationToken cancellation) {
        DesktopAccessProfile profile = requireTeacher();
        cancellation.throwIfCancelled();
        var core = context();
        var session = core.getBean(CloudSessionService.class).current()
            .orElseThrow(() -> new SecurityException("Class creation requires an authenticated session"));
        var classroom = core.getBean(CloudApiClient.class).createClass(
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
        return mapper.valueToTree(context().getBean(CloudApiClient.class).addClassMember(session.accessToken(),
            requiredText(params, "classroomId", 128), requiredText(params, "email", 320),
            UserRole.valueOf(requiredText(params, "role", 32))));
    }

    private JsonNode cloudAssignments(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.createObjectNode().set("items", mapper.valueToTree(context().getBean(CloudApiClient.class)
            .listAssignments(session.accessToken(), requiredText(params, "classroomId", 128))));
    }

    private JsonNode cloudAssignmentCreate(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        Instant dueAt = optionalInstant(params, "dueAt");
        return mapper.valueToTree(context().getBean(CloudApiClient.class).createAssignmentDraft(session.accessToken(),
            requiredText(params, "classroomId", 128), requiredText(params, "exerciseId", 128),
            requiredText(params, "title", 240), params.path("description").asText(""), dueAt));
    }

    private JsonNode cloudAssignmentUpdate(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudApiClient.class).updateAssignment(session.accessToken(),
            requiredText(params, "classroomId", 128), requiredText(params, "assignmentId", 128),
            requiredText(params, "title", 240), params.path("description").asText(""),
            optionalInstant(params, "dueAt"), params.path("expectedVersion").asLong()));
    }

    private JsonNode cloudAssignmentCopy(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudApiClient.class).copyAssignment(session.accessToken(),
            requiredText(params, "classroomId", 128), requiredText(params, "assignmentId", 128),
            params.path("expectedVersion").asLong()));
    }

    private JsonNode cloudAssignmentStatus(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudApiClient.class).changeAssignmentStatus(session.accessToken(),
            requiredText(params, "classroomId", 128), requiredText(params, "assignmentId", 128),
            AssignmentStatus.valueOf(requiredText(params, "status", 32)), params.path("expectedVersion").asLong()));
    }

    private JsonNode cloudClassAnalytics(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudApiClient.class).getClassLearningSummary(
            session.accessToken(), requiredText(params, "classroomId", 128)));
    }

    private JsonNode cloudClassAnalyticsExport(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.createObjectNode().put("csv", context().getBean(CloudApiClient.class).exportClassLearningCsv(
            session.accessToken(), requiredText(params, "classroomId", 128)));
    }

    private JsonNode cloudAssignmentAnalytics(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudApiClient.class).getAssignmentAnalytics(session.accessToken(),
            requiredText(params, "classroomId", 128), requiredText(params, "assignmentId", 128),
            assignmentAnalyticsFilter(params)));
    }

    private JsonNode cloudAssignmentAnalyticsExport(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.createObjectNode().put("csv", context().getBean(CloudApiClient.class).exportAssignmentAnalyticsCsv(
            session.accessToken(), requiredText(params, "classroomId", 128),
            requiredText(params, "assignmentId", 128), assignmentAnalyticsFilter(params)));
    }

    private static AssignmentAnalyticsFilter assignmentAnalyticsFilter(JsonNode params) {
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
        return mapper.valueToTree(context().getBean(CloudApiClient.class).getAssignmentContentSnapshot(
            session.accessToken(), requiredText(params, "classroomId", 128),
            requiredText(params, "assignmentId", 128)));
    }

    private JsonNode cloudAssignmentSubmit(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        requireCloudSession();
        var service = context().getBean(com.sqlteacher.application.collaboration.AssignmentDeliveryService.class);
        Instant completedAt = optionalInstant(params, "completedAt");
        var result = service.deliver(requiredText(params, "classroomId", 128),
            requiredText(params, "assignmentId", 128), params.path("passed").asBoolean(false),
            optionalErrorCode(params), completedAt == null ? Instant.now() : completedAt);
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
                items = context().getBean(CloudApiClient.class).listSubmissionFeedback(
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
        return mapper.valueToTree(context().getBean(CloudApiClient.class).saveSubmissionFeedback(
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
        return mapper.valueToTree(context().getBean(CloudApiClient.class).draftSubmissionFeedback(
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
                items = context().getBean(CloudApiClient.class).getKnowledgeMastery(
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
                items = context().getBean(CloudApiClient.class).listNotifications(
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
        var notification = context().getBean(CloudApiClient.class).markNotificationRead(
            session.accessToken(), requiredText(params, "notificationId", 128));
        var cache = context().getBean(com.sqlteacher.application.collaboration.TeachingContentCache.class);
        var items = cache.loadNotifications(session.user().id()).stream()
            .map(item -> item.id().equals(notification.id()) ? notification : item).toList();
        cache.saveNotifications(session.user().id(), items);
        return mapper.valueToTree(notification);
    }

    private JsonNode learningPortfolio(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return mapper.createObjectNode().set("items", mapper.valueToTree(context().getBean(
            com.sqlteacher.application.activity.ProjectPortfolioService.class).listOwnEntries()));
    }

    private JsonNode learningPortfolioExport(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        if (!params.path("confirmed").asBoolean(false)) {
            throw new SecurityException("Portfolio export requires explicit confirmation");
        }
        return mapper.createObjectNode().put("content", context().getBean(
            com.sqlteacher.application.activity.ProjectPortfolioService.class).exportOwnPortfolio(true));
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
                items = context().getBean(CloudApiClient.class).listCourses(session.accessToken());
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
        return mapper.valueToTree(context().getBean(CloudApiClient.class).createCourse(session.accessToken(),
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
                var api = context().getBean(CloudApiClient.class);
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
        return mapper.valueToTree(context().getBean(CloudApiClient.class).createCourseSection(
            session.accessToken(), requiredText(params, "courseId", 128), requiredText(params, "name", 120),
            Math.max(0, params.path("sortOrder").asInt(0))));
    }

    private JsonNode cloudCourseKnowledgeCreate(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudApiClient.class).createKnowledgePoint(
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
        return mapper.valueToTree(context().getBean(CloudApiClient.class).publishSharedExercise(
            session.accessToken(), requiredText(params, "courseId", 128),
            requiredText(params, "exerciseId", 128), requiredText(params, "title", 240),
            requiredText(params, "prompt", 16_384), requiredText(params, "datasetVersion", 128),
            requiredText(params, "evaluationRule", 16_384), knowledgePointIds, UUID.randomUUID().toString()));
    }

    private JsonNode cloudAssignmentCreateVersioned(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudApiClient.class).createAssignmentFromVersion(
            session.accessToken(), requiredText(params, "classroomId", 128),
            requiredText(params, "exerciseVersionId", 128), requiredText(params, "title", 240),
            params.path("description").asText(""), optionalInstant(params, "dueAt"), UUID.randomUUID().toString()));
    }

    private JsonNode cloudCourseExport(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.createObjectNode().put("content", context().getBean(CloudApiClient.class).exportCourseBundle(
            session.accessToken(), requiredText(params, "courseId", 128)));
    }

    private JsonNode cloudCourseImport(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudApiClient.class).importCourseBundle(session.accessToken(),
            requiredText(params, "content", 900_000), UUID.randomUUID().toString()));
    }

    private JsonNode cloudCoursePackagePreview(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudApiClient.class).previewCoursePackage(
            session.accessToken(), requiredText(params, "content", 900_000)));
    }

    private JsonNode cloudCoursePackageImport(JsonNode params, CancellationToken cancellation) {
        requireTeacher();
        cancellation.throwIfCancelled();
        if (!params.path("licenseConfirmed").asBoolean(false)) {
            throw new SecurityException("Course package license confirmation is required");
        }
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudApiClient.class).importCoursePackage(
            session.accessToken(), requiredText(params, "content", 900_000), UUID.randomUUID().toString(),
            requiredText(params, "expectedSha256", 64), true));
    }

    private static String optionalErrorCode(JsonNode params) {
        String value = params.path("errorCode").asText("").trim().toUpperCase();
        return value.isEmpty() ? null : value;
    }

    private com.sqlteacher.application.collaboration.CloudAuthenticationService.Session requireCloudSession() {
        return context().getBean(CloudSessionService.class).current()
            .orElseThrow(() -> new SecurityException("An authenticated cloud session is required"));
    }

    private static Instant optionalInstant(JsonNode params, String field) {
        String value = params.path(field).asText("").trim();
        return value.isEmpty() ? null : Instant.parse(value);
    }

    private JsonNode settingsWorkspace(CancellationToken cancellation) {
        ObjectNode result = settingsPreferences(cancellation);
        result.setAll(settingsEnvironment(cancellation));
        result.setAll(settingsStorage(cancellation));
        return result;
    }

    private ObjectNode settingsPreferences(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var core = context();
        DesktopAccessProfile profile = currentAccessProfile();
        GeneralSoftwareService general = core.getBean(GeneralSoftwareService.class);
        GeneralSoftwareSettings settings = general.settings();
        ObjectNode result = mapper.createObjectNode();
        result.put("role", webRole(profile));
        result.put("developerMode", core.getBean(SqlSafetyModeService.class).isDeveloperModeEnabled());
        result.put("canMaintainLocalData", profile.canConfigure(
            com.sqlteacher.application.collaboration.DesktopSettingPermission.LOCAL_DATA_MAINTENANCE));
        result.set("general", mapper.valueToTree(settings));
        result.set("notifications", mapper.valueToTree(general.notifications()));
        result.set("tasks", mapper.valueToTree(general.tasks()));
        result.set("helpTopics", mapper.valueToTree(general.helpTopics()));
        result.put("secretsExposed", false);
        return result;
    }

    private ObjectNode settingsEnvironment(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var core = context();
        ObjectNode result = mapper.createObjectNode();
        result.put("connectivity", core.getBean(GeneralSoftwareService.class).connectivitySummary());
        cancellation.throwIfCancelled();
        result.set("runnerCapabilities", mapper.valueToTree(core.getBean(LocalCodeRunner.class).capabilities()));
        cancellation.throwIfCancelled();
        result.set("components", mapper.valueToTree(core.getBean(ManagedComponentService.class).statuses()));
        result.put("manualPathPolicy", "java.home, JAVA_HOME, JDK_HOME, PATH and documented tool locations");
        return result;
    }

    private ObjectNode settingsStorage(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return mapper.createObjectNode().set("storage",
            mapper.valueToTree(context().getBean(GeneralSoftwareService.class).storage()));
    }

    private JsonNode settingsUpdate(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var core = context();
        GeneralSoftwareService service = core.getBean(GeneralSoftwareService.class);
        GeneralSoftwareSettings current = service.settings();
        boolean developerMode = params.path("developerMode").asBoolean(
            core.getBean(SqlSafetyModeService.class).isDeveloperModeEnabled());
        core.getBean(SqlSafetyModeService.class).setDeveloperModeEnabled(developerMode);
        String language = params.path("language").asText(current.language());
        boolean supportLogging = params.path("supportLogging").asBoolean(current.supportLogging());
        long supportLoggingExpiresAt = supportLogging
            ? (current.supportLogging() && current.supportLoggingExpiresAt() > System.currentTimeMillis()
                ? current.supportLoggingExpiresAt() : System.currentTimeMillis() + java.time.Duration.ofHours(24).toMillis())
            : 0;
        GeneralSoftwareSettings updated = new GeneralSoftwareSettings(
            current.formatVersion(),
            params.path("automaticUpdateChecks").asBoolean(current.automaticUpdateChecks()),
            current.skippedVersion(), GeneralSoftwareSettings.ProxyMode.valueOf(
                params.path("proxyMode").asText(current.proxyMode().name())),
            params.path("proxyHost").asText(current.proxyHost()),
            params.path("proxyPort").asInt(current.proxyPort()),
            params.path("reducedMotion").asBoolean(current.reducedMotion()),
            params.path("highContrast").asBoolean(current.highContrast()),
            supportLogging,
            supportLoggingExpiresAt,
            params.path("updateMirrorsEnabled").asBoolean(current.updateMirrorsEnabled()),
            language,
            params.path("nativeNotificationsEnabled").asBoolean(current.nativeNotificationsEnabled()),
            params.path("meteredNetwork").asBoolean(current.meteredNetwork()),
            params.path("theme").asText(current.theme()),
            params.path("font").asText(current.font()),
            params.path("density").asText(current.density()));
        service.saveSettings(updated);
        cancellation.throwIfCancelled();
        return mapper.createObjectNode().put("saved", true).put("developerMode", developerMode)
            .set("general", mapper.valueToTree(updated));
    }

    private JsonNode settingsComponentInstall(JsonNode params, CancellationToken cancellation,
                                              Consumer<LocalAppEvent> events) {
        ManagedComponentId id = ManagedComponentId.valueOf(requiredText(params, "componentId", 64));
        cancellation.throwIfCancelled();
        var status = context().getBean(ManagedComponentService.class).install(id, progress -> {
            cancellation.throwIfCancelled();
            ObjectNode payload = mapper.createObjectNode();
            payload.put("componentId", id.name());
            payload.put("fraction", progress.fraction());
            payload.put("message", progress.message());
            events.accept(new LocalAppEvent("progress", payload));
        });
        cancellation.throwIfCancelled();
        return mapper.valueToTree(status);
    }

    private JsonNode settingsComponentCancel(JsonNode params) {
        ManagedComponentId id = ManagedComponentId.valueOf(requiredText(params, "componentId", 64));
        context().getBean(ManagedComponentService.class).cancel(id);
        return mapper.createObjectNode().put("cancelled", true).put("componentId", id.name());
    }

    private JsonNode settingsBackups(CancellationToken cancellation) {
        requireLocalMaintenance();
        cancellation.throwIfCancelled();
        return mapper.createObjectNode().set("items",
            mapper.valueToTree(context().getBean(ApplicationBackupService.class).listBackups()));
    }

    private JsonNode settingsBackupCreate(CancellationToken cancellation) {
        requireLocalMaintenance();
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(ApplicationBackupService.class).createBackup());
    }

    private JsonNode settingsBackupRestore(JsonNode params, CancellationToken cancellation) {
        requireLocalMaintenance();
        cancellation.throwIfCancelled();
        String backupId = requiredText(params, "backupId", 512);
        context().getBean(ApplicationBackupService.class).restoreBackup(backupId);
        return mapper.createObjectNode().put("restored", true).put("backupId", backupId);
    }

    private JsonNode settingsDemoRestore(CancellationToken cancellation) {
        requireLocalMaintenance();
        cancellation.throwIfCancelled();
        context().getBean(ApplicationBackupService.class).restoreDemoDatabase();
        return mapper.createObjectNode().put("restored", true);
    }

    private JsonNode settingsLearningReset(JsonNode params, CancellationToken cancellation) {
        requireLocalMaintenance();
        if (!"RESET LEARNING DATA".equals(params.path("confirmation").asText())) {
            throw new IllegalArgumentException("Learning data reset requires the exact confirmation phrase");
        }
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(DataMaintenanceService.class).resetLearningData());
    }

    private JsonNode settingsCacheClear(CancellationToken cancellation) {
        requireLocalMaintenance();
        cancellation.throwIfCancelled();
        long bytes = context().getBean(GeneralSoftwareService.class).clearRebuildableFiles();
        return mapper.createObjectNode().put("clearedBytes", bytes);
    }

    private JsonNode settingsUpdateCheck(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(UpdateService.class).check(true));
    }

    private JsonNode settingsNotificationsRead(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        context().getBean(GeneralSoftwareService.class).markNotificationsRead();
        return mapper.createObjectNode().put("updated", true);
    }

    private JsonNode settingsHelp(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String topicId = requiredText(params, "topicId", 128);
        return mapper.createObjectNode().put("topicId", topicId)
            .put("content", context().getBean(GeneralSoftwareService.class).help(topicId));
    }

    private void requireLocalMaintenance() {
        if (!currentAccessProfile().canConfigure(
                com.sqlteacher.application.collaboration.DesktopSettingPermission.LOCAL_DATA_MAINTENANCE)) {
            throw new SecurityException("Local data maintenance is not allowed for the current role");
        }
    }

    private DesktopAccessProfile currentAccessProfile() {
        return context().getBean(CloudSessionService.class).current()
            .map(DesktopAccessProfile::from).orElseGet(DesktopAccessProfile::guest);
    }

    private DesktopAccessProfile requireTeacher() {
        DesktopAccessProfile profile = currentAccessProfile();
        if (profile.kind() != DesktopAccessProfile.Kind.TEACHER
                && profile.kind() != DesktopAccessProfile.Kind.ADMIN) {
            throw new SecurityException("Teaching workspace requires teacher or administrator role");
        }
        return profile;
    }

    private static String webRole(DesktopAccessProfile profile) {
        return switch (profile.kind()) {
            case ADMIN -> "ADMINISTRATOR";
            case TEACHER -> "TEACHER";
            case STUDENT, GUEST -> "STUDENT";
        };
    }

    private ObjectNode homeSummary(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        AnnotationConfigApplicationContext applicationContext = context();
        cancellation.throwIfCancelled();
        // The default home path is local-only. Cloud refresh and synchronization require an explicit
        // action in the cloud workspace and must never delay the deterministic offline dashboard.
        var dashboard = applicationContext.getBean(LearningDiagnosisService.class).refresh();
        ObjectNode result = mapper.createObjectNode();
        result.put("ownerId", dashboard.ownerId());
        result.put("policyVersion", dashboard.policyVersion());
        result.put("knowledgePointCount", dashboard.mastery().size());
        result.put("needsPracticeCount", dashboard.mastery().stream()
            .filter(item -> item.level() == MasteryLevel.NEEDS_PRACTICE).count());
        result.put("cloudAvailable", false);
        result.put("calculationMillis", dashboard.calculationTime().toMillis());
        ArrayNode actions = result.putArray("actions");
        dashboard.actions().forEach(item -> {
            ObjectNode action = actions.addObject();
            action.put("id", item.id());
            action.put("type", item.type().name());
            action.put("title", item.title());
            action.put("description", item.description());
            action.put("priority", item.priority());
            action.put("exerciseId", item.exerciseId());
            action.put("knowledgePoint", item.knowledgePoint());
            action.put("reason", item.reason().name());
        });
        return result;
    }

    private JsonNode homeActionDismiss(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String actionId = requiredText(params, "actionId", 128);
        context().getBean(LearningDiagnosisService.class).dismissAction(actionId);
        return mapper.createObjectNode().put("dismissed", true).put("actionId", actionId);
    }

    private JsonNode courseWorkspace(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var core = context();
        var result = mapper.createObjectNode();
        result.set("courses", mapper.valueToTree(core.getBean(CourseMapService.class).load().courses()));
        var knowledge = core.getBean(CourseKnowledgeService.class).listArticles();
        result.set("articles", mapper.valueToTree(knowledge));
        result.put("articleCount", knowledge.size());
        return result;
    }

    private JsonNode activityDefinition(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String activityId = requiredText(params, "activityId", 128);
        ActivityLearningService service = context().getBean(ActivityLearningService.class);
        var definition = service.loadDefinition(activityId);
        ObjectNode result = mapper.valueToTree(definition);
        ObjectNode safeSpecification = mapper.valueToTree(definition.specification());
        switch (definition.type()) {
            case QUIZ -> safeSpecification.path("questions").forEach(question -> {
                if (question instanceof ObjectNode object) {
                    object.remove("correctOptionId");
                    object.remove("explanation");
                }
            });
            case TRACE -> safeSpecification.remove("expectedNodeIds");
            case CODE -> safeSpecification.remove("tests");
            case READING -> safeSpecification.path("checks").forEach(check -> {
                if (check instanceof ObjectNode object) {
                    object.remove("expectedAnswer");
                    object.remove("explanation");
                }
            });
            case SIMULATION -> safeSpecification.remove("checkpoints");
            case SQL -> {
                safeSpecification.remove("referenceSql");
                safeSpecification.remove("evaluationRule");
            }
            case PROJECT, LAB -> { }
        }
        result.set("specification", safeSpecification);
        result.put("type", definition.type().name());
        result.put("nextSubmissionVersion", service.nextSubmissionVersion(activityId));
        service.latestFeedback(activityId).ifPresent(feedback -> result.set("latestFeedback", mapper.valueToTree(feedback)));
        return result;
    }

    private JsonNode activitySubmit(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String activityId = requiredText(params, "activityId", 128);
        String type = requiredText(params, "type", 32).toUpperCase();
        JsonNode artifact = params.path("artifact");
        var submitted = switch (type) {
            case "QUIZ" -> new QuizActivityArtifact(textMap(artifact.path("selectedOptionIds"), 100, 256));
            case "TRACE" -> new TraceActivityArtifact(textList(artifact.path("visitedNodeIds"), 256, 128));
            case "SIMULATION" -> new SimulationActivityArtifact(textList(artifact.path("actionIds"), 256, 128));
            case "CODE" -> new CodeActivityArtifact(
                CodeLanguage.valueOf(requiredText(artifact, "language", 16).toUpperCase()),
                requiredText(artifact, "sourceCode", 256 * 1024));
            case "PROJECT" -> new ProjectActivityArtifact(
                artifact.path("submissionVersion").asInt(1),
                textList(artifact.path("completedMilestoneIds"), 20, 128),
                artifact.path("evidenceSummary").asText(""), artifact.path("reflection").asText(""));
            case "LAB" -> new LabActivityArtifact(
                textList(artifact.path("completedStepIds"), 50, 128),
                textMap(artifact.path("observations"), 50, 4_000), artifact.path("conclusion").asText(""));
            case "READING" -> new ReadingActivityArtifact(
                artifact.path("readToEnd").asBoolean(false), textMap(artifact.path("answers"), 30, 2_000));
            case "SQL" -> new SqlActivityArtifact(requiredText(artifact, "submittedSql", 256 * 1024));
            default -> throw new IllegalArgumentException("Unsupported activity type: " + type);
        };
        var submission = context().getBean(ActivityLearningService.class)
            .submit(activityId, submitted, cancellation::cancelled);
        cancellation.throwIfCancelled();
        return mapper.valueToTree(submission);
    }

    private JsonNode knowledgeArticle(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var detail = context().getBean(CourseKnowledgeService.class)
            .getArticle(requiredText(params, "articleId", 128));
        ObjectNode result = mapper.createObjectNode();
        result.set("article", mapper.valueToTree(detail.article()));
        result.put("markdown", detail.revision().content());
        result.put("sourceName", detail.revision().sourceName());
        result.put("revision", detail.revision().revision());
        result.put("trustedHtml", false);
        result.put("externalResourcesAllowed", false);
        return result;
    }

    private JsonNode knowledgeSearch(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String query = requiredText(params, "query", 500);
        int limit = Math.clamp(params.path("limit").asInt(30), 1, 100);
        CourseKnowledgeService service = context().getBean(CourseKnowledgeService.class);
        Map<String, String> articleIds = service.listArticles().stream()
            .collect(java.util.stream.Collectors.toMap(item -> item.documentId(), item -> item.id(), (left, right) -> left));
        ArrayNode items = mapper.createArrayNode();
        service.search(query, CourseKnowledgeSearchFilter.allLocal(), limit).forEach(item -> {
            ObjectNode result = items.addObject();
            result.put("articleId", articleIds.getOrDefault(item.documentId(), ""));
            result.put("documentId", item.documentId());
            result.put("title", item.title());
            result.put("sourceName", item.sourceName());
            result.put("chunkIndex", item.chunkIndex());
            result.put("snippet", item.snippet());
            result.put("relevance", item.relevance());
        });
        return mapper.createObjectNode().set("items", items);
    }

    private JsonNode knowledgeReadMark(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(KnowledgeReadStateService.class).save(
            requiredText(params, "articleId", 128), Math.max(1, params.path("revision").asInt(1)),
            Math.clamp(params.path("progressPercent").asInt(100), 0, 100)));
    }

    private JsonNode knowledgeIndexStatus(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(KnowledgeIndexService.class).status());
    }

    private JsonNode knowledgeIndexRebuild(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        requireTeacher();
        return mapper.valueToTree(context().getBean(KnowledgeIndexService.class).rebuildAll());
    }

    private JsonNode knowledgeArticleImport(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        requireTeacher();
        var article = context().getBean(CourseKnowledgeService.class).importArticle(
            Path.of(requiredText(params, "path", 32_768)), requiredText(params, "courseTitle", 240),
            requiredText(params, "sectionTitle", 240), textList(params.path("knowledgePoints"), 100, 240));
        context().getBean(KnowledgeIndexService.class).rebuildPending();
        return mapper.valueToTree(article);
    }

    private JsonNode knowledgeArticleRevise(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        requireTeacher();
        var article = context().getBean(CourseKnowledgeService.class).reviseArticle(
            requiredText(params, "articleId", 128), Path.of(requiredText(params, "path", 32_768)),
            textList(params.path("knowledgePoints"), 100, 240));
        context().getBean(KnowledgeIndexService.class).rebuildPending();
        return mapper.valueToTree(article);
    }

    private JsonNode knowledgeArticleVisibility(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        requireTeacher();
        var article = context().getBean(CourseKnowledgeService.class).changeVisibility(
            requiredText(params, "articleId", 128), KnowledgeVisibility.valueOf(requiredText(params, "visibility", 32)));
        context().getBean(KnowledgeIndexService.class).rebuildPending();
        return mapper.valueToTree(article);
    }

    private JsonNode knowledgeArticleDelete(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        requireTeacher();
        String articleId = requiredText(params, "articleId", 128);
        var service = context().getBean(CourseKnowledgeService.class);
        var article = service.listArticles().stream().filter(item -> item.id().equals(articleId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Knowledge article was not found"));
        context().getBean(KnowledgeDocumentService.class).deleteDocument(article.documentId());
        return mapper.createObjectNode().put("deleted", true).put("articleId", articleId);
    }

    private JsonNode knowledgeImportPreview(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String root = requiredText(params, "root", 32_768);
        var mapping = new ObsidianVaultImportService.ImportMapping(
            params.path("courseTitle").asText("Obsidian 知识库"),
            params.path("sectionDepth").asInt(1),
            params.path("includeAttachments").asBoolean(true));
        var preview = context().getBean(ObsidianVaultImportService.class).preview(Path.of(root), mapping);
        return mapper.valueToTree(preview);
    }

    private JsonNode knowledgeImportExecute(JsonNode params, CancellationToken cancellation,
                                            Consumer<LocalAppEvent> events) {
        cancellation.throwIfCancelled();
        emit(events, "import.progress", "phase", "verifying");
        var report = context().getBean(ObsidianVaultImportService.class)
            .execute(requiredText(params, "previewToken", 128));
        cancellation.throwIfCancelled();
        emit(events, "import.progress", "phase", "completed");
        return mapper.valueToTree(report);
    }

    private JsonNode practiceCatalog(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var items = context().getBean(ExerciseCatalogService.class).listAvailableExercises();
        return mapper.createObjectNode().set("items", mapper.valueToTree(items));
    }

    private JsonNode practicePreview(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var exercise = context().getBean(ExerciseCatalogService.class)
            .findAvailableExercise(requiredText(params, "exerciseId", 128))
            .orElseThrow(() -> new IllegalArgumentException("Exercise is not available"));
        return mapper.valueToTree(exercise);
    }

    private JsonNode practiceStart(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(ExercisePracticeService.class)
            .start(requiredText(params, "exerciseId", 128)));
    }

    private JsonNode practiceAttempt(JsonNode params, CancellationToken cancellation, boolean submit) {
        cancellation.throwIfCancelled();
        String sessionId = requiredText(params, "sessionId", 128);
        String sql = requiredText(params, "answer", 256 * 1024);
        ExerciseAttemptResult attempt = submit
            ? context().getBean(ExercisePracticeService.class).submit(sessionId, sql)
            : context().getBean(ExercisePracticeService.class).run(sessionId, sql);
        cancellation.throwIfCancelled();
        return mapper.valueToTree(attempt);
    }

    private JsonNode practiceHint(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(ExercisePracticeService.class)
            .requestHint(requiredText(params, "sessionId", 128)));
    }

    private JsonNode practiceReset(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(ExercisePracticeService.class)
            .reset(requiredText(params, "sessionId", 128)));
    }

    private JsonNode practiceClose(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String sessionId = requiredText(params, "sessionId", 128);
        context().getBean(ExercisePracticeService.class).close(sessionId);
        return mapper.createObjectNode().put("closed", true).put("sessionId", sessionId);
    }

    private JsonNode runnerCapabilities(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return mapper.createObjectNode().set("items",
            mapper.valueToTree(context().getBean(LocalCodeRunner.class).capabilities()));
    }

    private JsonNode runnerRun(JsonNode params, CancellationToken cancellation,
                               Consumer<LocalAppEvent> events) {
        CodeLanguage language;
        try {
            language = CodeLanguage.valueOf(requiredText(params, "language", 16).toUpperCase());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unsupported code language");
        }
        String source = requiredText(params, "sourceCode", 256 * 1024);
        String input = params.path("standardInput").asText("");
        if (input.length() > 64 * 1024) throw new IllegalArgumentException("standardInput exceeds 64 KiB");
        emit(events, "runner.progress", "phase", "starting");
        var result = context().getBean(LocalCodeRunner.class).run(
            new CodeRunRequest(language, source, input, CodeExecutionLimits.defaults()),
            cancellation::cancelled);
        cancellation.throwIfCancelled();
        emit(events, "runner.progress", "phase", "completed");
        return mapper.valueToTree(result);
    }

    private JsonNode dataConnections(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        ConnectionManagementService service = context().getBean(ConnectionManagementService.class);
        String current = service.currentProfile().map(profile -> profile.id()).orElse("");
        ArrayNode items = mapper.createArrayNode();
        service.listProfiles().forEach(profile -> {
            ObjectNode item = items.addObject();
            item.put("id", profile.id());
            item.put("displayName", profile.displayName());
            item.put("dialect", profile.dialect().name());
            item.put("readOnly", profile.readOnly());
            item.put("enabled", profile.enabled());
            item.put("builtIn", profile.builtIn());
            item.put("selected", profile.id().equals(current));
            appendConnectionTarget(item, profile.target());
        });
        return mapper.createObjectNode().set("items", items);
    }

    private JsonNode dataConnectionSave(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        DatabaseConnectionProfile profile = connectionProfile(params);
        return mapper.valueToTree(context().getBean(ConnectionManagementService.class).saveProfile(profile));
    }

    private JsonNode dataConnectionTest(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        DatabaseConnectionProfile profile = connectionProfile(params);
        char[] password = params.path("password").asText("").toCharArray();
        try {
            var core = context();
            var result = core.getBean(DatabaseConnectionTestService.class).testConnection(profile, password);
            if (result.successful() && !profile.dialect().fileBased()) {
                core.getBean(DatabaseCredentialSession.class).remember(profile.id(), password);
            }
            cancellation.throwIfCancelled();
            return mapper.valueToTree(result);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private JsonNode dataConnectionSelect(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(ConnectionManagementService.class)
            .selectProfile(requiredText(params, "connectionId", 64)));
    }

    private JsonNode dataConnectionDelete(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String connectionId = requiredText(params, "connectionId", 64);
        var core = context();
        core.getBean(DatabaseCredentialSession.class).forget(connectionId);
        core.getBean(ConnectionManagementService.class).removeProfile(connectionId);
        return mapper.createObjectNode().put("deleted", true).put("connectionId", connectionId);
    }

    private DatabaseConnectionProfile connectionProfile(JsonNode params) {
        String id = requiredText(params, "id", 64);
        String displayName = requiredText(params, "displayName", 160);
        DatabaseDialect dialect = DatabaseDialect.valueOf(requiredText(params, "dialect", 32));
        DatabaseConnectionTarget target;
        if (dialect == DatabaseDialect.SQLITE) {
            target = new SqliteConnectionTarget(Path.of(requiredText(params, "databasePath", 4096)));
        } else if (dialect.fileBased()) {
            target = new FileDatabaseConnectionTarget(dialect,
                Path.of(requiredText(params, "databasePath", 4096)));
        } else if (dialect.generic()) {
            target = new GenericJdbcConnectionTarget(requiredText(params, "jdbcUrl", 4096),
                requiredText(params, "driverClass", 512),
                Path.of(requiredText(params, "driverJar", 4096)), params.path("username").asText(""));
        } else {
            target = new ServerConnectionTarget(dialect, requiredText(params, "host", 512),
                params.path("port").asInt(dialect.defaultPort()), requiredText(params, "databaseName", 512),
                requiredText(params, "username", 512));
        }
        return new DatabaseConnectionProfile(id, displayName, target,
            params.path("readOnly").asBoolean(), params.path("enabled").asBoolean(true), false);
    }

    private void appendConnectionTarget(ObjectNode item, DatabaseConnectionTarget target) {
        if (target instanceof SqliteConnectionTarget sqlite) {
            item.put("databasePath", sqlite.databasePath().toString());
        } else if (target instanceof FileDatabaseConnectionTarget file) {
            item.put("databasePath", file.databasePath().toString());
        } else if (target instanceof ServerConnectionTarget server) {
            item.put("host", server.host());
            item.put("port", server.port());
            item.put("databaseName", server.databaseName());
            item.put("username", server.username());
        } else if (target instanceof GenericJdbcConnectionTarget generic) {
            item.put("jdbcUrl", generic.jdbcUrl());
            item.put("driverClass", generic.driverClass());
            item.put("driverJar", generic.driverJar().toString());
            item.put("username", generic.username());
        }
    }

    private JsonNode dataSchema(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String connectionId = requiredText(params, "connectionId", 64);
        var tables = context().getBean(DatabaseMetadataService.class).listTables(connectionId);
        return mapper.createObjectNode().set("tables", mapper.valueToTree(tables));
    }

    private JsonNode sqlAnalyze(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        expireSqlState();
        String connectionId = requiredText(params, "connectionId", 64);
        String sql = requiredText(params, "sql", 256 * 1024);
        ConnectionManagementService connections = context().getBean(ConnectionManagementService.class);
        var profile = connections.findProfile(connectionId)
            .orElseThrow(() -> new IllegalArgumentException("Database connection does not exist"));
        SqlRiskAnalysis risk = context().getBean(SqlRiskAnalysisService.class).analyze(sql, profile.dialect());
        ObjectNode result = mapper.valueToTree(risk);
        if (risk.executable() && risk.confirmationRequired()) {
            String token = UUID.randomUUID().toString();
            sqlConfirmations.put(token, new SqlConfirmation(connectionId, hash(sql), Instant.now().plusSeconds(300)));
            result.put("confirmationToken", token);
            result.put("confirmationExpiresAt", Instant.now().plusSeconds(300).toString());
        }
        result.put("enforcedBy", "java");
        result.put("maxRows", 500);
        result.put("timeoutSeconds", 10);
        return result;
    }

    private JsonNode sqlExecute(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        expireSqlState();
        String connectionId = requiredText(params, "connectionId", 64);
        String sql = requiredText(params, "sql", 256 * 1024);
        String confirmationToken = params.path("confirmationToken").asText("").trim();
        ConnectionManagementService connections = context().getBean(ConnectionManagementService.class);
        var profile = connections.findProfile(connectionId)
            .orElseThrow(() -> new IllegalArgumentException("Database connection does not exist"));
        SqlRiskAnalysis risk = context().getBean(SqlRiskAnalysisService.class).analyze(sql, profile.dialect());
        if (!risk.executable()) throw new IllegalArgumentException("SQL is blocked by Java risk analysis");
        boolean confirmed = false;
        if (risk.confirmationRequired()) {
            SqlConfirmation confirmation = sqlConfirmations.remove(confirmationToken);
            confirmed = confirmation != null && confirmation.expiresAt().isAfter(Instant.now())
                && confirmation.connectionId().equals(connectionId) && confirmation.sqlHash().equals(hash(sql));
            if (!confirmed) throw new IllegalArgumentException("A current confirmation token is required");
        }
        int maxRows = Math.clamp(params.path("maxRows").asInt(500), 1, 500);
        SqlExecutionResult execution = context().getBean(SqlExecutionService.class).execute(
            new SqlExecutionRequest(connectionId, sql, maxRows, Duration.ofSeconds(10), confirmed));
        cancellation.throwIfCancelled();
        String resultId = UUID.randomUUID().toString();
        rememberSqlResult(resultId, new CachedSqlResult(execution, Instant.now().plusSeconds(600)));
        return sqlPage(resultId, execution, 0, Math.clamp(params.path("pageSize").asInt(50), 1, 100));
    }

    /** 结果缓存有上限：过期清理后仍满时移除最早的条目，避免长会话内存无界增长。 */
    private void rememberSqlResult(String resultId, CachedSqlResult cached) {
        if (sqlResults.size() >= 64) {
            expireSqlState();
            if (sqlResults.size() >= 64) {
                sqlResults.keySet().stream().findFirst().ifPresent(sqlResults::remove);
            }
        }
        sqlResults.put(resultId, cached);
    }

    private JsonNode sqlResultPage(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        expireSqlState();
        String resultId = requiredText(params, "resultId", 128);
        CachedSqlResult cached = sqlResults.get(resultId);
        if (cached == null) throw new IllegalArgumentException("SQL result page has expired");
        return sqlPage(resultId, cached.result(), Math.max(0, params.path("page").asInt(0)),
            Math.clamp(params.path("pageSize").asInt(50), 1, 100));
    }

    private ObjectNode sqlPage(String resultId, SqlExecutionResult result, int page, int pageSize) {
        int from = Math.min(page * pageSize, result.rows().size());
        int to = Math.min(from + pageSize, result.rows().size());
        ObjectNode response = mapper.createObjectNode();
        response.put("resultId", resultId);
        response.put("success", result.success());
        response.set("columns", mapper.valueToTree(result.columns()));
        response.set("rows", mapper.valueToTree(result.rows().subList(from, to)));
        response.put("page", page);
        response.put("pageSize", pageSize);
        response.put("totalRows", result.rows().size());
        response.put("hasMore", to < result.rows().size());
        response.put("affectedRows", result.affectedRows());
        response.put("truncated", result.truncated());
        response.put("message", result.message());
        response.put("durationMillis", result.duration().toMillis());
        response.put("auditRecorded", true);
        return response;
    }

    private JsonNode aiKnowledgeAsk(JsonNode params, CancellationToken cancellation,
                                    Consumer<LocalAppEvent> events) {
        cancellation.throwIfCancelled();
        String question = requiredText(params, "question", 2_000);
        var answer = context().getBean(GroundedKnowledgeExplanationService.class)
            .explain(question, CourseKnowledgeSearchFilter.allLocal());
        cancellation.throwIfCancelled();
        String content = answer.answer();
        for (int offset = 0; offset < content.length(); offset += 240) {
            cancellation.throwIfCancelled();
            ObjectNode payload = mapper.createObjectNode();
            payload.put("delta", content.substring(offset, Math.min(offset + 240, content.length())));
            events.accept(new LocalAppEvent("ai.delta", payload));
        }
        return mapper.valueToTree(answer);
    }

    private JsonNode aiSqlPreview(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        Nl2SqlRequest request = nl2SqlRequest(params);
        return mapper.valueToTree(context().getBean(Nl2SqlSafetyService.class).preview(request));
    }

    private JsonNode aiSqlGenerate(JsonNode params, CancellationToken cancellation,
                                   Consumer<LocalAppEvent> events) {
        cancellation.throwIfCancelled();
        emit(events, "ai.delta", "delta", "正在生成并执行 Java 安全评估…");
        var result = context().getBean(Nl2SqlSafetyService.class).generateAndAssess(nl2SqlRequest(params));
        cancellation.throwIfCancelled();
        return mapper.valueToTree(result);
    }

    private Nl2SqlRequest nl2SqlRequest(JsonNode params) {
        String connectionId = requiredText(params, "connectionId", 64);
        var profile = context().getBean(ConnectionManagementService.class).findProfile(connectionId)
            .orElseThrow(() -> new IllegalArgumentException("Database connection was not found"));
        if (!profile.enabled()) throw new IllegalArgumentException("Database connection is disabled");
        return new Nl2SqlRequest(requiredText(params, "question", 2_000), profile.id(), profile.dialect());
    }

    private static String requiredText(JsonNode params, String field, int maxLength) {
        String value = params.path(field).asText("").trim();
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain at most " + maxLength + " characters");
        }
        return value;
    }

    private static List<String> textList(JsonNode node, int maximumItems, int maximumLength) {
        if (!node.isArray() || node.size() > maximumItems) {
            throw new IllegalArgumentException("Expected a bounded text list");
        }
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        node.forEach(item -> {
            String value = item.asText("").trim();
            if (value.isEmpty() || value.length() > maximumLength) {
                throw new IllegalArgumentException("Text list contains an invalid value");
            }
            result.add(value);
        });
        return List.copyOf(result);
    }

    private static Map<String, String> textMap(JsonNode node, int maximumItems, int maximumValueLength) {
        if (!node.isObject() || node.size() > maximumItems) {
            throw new IllegalArgumentException("Expected a bounded text map");
        }
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey().trim();
            String value = entry.getValue().asText("").trim();
            if (key.isEmpty() || key.length() > 128 || value.length() > maximumValueLength) {
                throw new IllegalArgumentException("Text map contains an invalid value");
            }
            result.put(key, value);
        });
        return Map.copyOf(result);
    }

    private static String requiredRawText(JsonNode params, String field, int maxLength) {
        String value = params.path(field).asText("");
        if (value.isEmpty() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain at most " + maxLength + " characters");
        }
        return value;
    }

    private void emit(Consumer<LocalAppEvent> events, String type, String field, String value) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put(field, value);
        events.accept(new LocalAppEvent(type, payload));
    }

    private void expireSqlState() {
        Instant now = Instant.now();
        sqlConfirmations.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        sqlResults.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private ObjectNode editorLanguages() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode languages = result.putArray("languages");
        languages.addObject().put("id", "sql").put("label", "SQL").put("completionSource", "deterministic-catalog");
        languages.addObject().put("id", "java").put("label", "Java").put("completionSource", "monaco-defaults");
        result.put("maxModelBytes", 1_048_576);
        return result;
    }

    private synchronized AnnotationConfigApplicationContext context() {
        if (context != null) return context;
        AnnotationConfigApplicationContext created = new AnnotationConfigApplicationContext(SqlTeacherApplicationConfig.class);
        try {
            created.getBean(DatabaseInitializationService.class).initialize();
            var owners = created.getBean(InMemoryLearningEventOwnerContext.class);
            created.getBean(CloudSessionService.class).current()
                .ifPresentOrElse(session -> owners.useAuthenticatedUser(session.user().id()), owners::useGuest);
            context = created;
            return created;
        } catch (RuntimeException error) {
            created.close();
            throw error;
        }
    }

    @Override
    public synchronized void close() {
        sqlConfirmations.clear();
        sqlResults.clear();
        if (context != null) {
            context.close();
            context = null;
        }
    }

    private record SqlConfirmation(String connectionId, String sqlHash, Instant expiresAt) { }
    private record CachedSqlResult(SqlExecutionResult result, Instant expiresAt) { }
}
