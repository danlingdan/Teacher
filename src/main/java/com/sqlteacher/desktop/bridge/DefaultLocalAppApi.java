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
import com.sqlteacher.application.component.ManagedComponentService;
import com.sqlteacher.application.database.DatabaseInitializationService;
import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.course.CourseMapService;
import com.sqlteacher.application.execution.SqlExecutionRequest;
import com.sqlteacher.application.execution.SqlExecutionResult;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.application.exercise.ExerciseAttemptResult;
import com.sqlteacher.application.exercise.ExerciseCatalogService;
import com.sqlteacher.application.exercise.ExerciseManagementService;
import com.sqlteacher.application.exercise.ExercisePracticeService;
import com.sqlteacher.application.exercise.ExerciseProgressService;
import com.sqlteacher.application.knowledge.CourseKnowledgeSearchFilter;
import com.sqlteacher.application.knowledge.CourseKnowledgeService;
import com.sqlteacher.application.knowledge.GroundedKnowledgeExplanationService;
import com.sqlteacher.application.knowledge.ObsidianVaultImportService;
import com.sqlteacher.application.learning.MasteryLevel;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.application.risk.SqlSafetyModeService;
import com.sqlteacher.application.runner.CodeRunRequest;
import com.sqlteacher.application.runner.LocalCodeRunner;
import com.sqlteacher.application.learning.StudentLearningQueueService;
import com.sqlteacher.application.system.GeneralSoftwareService;
import com.sqlteacher.application.system.GeneralSoftwareSettings;
import com.sqlteacher.domain.activity.CodeExecutionLimits;
import com.sqlteacher.domain.activity.CodeLanguage;
import com.sqlteacher.infrastructure.cloud.InMemoryLearningEventOwnerContext;
import com.sqlteacher.infrastructure.spring.SqlTeacherApplicationConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;
import java.io.InputStream;
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
    private AnnotationConfigApplicationContext context;

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
            case "knowledge.sample" -> knowledgeSample();
            case "course.workspace" -> courseWorkspace(cancellation);
            case "knowledge.article" -> knowledgeArticle(params, cancellation);
            case "knowledge.search" -> knowledgeSearch(params, cancellation);
            case "knowledge.import.preview" -> knowledgeImportPreview(params, cancellation);
            case "knowledge.import.execute" -> knowledgeImportExecute(params, cancellation, events);
            case "practice.catalog" -> practiceCatalog(cancellation);
            case "practice.preview" -> practicePreview(params, cancellation);
            case "practice.start" -> practiceStart(params, cancellation);
            case "practice.run" -> practiceAttempt(params, cancellation, false);
            case "practice.submit" -> practiceAttempt(params, cancellation, true);
            case "runner.capabilities" -> runnerCapabilities(cancellation);
            case "runner.run" -> runnerRun(params, cancellation, events);
            case "data.connections" -> dataConnections(cancellation);
            case "data.schema" -> dataSchema(params, cancellation);
            case "sql.analyze" -> sqlAnalyze(params, cancellation);
            case "sql.execute" -> sqlExecute(params, cancellation);
            case "sql.result.page" -> sqlResultPage(params, cancellation);
            case "ai.knowledge.ask" -> aiKnowledgeAsk(params, cancellation, events);
            case "account.login" -> accountLogin(params, cancellation);
            case "account.logout" -> accountLogout(cancellation);
            case "teaching.workspace" -> teachingWorkspace(cancellation);
            case "teaching.exercise.toggle" -> teachingExerciseToggle(params, cancellation);
            case "cloud.workspace" -> cloudWorkspace(params, cancellation);
            case "cloud.sync" -> cloudSync(cancellation, events);
            case "cloud.class.create" -> cloudClassCreate(params, cancellation);
            case "settings.workspace" -> settingsWorkspace(cancellation);
            case "settings.update" -> settingsUpdate(params, cancellation);
            case "migration.status" -> migrationStatus();
            case "editor.languages" -> editorLanguages();
            case "benchmark.echo" -> params.deepCopy();
            case "task.demo" -> demoTask(params, cancellation, events);
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

    private JsonNode settingsWorkspace(CancellationToken cancellation) {
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
        result.put("connectivity", general.connectivitySummary());
        result.set("general", mapper.valueToTree(settings));
        result.set("storage", mapper.valueToTree(general.storage()));
        result.set("runnerCapabilities", mapper.valueToTree(core.getBean(LocalCodeRunner.class).capabilities()));
        result.set("components", mapper.valueToTree(core.getBean(ManagedComponentService.class).statuses()));
        result.put("manualPathPolicy", "java.home, JAVA_HOME, JDK_HOME, PATH and documented tool locations");
        result.put("secretsExposed", false);
        return result;
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
        GeneralSoftwareSettings updated = new GeneralSoftwareSettings(
            current.formatVersion(),
            params.path("automaticUpdateChecks").asBoolean(current.automaticUpdateChecks()),
            current.skippedVersion(), current.proxyMode(), current.proxyHost(), current.proxyPort(),
            params.path("reducedMotion").asBoolean(current.reducedMotion()),
            params.path("highContrast").asBoolean(current.highContrast()),
            current.supportLogging(), current.supportLoggingExpiresAt(), current.updateMirrorsEnabled(),
            language,
            params.path("nativeNotificationsEnabled").asBoolean(current.nativeNotificationsEnabled()),
            params.path("meteredNetwork").asBoolean(current.meteredNetwork()));
        service.saveSettings(updated);
        cancellation.throwIfCancelled();
        return mapper.createObjectNode().put("saved", true).put("developerMode", developerMode)
            .set("general", mapper.valueToTree(updated));
    }

    private ObjectNode migrationStatus() {
        ObjectNode result = mapper.createObjectNode();
        result.put("version", ApplicationVersion.current());
        result.put("stage", "ALPHA_COMPLETE");
        result.put("defaultProductionUiChanged", false);
        result.put("javaFxFallback", true);
        result.put("offlineCore", true);
        result.put("schemaSemanticsChanged", false);
        ArrayNode features = result.putArray("features");
        addParityFeature(features, "learning", "今天与确定性学习队列", "COMPLETE");
        addParityFeature(features, "knowledge", "课程、知识与 Obsidian 导入", "COMPLETE");
        addParityFeature(features, "practice", "练习、实验与多语言 Runner", "COMPLETE");
        addParityFeature(features, "data", "数据库、SQL 与 AI", "COMPLETE");
        addParityFeature(features, "teaching", "题库、学情与教师角色", "COMPLETE");
        addParityFeature(features, "cloud", "班级、账号与云端同步", "COMPLETE");
        addParityFeature(features, "settings", "设置、环境探测与恢复说明", "COMPLETE");
        return result;
    }

    private void addParityFeature(ArrayNode features, String id, String title, String status) {
        features.addObject().put("id", id).put("title", title).put("status", status);
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
        var queue = applicationContext.getBean(StudentLearningQueueService.class).refresh();
        var dashboard = queue.dashboard();
        ObjectNode result = mapper.createObjectNode();
        result.put("ownerId", dashboard.ownerId());
        result.put("policyVersion", dashboard.policyVersion());
        result.put("knowledgePointCount", dashboard.mastery().size());
        result.put("needsPracticeCount", dashboard.mastery().stream()
            .filter(item -> item.level() == MasteryLevel.NEEDS_PRACTICE).count());
        result.put("cloudAvailable", queue.cloudAvailable());
        result.put("calculationMillis", dashboard.calculationTime().toMillis());
        ArrayNode actions = result.putArray("actions");
        queue.items().forEach(item -> {
            ObjectNode action = actions.addObject();
            action.put("id", item.action().id());
            action.put("type", item.action().type().name());
            action.put("title", item.action().title());
            action.put("description", item.action().description());
            action.put("priority", item.action().priority());
        });
        return result;
    }

    private ObjectNode knowledgeSample() throws IOException {
        try (InputStream stream = DefaultLocalAppApi.class.getResourceAsStream("/v3-alpha1/knowledge-sample.md")) {
            if (stream == null) throw new IOException("Bundled Alpha.1 knowledge sample is missing");
            ObjectNode result = mapper.createObjectNode();
            result.put("id", "alpha1-safe-rendering");
            result.put("title", "确定性学习闭环");
            result.put("markdown", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            result.put("trustedHtml", false);
            result.put("externalResourcesAllowed", false);
            return result;
        }
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
        });
        return mapper.createObjectNode().set("items", items);
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
        sqlResults.put(resultId, new CachedSqlResult(execution, Instant.now().plusSeconds(600)));
        return sqlPage(resultId, execution, 0, Math.clamp(params.path("pageSize").asInt(50), 1, 100));
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

    private static String requiredText(JsonNode params, String field, int maxLength) {
        String value = params.path(field).asText("").trim();
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain at most " + maxLength + " characters");
        }
        return value;
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

    private ObjectNode demoTask(JsonNode params, CancellationToken cancellation,
                                Consumer<LocalAppEvent> events) throws InterruptedException {
        int steps = Math.clamp(params.path("steps").asInt(5), 1, 20);
        int delayMillis = Math.clamp(params.path("delayMillis").asInt(25), 0, 500);
        for (int step = 1; step <= steps; step++) {
            cancellation.throwIfCancelled();
            if (delayMillis > 0) Thread.sleep(delayMillis);
            ObjectNode payload = mapper.createObjectNode();
            payload.put("step", step);
            payload.put("total", steps);
            payload.put("percent", step * 100 / steps);
            events.accept(new LocalAppEvent("progress", payload));
        }
        return mapper.createObjectNode().put("completed", true).put("steps", steps);
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
