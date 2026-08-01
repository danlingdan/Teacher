package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.application.ai.AiModelSelectionService;
import com.sqlteacher.application.analytics.LearningAnalyticsService;
import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.connection.DatabaseConnectionTestService;
import com.sqlteacher.application.connection.DatabaseCredentialSession;
import com.sqlteacher.application.error.ApplicationExceptionMapper;
import com.sqlteacher.application.exercise.ExerciseCatalogService;
import com.sqlteacher.application.exercise.ExerciseManagementService;
import com.sqlteacher.application.exercise.ExercisePracticeService;
import com.sqlteacher.application.knowledge.KnowledgeDocumentService;
import com.sqlteacher.application.knowledge.KnowledgeSearchService;
import com.sqlteacher.application.knowledge.CourseKnowledgeService;
import com.sqlteacher.application.knowledge.GroundedKnowledgeExplanationService;
import com.sqlteacher.application.planning.GroundedTutorService;
import com.sqlteacher.application.knowledge.HybridKnowledgeRetrievalService;
import com.sqlteacher.application.knowledge.KnowledgeIndexService;
import com.sqlteacher.application.knowledge.KnowledgeReadStateService;
import com.sqlteacher.application.knowledge.SafeWebContentFetcher;
import com.sqlteacher.application.knowledge.WebSearchProvider;
import com.sqlteacher.application.maintenance.DataMaintenanceService;
import com.sqlteacher.application.maintenance.ApplicationBackupService;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.collaboration.CloudApiClient;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.CloudLearningSyncService;
import com.sqlteacher.application.collaboration.DesktopAccessProfile;
import com.sqlteacher.application.collaboration.DesktopCapability;
import com.sqlteacher.application.collaboration.AssignmentDeliveryService;
import com.sqlteacher.application.collaboration.AssignmentTaskContext;
import com.sqlteacher.application.collaboration.FeedbackDraftEnhancer;
import com.sqlteacher.application.collaboration.TeachingContentCache;
import com.sqlteacher.application.ai.NetworkAiSettingsService;
import com.sqlteacher.application.ai.AiProviderProfileService;
import com.sqlteacher.application.ai.AiProviderProbeService;
import com.sqlteacher.application.ai.AiTaskHistoryService;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.nl2sql.Nl2SqlSafetyService;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.application.risk.SqlSafetyModeService;
import com.sqlteacher.application.learning.LearningDiagnosisService;
import com.sqlteacher.application.learning.InterventionService;
import com.sqlteacher.application.learning.StudentLearningQueueService;
import com.sqlteacher.application.support.DiagnosticBundleService;
import com.sqlteacher.application.support.ProblemReportService;
import com.sqlteacher.application.system.CommandPaletteModel;
import com.sqlteacher.application.system.GeneralSoftwareService;
import com.sqlteacher.desktop.AppI18n;
import com.sqlteacher.application.update.UpdateService;
import com.sqlteacher.desktop.GlobalLoading;
import com.sqlteacher.desktop.appearance.UiIcon;
import com.sqlteacher.desktop.appearance.UiIcons;
import com.sqlteacher.desktop.appearance.UiLayoutMode;
import com.sqlteacher.desktop.appearance.UiPreferencesService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 主窗口控制器：负责顶部横向导航栏与右侧页面容器之间的路由，并把各子页面
 * <strong>内嵌</strong>进右侧 {@code pageContainer} 插槽（不再作为独立窗口弹出）。
 *
 * <p><b>依赖注入</b>：本控制器使用<strong>构造注入</strong>，由 {@code SqlTeacherFxApp} 通过
 * {@link FXMLLoader#setControllerFactory} 传入 {@link SqlExecutionService} 与
 * {@link DatabaseMetadataService}（运行期实现由 Spring Context 提供）。加载子页面 FXML 时，
 * 再以同样的方式把所需服务构造注入到子页面控制器，形成贯穿注入。
 *
 * <p><b>全局 Loading</b>：本控制器在 {@link #initialize()} 中读取 FXML 里定义的
 * {@code loadingOverlay} 与 {@code loadingText}，并调用 {@link GlobalLoading#initialize}
 * 完成全局遮罩初始化。后续所有子页面的耗时操作统一通过 {@link GlobalLoading#show(String)}
 * / {@link GlobalLoading#hide()} 复用该遮罩。
 *
 * <p><b>路由策略</b>：SQL 练习页与表结构页均在首次导航时懒加载一次并缓存复用
 * （保留输入与结果状态，避免每次点击重复加载）。后续新增页面同样通过 {@link #showPage(Node)}
 * 复用同一插槽。
 *
 * <p><b>双向联动</b>：表结构页点击表名时，通过 {@link #fillSqlCallback} 把
 * {@code AppI18n.get("MainWindowController.1")} 同步填充到 SQL 练习页输入框（不自动跳转页面，
 * 用户可手动点击顶部「SQL 练习」导航查看/编辑），实现页面间联动。
 */
public final class MainWindowController {

    /** 选中态样式类，与 css/app.css 中的 {@code .nav-button.selected} 对应。 */
    private static final String SELECTED_STYLE_CLASS = "selected";

    /** 首页 FXML 的类路径位置。 */
    private static final String HOME_FXML = "/fxml/home.fxml";

    /** SQL 练习子页面 FXML 的类路径位置。 */
    private static final String SQL_PRACTICE_FXML = "/fxml/SqlPractice.fxml";

    /** 表结构浏览子页面 FXML 的类路径位置。 */
    private static final String TABLE_SCHEMA_FXML = "/fxml/TableSchemaView.fxml";

    /** AI 助手子页面 FXML 的类路径位置。 */
    private static final String AI_ASSISTANT_FXML = "/fxml/ai-assistant.fxml";
    private static final String SETTINGS_FXML = "/fxml/settings.fxml";
    private static final String STUDENT_EXERCISE_FXML = "/fxml/student-exercise.fxml";
    private static final String EXERCISE_MANAGEMENT_FXML = "/fxml/exercise-management.fxml";
    private static final String EXERCISE_PROGRESS_FXML = "/fxml/exercise-progress.fxml";
    private static final String KNOWLEDGE_CENTER_FXML = "/fxml/knowledge-center.fxml";
    private static final String CLOUD_CENTER_FXML = "/fxml/cloud-center.fxml";
    private static final String TEACHING_CONTENT_FXML = "/fxml/teaching-content.fxml";

    /** SQL 执行服务（应用层接口）；运行期实现由 Spring 提供，向下注入到 SQL 练习页控制器。 */
    private final SqlExecutionService sqlExecutionService;

    /** 表元数据服务（应用层接口）；运行期实现由 Spring 提供，向下注入到表结构页控制器。 */
    private final DatabaseMetadataService databaseMetadataService;

    /** NL2SQL 服务（应用层接口）；运行期实现由 Spring 提供，向下注入到 AI 助手页控制器。 */
    private final Nl2SqlSafetyService nl2SqlSafetyService;

    /** 本地 AI 模型发现与选择服务。 */
    private final AiModelSelectionService aiModelSelectionService;

    /** SQL 风险分析服务（应用层接口）；运行期实现由 Spring 提供，向下注入到 AI 助手页控制器。 */
    private final SqlRiskAnalysisService sqlRiskAnalysisService;
    private final SqlSafetyModeService sqlSafetyModeService;
    private final ConnectionManagementService connectionManagementService;
    private final DatabaseConnectionTestService databaseConnectionTestService;
    private final ApplicationExceptionMapper applicationExceptionMapper;
    private final DatabaseCredentialSession databaseCredentialSession;
    private final ExerciseCatalogService exerciseCatalogService;
    private final ExercisePracticeService exercisePracticeService;
    private final ExerciseManagementService exerciseManagementService;
    private final LearningAnalyticsService learningAnalyticsService;
    private final DataMaintenanceService dataMaintenanceService;
    private final KnowledgeDocumentService knowledgeDocumentService;
    private final KnowledgeSearchService knowledgeSearchService;
    private final CourseKnowledgeService courseKnowledgeService;
    private final GroundedKnowledgeExplanationService groundedKnowledgeExplanationService;
    private final GroundedTutorService groundedTutorService;
    private final HybridKnowledgeRetrievalService hybridKnowledgeRetrievalService;
    private final KnowledgeIndexService knowledgeIndexService;
    private final KnowledgeReadStateService knowledgeReadStateService;
    private final WebSearchProvider webSearchProvider;
    private final SafeWebContentFetcher safeWebContentFetcher;
    private final ApplicationBackupService applicationBackupService;
    private final SqlTeacherConfiguration configuration;
    private final CloudApiClient cloudApiClient;
    private final CloudSessionService cloudSessionService;
    private final CloudLearningSyncService cloudLearningSyncService;
    private final AssignmentDeliveryService assignmentDeliveryService;
    private final NetworkAiSettingsService networkAiSettingsService;
    private final AiProviderProfileService aiProviderProfileService;
    private final AiProviderProbeService aiProviderProbeService;
    private final AiTaskHistoryService aiTaskHistoryService;
    private final DesktopAccessProfile accessProfile;
    private final FeedbackDraftEnhancer feedbackDraftEnhancer;
    private final TeachingContentCache teachingContentCache;
    private final LearningDiagnosisService learningDiagnosisService;
    private final InterventionService interventionService;
    private final StudentLearningQueueService studentLearningQueueService;
    private final UiPreferencesService uiPreferences;
    private final UpdateService updateService;
    private final ProblemReportService problemReportService;
    private final DiagnosticBundleService diagnosticBundleService;
    private final GeneralSoftwareService generalSoftwareService;
    private final Runnable switchIdentityAction;

    /**
     * 表名选中回调：表结构页点击表名时触发，把 {@code AppI18n.get("MainWindowController.2")}
     * 填充到 SQL 练习页输入框（不自动跳转页面，避免打断右侧即时预览）。在构造器中初始化。
     */
    private final Consumer<String> fillSqlCallback;

    /** 主内容层 BorderPane（FXML 根节点改为 StackPane 后，业务内容仍放在 BorderPane 内）。 */
    @FXML
    private BorderPane mainContainer;
    @FXML
    private StackPane mainWindowRoot;
    @FXML
    private VBox appSidebar;
    @FXML
    private Label brandTitle;
    @FXML
    private Label sidebarCaption;

    /** 首页导航按钮（顶部导航栏）。 */
    @FXML
    private Button homeNavButton;

    /** SQL 练习导航按钮（顶部导航栏）。 */
    @FXML
    private Button sqlPracticeNavButton;

    /** 表结构导航按钮（顶部导航栏）。 */
    @FXML
    private Button tableSchemaNavButton;

    /** AI 助手导航按钮（顶部导航栏）。 */
    @FXML
    private Button aiAssistantNavButton;
    @FXML
    private Button settingsNavButton;
    @FXML
    private Button studentExerciseNavButton;
    @FXML
    private Button exerciseManagementNavButton;
    @FXML
    private Button exerciseProgressNavButton;
    @FXML
    private Button knowledgeCenterNavButton;
    @FXML
    private Button cloudCenterNavButton;
    @FXML
    private Button teachingContentNavButton;
    @FXML
    private Label identityLabel;
    @FXML
    private Button switchIdentityButton;
    @FXML private VBox learningNavGroup;
    @FXML private VBox teachingNavGroup;
    @FXML private VBox toolsNavGroup;
    @FXML private VBox systemNavGroup;
    @FXML private Label teachingNavGroupTitle;
    @FXML private Label learningNavGroupTitle;
    @FXML private Label toolsNavGroupTitle;
    @FXML private Label systemNavGroupTitle;

    private UiLayoutMode layoutMode;

    /** 右侧页面容器，导航切换时替换其中的内容节点。 */
    @FXML
    private StackPane pageContainer;

    /** 全局 Loading 遮罩根容器，覆盖整个主窗口。 */
    @FXML
    private StackPane loadingOverlay;

    /** 全局 Loading 提示文字 Label。 */
    @FXML
    private Label loadingText;

    /** SQL 练习页视图，懒加载一次后缓存复用（内嵌于 pageContainer，绝不独立弹窗）。 */
    private Node sqlPracticePage;

    /** 表结构页视图，懒加载一次后缓存复用。 */
    private Node tableSchemaPage;

    /** SQL 练习页控制器引用，懒加载时捕获，供 {@link #fillSqlCallback} 联动调用
     * {@link SqlPracticeController#fillSql(String)}。
     */
    private SqlPracticeController sqlPracticeController;

    /** 表结构页控制器引用，懒加载时捕获，供DDL执行后刷新表结构。 */
    private TableSchemaController tableSchemaController;

    /** AI 助手页视图，懒加载一次后缓存复用。 */
    private Node aiAssistantPage;

    /** 首页视图，懒加载一次后缓存复用。 */
    private Node homePage;
    private Node settingsPage;
    private Node studentExercisePage;
    private StudentExerciseController studentExerciseController;
    private Node exerciseManagementPage;
    private Node exerciseProgressPage;
    private Node knowledgeCenterPage;
    private KnowledgeCenterController knowledgeCenterController;
    private Node cloudCenterPage;
    private Node teachingContentPage;

    /**
     * 构造注入 SQL 执行服务、表元数据服务、NL2SQL 服务与 SQL 风险分析服务，并初始化表名选中回调。
     *
     * @param sqlExecutionService     应用层 SQL 执行服务接口，不可为 {@code null}
     * @param databaseMetadataService 应用层表元数据服务接口，不可为 {@code null}
     * @param nl2SqlSafetyService     应用层 NL2SQL 安全编排服务接口，不可为 {@code null}
     * @param sqlRiskAnalysisService  应用层 SQL 风险分析服务接口，不可为 {@code null}
     */
    public MainWindowController(SqlExecutionService sqlExecutionService,
                                DatabaseMetadataService databaseMetadataService,
                                Nl2SqlSafetyService nl2SqlSafetyService,
                                AiModelSelectionService aiModelSelectionService,
                                SqlRiskAnalysisService sqlRiskAnalysisService,
                                SqlSafetyModeService sqlSafetyModeService,
                                ConnectionManagementService connectionManagementService,
                                DatabaseConnectionTestService databaseConnectionTestService,
                                ApplicationExceptionMapper applicationExceptionMapper,
                                DatabaseCredentialSession databaseCredentialSession,
                                ExerciseCatalogService exerciseCatalogService,
                                ExercisePracticeService exercisePracticeService,
                                ExerciseManagementService exerciseManagementService,
                                LearningAnalyticsService learningAnalyticsService,
                                DataMaintenanceService dataMaintenanceService,
                                KnowledgeDocumentService knowledgeDocumentService,
                                KnowledgeSearchService knowledgeSearchService,
                                CourseKnowledgeService courseKnowledgeService,
                                GroundedKnowledgeExplanationService groundedKnowledgeExplanationService,
                                GroundedTutorService groundedTutorService,
                                HybridKnowledgeRetrievalService hybridKnowledgeRetrievalService,
                                KnowledgeIndexService knowledgeIndexService,
                                KnowledgeReadStateService knowledgeReadStateService,
                                WebSearchProvider webSearchProvider,
                                SafeWebContentFetcher safeWebContentFetcher,
                                ApplicationBackupService applicationBackupService,
                                SqlTeacherConfiguration configuration,
                                CloudApiClient cloudApiClient,
                                CloudSessionService cloudSessionService,
                                CloudLearningSyncService cloudLearningSyncService,
                                AssignmentDeliveryService assignmentDeliveryService,
                                NetworkAiSettingsService networkAiSettingsService,
                                AiProviderProfileService aiProviderProfileService,
                                AiProviderProbeService aiProviderProbeService,
                                AiTaskHistoryService aiTaskHistoryService,
                                FeedbackDraftEnhancer feedbackDraftEnhancer,
                                TeachingContentCache teachingContentCache,
                                LearningDiagnosisService learningDiagnosisService,
                                InterventionService interventionService,
                                StudentLearningQueueService studentLearningQueueService,
                                UiPreferencesService uiPreferences,
                                UpdateService updateService,
                                ProblemReportService problemReportService,
                                DiagnosticBundleService diagnosticBundleService,
                                GeneralSoftwareService generalSoftwareService,
                                DesktopAccessProfile accessProfile,
                                Runnable switchIdentityAction) {
        this.sqlExecutionService = Objects.requireNonNull(sqlExecutionService, "sqlExecutionService must not be null");
        this.databaseMetadataService = Objects.requireNonNull(databaseMetadataService, "databaseMetadataService must not be null");
        this.nl2SqlSafetyService = Objects.requireNonNull(nl2SqlSafetyService, "nl2SqlSafetyService must not be null");
        this.aiModelSelectionService = Objects.requireNonNull(
            aiModelSelectionService,
            "aiModelSelectionService must not be null"
        );
        this.sqlRiskAnalysisService = Objects.requireNonNull(sqlRiskAnalysisService, "sqlRiskAnalysisService must not be null");
        this.sqlSafetyModeService = Objects.requireNonNull(sqlSafetyModeService, "sqlSafetyModeService must not be null");
        this.connectionManagementService = Objects.requireNonNull(connectionManagementService);
        this.databaseConnectionTestService = Objects.requireNonNull(databaseConnectionTestService);
        this.applicationExceptionMapper = Objects.requireNonNull(applicationExceptionMapper);
        this.databaseCredentialSession = Objects.requireNonNull(databaseCredentialSession);
        this.exerciseCatalogService = Objects.requireNonNull(exerciseCatalogService);
        this.exercisePracticeService = Objects.requireNonNull(exercisePracticeService);
        this.exerciseManagementService = Objects.requireNonNull(exerciseManagementService);
        this.learningAnalyticsService = Objects.requireNonNull(learningAnalyticsService);
        this.dataMaintenanceService = Objects.requireNonNull(dataMaintenanceService);
        this.knowledgeDocumentService = Objects.requireNonNull(knowledgeDocumentService);
        this.knowledgeSearchService = Objects.requireNonNull(knowledgeSearchService);
        this.courseKnowledgeService = Objects.requireNonNull(courseKnowledgeService);
        this.groundedKnowledgeExplanationService = Objects.requireNonNull(groundedKnowledgeExplanationService);
        this.groundedTutorService = Objects.requireNonNull(groundedTutorService);
        this.hybridKnowledgeRetrievalService = Objects.requireNonNull(hybridKnowledgeRetrievalService);
        this.knowledgeIndexService = Objects.requireNonNull(knowledgeIndexService);
        this.knowledgeReadStateService = Objects.requireNonNull(knowledgeReadStateService);
        this.webSearchProvider = Objects.requireNonNull(webSearchProvider);
        this.safeWebContentFetcher = Objects.requireNonNull(safeWebContentFetcher);
        this.applicationBackupService = Objects.requireNonNull(applicationBackupService);
        this.configuration = Objects.requireNonNull(configuration);
        this.cloudApiClient = Objects.requireNonNull(cloudApiClient);
        this.cloudSessionService = Objects.requireNonNull(cloudSessionService);
        this.cloudLearningSyncService = Objects.requireNonNull(cloudLearningSyncService);
        this.assignmentDeliveryService = Objects.requireNonNull(assignmentDeliveryService);
        this.networkAiSettingsService = Objects.requireNonNull(networkAiSettingsService);
        this.aiProviderProfileService = Objects.requireNonNull(aiProviderProfileService);
        this.aiProviderProbeService = Objects.requireNonNull(aiProviderProbeService);
        this.aiTaskHistoryService = Objects.requireNonNull(aiTaskHistoryService);
        this.feedbackDraftEnhancer = Objects.requireNonNull(feedbackDraftEnhancer);
        this.teachingContentCache = Objects.requireNonNull(teachingContentCache);
        this.learningDiagnosisService = Objects.requireNonNull(learningDiagnosisService);
        this.interventionService = Objects.requireNonNull(interventionService);
        this.studentLearningQueueService = Objects.requireNonNull(studentLearningQueueService);
        this.uiPreferences = Objects.requireNonNull(uiPreferences);
        this.updateService = Objects.requireNonNull(updateService);
        this.problemReportService = Objects.requireNonNull(problemReportService);
        this.diagnosticBundleService = Objects.requireNonNull(diagnosticBundleService);
        this.generalSoftwareService = Objects.requireNonNull(generalSoftwareService);
        this.accessProfile = Objects.requireNonNull(accessProfile, "accessProfile must not be null");
        this.switchIdentityAction = Objects.requireNonNull(switchIdentityAction, "switchIdentityAction must not be null");
        this.fillSqlCallback = sql -> {
            // 确保 SQL 练习页已加载并捕获控制器引用，仅填充 SQL 不跳转页面。
            sqlPracticePage();
            if (sqlPracticeController != null) {
                sqlPracticeController.fillSql(sql);
            }
        };
    }

    /**
     * FXML 加载完成、控件注入后由 JavaFX 自动回调。
     * 初始化全局 Loading 遮罩，并默认进入首页。
     */
    @FXML
    private void initialize() {
        GlobalLoading.initialize(loadingOverlay, loadingText);
        decorateNavigation();
        applyAccessPolicy();
        bindResponsiveLayout();
        onNavigateHome();
    }

    private void bindResponsiveLayout() {
        mainWindowRoot.widthProperty().addListener((ignored, oldWidth, newWidth) ->
            applyResponsiveLayout(newWidth.doubleValue()));
        Platform.runLater(() -> applyResponsiveLayout(mainWindowRoot.getWidth()));
    }

    private void applyResponsiveLayout(double width) {
        if (width <= 0) return;
        UiLayoutMode next = UiLayoutMode.forWidth(width);
        if (next == layoutMode) return;
        layoutMode = next;
        for (UiLayoutMode candidate : UiLayoutMode.values()) {
            mainWindowRoot.getStyleClass().remove(candidate.styleClass());
        }
        mainWindowRoot.getStyleClass().add(next.styleClass());

        boolean compact = next == UiLayoutMode.COMPACT;
        double sidebarWidth = switch (next) {
            case COMPACT -> 72.0;
            case MEDIUM -> 196.0;
            case WIDE -> 232.0;
        };
        appSidebar.setMinWidth(sidebarWidth);
        appSidebar.setPrefWidth(sidebarWidth);
        appSidebar.setMaxWidth(sidebarWidth);
        brandTitle.setText(compact ? "ST" : "SQLTeacher");
        sidebarCaption.setVisible(!compact);
        sidebarCaption.setManaged(!compact);
        setVisibleAndManaged(learningNavGroupTitle, !compact);
        setVisibleAndManaged(teachingNavGroupTitle, !compact);
        setVisibleAndManaged(toolsNavGroupTitle, !compact);
        setVisibleAndManaged(systemNavGroupTitle, !compact);
        for (ButtonBase navButton : navButtons()) {
            navButton.setContentDisplay(compact ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.LEFT);
        }
        identityLabel.setText(compact
            ? accessProfile.roleLabel()
            : accessProfile.displayName() + " · " + accessProfile.roleLabel());
        switchIdentityButton.setContentDisplay(compact ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.LEFT);
    }

    private static void setVisibleAndManaged(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private void applyAccessPolicy() {
        identityLabel.setText(accessProfile.displayName() + " · " + accessProfile.roleLabel());
        if (accessProfile.kind() == DesktopAccessProfile.Kind.STUDENT) {
            teachingNavGroupTitle.setText(AppI18n.get("MainWindowController.3"));
            teachingContentNavButton.setText(AppI18n.get("MainWindowController.4"));
            cloudCenterNavButton.setText(AppI18n.get("MainWindowController.5"));
            UiIcons.decorate(teachingContentNavButton, UiIcon.BOOK, AppI18n.get("MainWindowController.6"));
            UiIcons.decorate(cloudCenterNavButton, UiIcon.CLOUD, AppI18n.get("MainWindowController.7"));
        } else if (accessProfile.kind() == DesktopAccessProfile.Kind.GUEST) {
            teachingNavGroupTitle.setText(AppI18n.get("MainWindowController.8"));
        }
        applyCapability(homeNavButton, DesktopCapability.HOME);
        applyCapability(sqlPracticeNavButton, DesktopCapability.SQL_PRACTICE);
        applyCapability(studentExerciseNavButton, DesktopCapability.STUDENT_EXERCISE);
        applyCapability(exerciseManagementNavButton, DesktopCapability.EXERCISE_MANAGEMENT);
        applyCapability(exerciseProgressNavButton, DesktopCapability.EXERCISE_PROGRESS);
        applyCapability(knowledgeCenterNavButton, DesktopCapability.KNOWLEDGE_CENTER);
        applyCapability(aiAssistantNavButton, DesktopCapability.AI_ASSISTANT);
        applyCapability(tableSchemaNavButton, DesktopCapability.TABLE_SCHEMA);
        applyCapability(settingsNavButton, DesktopCapability.SETTINGS);
        applyCapability(cloudCenterNavButton, DesktopCapability.CLOUD_CENTER);
        applyCapability(teachingContentNavButton, DesktopCapability.TEACHING_CONTENT);
        updateGroupVisibility(learningNavGroup, homeNavButton, studentExerciseNavButton, knowledgeCenterNavButton);
        updateGroupVisibility(teachingNavGroup, teachingContentNavButton, exerciseManagementNavButton,
            exerciseProgressNavButton, cloudCenterNavButton);
        updateGroupVisibility(toolsNavGroup, sqlPracticeNavButton, aiAssistantNavButton, tableSchemaNavButton);
        updateGroupVisibility(systemNavGroup, settingsNavButton);
    }

    private void decorateNavigation() {
        UiIcons.decorate(homeNavButton, UiIcon.HOME, AppI18n.get("MainWindowController.9"));
        UiIcons.decorate(studentExerciseNavButton, UiIcon.PRACTICE, AppI18n.get("MainWindowController.10"));
        UiIcons.decorate(knowledgeCenterNavButton, UiIcon.BOOK, AppI18n.get("MainWindowController.11"));
        UiIcons.decorate(teachingContentNavButton, UiIcon.LIBRARY, AppI18n.get("MainWindowController.12"));
        UiIcons.decorate(exerciseManagementNavButton, UiIcon.PRACTICE, AppI18n.get("MainWindowController.13"));
        UiIcons.decorate(exerciseProgressNavButton, UiIcon.CHART, AppI18n.get("MainWindowController.14"));
        UiIcons.decorate(cloudCenterNavButton, UiIcon.CLOUD, AppI18n.get("MainWindowController.15"));
        UiIcons.decorate(sqlPracticeNavButton, UiIcon.CODE, AppI18n.get("MainWindowController.16"));
        UiIcons.decorate(aiAssistantNavButton, UiIcon.SPARK, AppI18n.get("MainWindowController.17"));
        UiIcons.decorate(tableSchemaNavButton, UiIcon.TABLE, AppI18n.get("MainWindowController.18"));
        UiIcons.decorate(settingsNavButton, UiIcon.SETTINGS, AppI18n.get("MainWindowController.19"));
        UiIcons.decorate(switchIdentityButton, UiIcon.USER, AppI18n.get("MainWindowController.20"));
    }

    private static void updateGroupVisibility(VBox group, Button... buttons) {
        boolean visible = java.util.Arrays.stream(buttons).anyMatch(Button::isVisible);
        group.setVisible(visible);
        group.setManaged(visible);
    }

    private void applyCapability(Button button, DesktopCapability capability) {
        boolean allowed = accessProfile.can(capability);
        button.setVisible(allowed);
        button.setManaged(allowed);
    }

    @FXML
    private void onSwitchIdentity() {
        switchIdentityAction.run();
    }

    /**
     * 导航到首页：高亮首页按钮并将首页视图放入右侧容器。
     */
    @FXML
    private void onNavigateHome() {
        selectNav(homeNavButton);
        try {
            showPage(homePage());
        } catch (RuntimeException error) {
            throw new IllegalStateException(AppI18n.get("MainWindowController.21"), error);
        }
    }

    /**
     * 导航到 SQL 练习页：高亮当前按钮并将 SQL 练习视图放入右侧容器。
     * 若子页面加载失败，不切换页面，避免空白或崩溃。
     */
    @FXML
    private void onNavigateSqlPractice() {
        requireCapability(DesktopCapability.SQL_PRACTICE);
        selectNav(sqlPracticeNavButton);
        try {
            showPage(sqlPracticePage());
        } catch (RuntimeException error) {
            // 子页面加载失败时保持原页面，由调用方日志或后续错误区处理。
            throw new IllegalStateException(AppI18n.get("MainWindowController.22"), error);
        }
    }

    /**
     * 导航到表结构页：高亮当前按钮并将表结构视图放入右侧容器。
     * 若子页面加载失败，不切换页面，避免空白或崩溃。
     */
    @FXML
    private void onNavigateTableSchema() {
        requireCapability(DesktopCapability.TABLE_SCHEMA);
        selectNav(tableSchemaNavButton);
        try {
            showPage(tableSchemaPage());
        } catch (RuntimeException error) {
            throw new IllegalStateException(AppI18n.get("MainWindowController.23"), error);
        }
    }

    /**
     * 导航到 AI 助手页：高亮当前按钮并将 AI 助手视图放入右侧容器。
     * 若子页面加载失败，不切换页面，避免空白或崩溃。
     */
    @FXML
    private void onNavigateAiAssistant() {
        requireCapability(DesktopCapability.AI_ASSISTANT);
        selectNav(aiAssistantNavButton);
        try {
            showPage(aiAssistantPage());
        } catch (RuntimeException error) {
            throw new IllegalStateException(AppI18n.get("MainWindowController.24"), error);
        }
    }

    public void registerKeyboardShortcuts(Scene scene) {
        Objects.requireNonNull(scene, "scene must not be null");
        Button[] buttons = {
            homeNavButton, sqlPracticeNavButton, studentExerciseNavButton,
            exerciseManagementNavButton, exerciseProgressNavButton, knowledgeCenterNavButton,
            aiAssistantNavButton, tableSchemaNavButton, settingsNavButton
        };
        KeyCode[] keys = {
            KeyCode.DIGIT1, KeyCode.DIGIT2, KeyCode.DIGIT3,
            KeyCode.DIGIT4, KeyCode.DIGIT5, KeyCode.DIGIT6,
            KeyCode.DIGIT7, KeyCode.DIGIT8, KeyCode.DIGIT9
        };
        for (int index = 0; index < buttons.length; index++) {
            Button button = buttons[index];
            if (!button.isVisible()) continue;
            scene.getAccelerators().put(
                new KeyCodeCombination(keys[index], KeyCombination.CONTROL_DOWN),
                button::fire
            );
        }
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.K, KeyCombination.CONTROL_DOWN), this::onCommandPalette);
    }

    /** Opens the command palette (Ctrl+K). Destructive actions are never executed here, only navigated to. */
    private void onCommandPalette() {
        CommandPaletteModel model = new CommandPaletteModel();
        model.register("home", AppI18n.get("MainWindowController.25"), "home", false, "home");
        model.register("practice", AppI18n.get("MainWindowController.26"), "sql practice editor", false, "practice");
        model.register("student", AppI18n.get("MainWindowController.27"), "student practice", false, "student");
        model.register("teaching", AppI18n.get("MainWindowController.28"), "exercise management", false, "teaching");
        model.register("progress", AppI18n.get("MainWindowController.29"), "learning analytics dashboard", false, "progress");
        model.register("knowledge", AppI18n.get("MainWindowController.30"), "course knowledge", false, "knowledge");
        model.register("ai", AppI18n.get("MainWindowController.31"), "ai assistant", false, "ai");
        model.register("schema", AppI18n.get("MainWindowController.32"), "table schema", false, "schema");
        model.register("settings", AppI18n.get("MainWindowController.33"), "settings", false, "settings");
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle(AppI18n.get("app.name"));
        dialog.setHeaderText(AppI18n.get("MainWindowController.34"));
        dialog.setContentText(AppI18n.get("MainWindowController.35"));
        dialog.showAndWait().ifPresent(query -> {
            var matches = model.search(query, 5);
            if (matches.isEmpty()) return;
            CommandPaletteModel.Command command = matches.getFirst();
            Button target = switch (command.target()) {
                case "home" -> homeNavButton;
                case "practice" -> sqlPracticeNavButton;
                case "student" -> studentExerciseNavButton;
                case "teaching" -> exerciseManagementNavButton;
                case "progress" -> exerciseProgressNavButton;
                case "knowledge" -> knowledgeCenterNavButton;
                case "ai" -> aiAssistantNavButton;
                case "schema" -> tableSchemaNavButton;
                case "settings" -> settingsNavButton;
                default -> null;
            };
            if (target != null && target.isVisible()) target.fire();
        });
    }

    @FXML
    private void onNavigateSettings() {
        requireCapability(DesktopCapability.SETTINGS);
        selectNav(settingsNavButton);
        try {
            showPage(settingsPage());
        } catch (RuntimeException error) {
            throw new IllegalStateException(AppI18n.get("MainWindowController.36"), error);
        }
    }

    @FXML
    private void onNavigateStudentExercise() {
        requireCapability(DesktopCapability.STUDENT_EXERCISE);
        selectNav(studentExerciseNavButton);
        showPage(studentExercisePage());
    }

    @FXML
    private void onNavigateExerciseManagement() {
        requireCapability(DesktopCapability.EXERCISE_MANAGEMENT);
        selectNav(exerciseManagementNavButton);
        showPage(exerciseManagementPage());
    }

    @FXML
    private void onNavigateExerciseProgress() {
        requireCapability(DesktopCapability.EXERCISE_PROGRESS);
        selectNav(exerciseProgressNavButton);
        showPage(exerciseProgressPage());
    }

    @FXML
    private void onNavigateKnowledgeCenter() {
        requireCapability(DesktopCapability.KNOWLEDGE_CENTER);
        selectNav(knowledgeCenterNavButton);
        showPage(knowledgeCenterPage());
    }

    @FXML
    private void onNavigateCloudCenter() {
        requireCapability(DesktopCapability.CLOUD_CENTER);
        selectNav(cloudCenterNavButton);
        showPage(cloudCenterPage());
    }

    @FXML
    private void onNavigateTeachingContent() {
        requireCapability(DesktopCapability.TEACHING_CONTENT);
        selectNav(teachingContentNavButton);
        showPage(teachingContentPage());
    }

    /**
     * 切换导航选中态：清除所有导航按钮的选中样式，仅对目标按钮追加选中样式。
     */
    private void selectNav(ButtonBase target) {
        for (ButtonBase navButton : navButtons()) {
            navButton.getStyleClass().remove(SELECTED_STYLE_CLASS);
        }
        if (!target.getStyleClass().contains(SELECTED_STYLE_CLASS)) {
            target.getStyleClass().add(SELECTED_STYLE_CLASS);
        }
    }

    /** 当前全部导航按钮集合，新增页面时在此登记。 */
    private List<ButtonBase> navButtons() {
        return List.of(
            homeNavButton, sqlPracticeNavButton, studentExerciseNavButton, exerciseManagementNavButton,
            exerciseProgressNavButton,
            knowledgeCenterNavButton, aiAssistantNavButton, tableSchemaNavButton, settingsNavButton,
            cloudCenterNavButton, teachingContentNavButton
        );
    }

    /**
     * 将目标节点设为右侧容器的唯一子节点，实现页面切换。
     * 切换前先清空旧子节点，防止多层透明页面堆叠。
     */
    private void showPage(Node page) {
        pageContainer.getChildren().clear();
        pageContainer.getChildren().setAll(page);
    }

    /** 懒加载并缓存 SQL 练习页视图，避免重复加载丢失界面状态。 */
    private Node sqlPracticePage() {
        if (sqlPracticePage == null) {
            sqlPracticePage = loadSqlPracticePage();
        }
        return sqlPracticePage;
    }

    /** 懒加载并缓存表结构页视图，避免重复加载丢失界面状态。 */
    private Node tableSchemaPage() {
        if (tableSchemaPage == null) {
            tableSchemaPage = loadTableSchemaPage();
        }
        return tableSchemaPage;
    }

    /** 懒加载并缓存 AI 助手页视图，避免重复加载丢失界面状态。 */
    private Node aiAssistantPage() {
        if (aiAssistantPage == null) {
            aiAssistantPage = loadAiAssistantPage();
        }
        return aiAssistantPage;
    }

    /**
     * 加载 {@code SqlPractice.fxml} 并内嵌到右侧插槽。
     *
     * <p>因 {@code SqlPracticeController} 使用构造注入（无无参构造），此处通过
     * {@link FXMLLoader#setControllerFactory} 提供已注入 {@link #sqlExecutionService} 的实例，
     * 不能使用无参默认 {@code load()}。返回的根节点直接放入 {@code pageContainer}，
     * SQL 练习页因此作为主窗口内的嵌套区域呈现，而非独立顶层窗口。
     * 同时捕获控制器引用，供 {@link #fillSqlCallback} 联动调用。
     */
    private Node loadSqlPracticePage() {
        URL fxml = MainWindowController.class.getResource(SQL_PRACTICE_FXML);
        if (fxml == null) {
            throw new IllegalStateException("Missing FXML resource on classpath: " + SQL_PRACTICE_FXML);
        }
        FXMLLoader loader = new FXMLLoader(fxml, AppI18n.bundle());
        loader.setControllerFactory(type -> {
            if (type == SqlPracticeController.class) {
                sqlPracticeController = new SqlPracticeController(
                    sqlExecutionService,
                    sqlRiskAnalysisService,
                    connectionManagementService,
                    sqlSafetyModeService
                );
                sqlPracticeController.setOnDdlSuccessCallback(this::refreshTableSchema);
                return sqlPracticeController;
            }
            throw new IllegalStateException("Unexpected controller type for SqlPractice.fxml: " + type);
        });
        try {
            return loader.load();
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load " + SQL_PRACTICE_FXML, error);
        }
    }

    /**
     * 加载 {@code TableSchemaView.fxml} 并内嵌到右侧插槽。
     *
     * <p>因 {@code TableSchemaController} 使用构造注入（无无参构造），此处通过
     * {@link FXMLLoader#setControllerFactory} 提供已注入 {@link #databaseMetadataService}、
     * {@link #sqlExecutionService} 与 {@link #fillSqlCallback} 的实例。
     */
    private Node loadTableSchemaPage() {
        URL fxml = MainWindowController.class.getResource(TABLE_SCHEMA_FXML);
        if (fxml == null) {
            throw new IllegalStateException("Missing FXML resource on classpath: " + TABLE_SCHEMA_FXML);
        }
        FXMLLoader loader = new FXMLLoader(fxml, AppI18n.bundle());
        loader.setControllerFactory(type -> {
            if (type == TableSchemaController.class) {
                tableSchemaController = new TableSchemaController(
                    databaseMetadataService,
                    sqlExecutionService,
                    connectionManagementService,
                    fillSqlCallback
                );
                return tableSchemaController;
            }
            throw new IllegalStateException("Unexpected controller type for TableSchemaView.fxml: " + type);
        });
        try {
            return loader.load();
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load " + TABLE_SCHEMA_FXML, error);
        }
    }

    /**
     * 加载 {@code ai-assistant.fxml} 并内嵌到右侧插槽。
     */
    private Node loadAiAssistantPage() {
        URL fxml = MainWindowController.class.getResource(AI_ASSISTANT_FXML);
        if (fxml == null) {
            throw new IllegalStateException("Missing FXML resource on classpath: " + AI_ASSISTANT_FXML);
        }
        FXMLLoader loader = new FXMLLoader(fxml, AppI18n.bundle());
        loader.setControllerFactory(type -> {
            if (type == AiAssistantController.class) {
                return new AiAssistantController(
                    nl2SqlSafetyService,
                    aiModelSelectionService,
                    networkAiSettingsService,
                    aiProviderProfileService,
                    aiProviderProbeService,
                    aiTaskHistoryService,
                    sqlRiskAnalysisService,
                    connectionManagementService,
                    fillSqlCallback,
                    this::onNavigateSqlPractice
                );
            }
            throw new IllegalStateException("Unexpected controller type for ai-assistant.fxml: " + type);
        });
        try {
            return loader.load();
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load " + AI_ASSISTANT_FXML, error);
        }
    }

    /**
     * 刷新表结构：确保表结构页已加载，然后调用控制器刷新方法。
     * 供SQL执行服务在DDL执行成功后调用。
     */
    public void refreshTableSchema() {
        tableSchemaPage();
        if (tableSchemaController != null) {
            tableSchemaController.refreshTableSchema();
        }
    }

    /** 懒加载并缓存首页视图，避免重复加载。 */
    private Node homePage() {
        if (homePage == null) {
            homePage = loadHomePage();
        }
        return homePage;
    }

    private void requireCapability(DesktopCapability capability) {
        if (!accessProfile.can(capability)) {
            throw new SecurityException(AppI18n.get("MainWindowController.37") + accessProfile.roleLabel() + AppI18n.get("MainWindowController.38"));
        }
    }

    private Node settingsPage() {
        if (settingsPage == null) {
            URL fxml = MainWindowController.class.getResource(SETTINGS_FXML);
            if (fxml == null) throw new IllegalStateException("Missing FXML resource: " + SETTINGS_FXML);
            FXMLLoader loader = new FXMLLoader(fxml, AppI18n.bundle());
            loader.setControllerFactory(type -> {
                if (type == SettingsController.class) {
                    return new SettingsController(
                        connectionManagementService,
                        databaseConnectionTestService,
                        applicationExceptionMapper,
                        databaseCredentialSession,
                        applicationBackupService,
                        configuration,
                        accessProfile,
                        uiPreferences,
                        sqlSafetyModeService,
                        updateService,
                        problemReportService,
                        diagnosticBundleService,
                        generalSoftwareService,
                        cloudApiClient,
                        cloudSessionService,
                        switchIdentityAction
                    );
                }
                throw new IllegalStateException("Unexpected controller type for settings: " + type);
            });
            try {
                settingsPage = loader.load();
            } catch (IOException error) {
                throw new IllegalStateException("Failed to load " + SETTINGS_FXML, error);
            }
        }
        return settingsPage;
    }

    private Node studentExercisePage() {
        if (studentExercisePage == null) {
            URL fxml = MainWindowController.class.getResource(STUDENT_EXERCISE_FXML);
            if (fxml == null) throw new IllegalStateException("Missing FXML resource: " + STUDENT_EXERCISE_FXML);
            FXMLLoader loader = new FXMLLoader(fxml, AppI18n.bundle());
            loader.setControllerFactory(type -> {
                if (type == StudentExerciseController.class) {
                    studentExerciseController = new StudentExerciseController(
                        exerciseCatalogService, exercisePracticeService, applicationExceptionMapper,
                        assignmentDeliveryService);
                    return studentExerciseController;
                }
                throw new IllegalStateException("Unexpected controller type for student exercise: " + type);
            });
            try {
                studentExercisePage = loader.load();
            } catch (IOException error) {
                throw new IllegalStateException("Failed to load " + STUDENT_EXERCISE_FXML, error);
            }
        }
        return studentExercisePage;
    }

    private Node exerciseManagementPage() {
        if (exerciseManagementPage == null) {
            URL fxml = MainWindowController.class.getResource(EXERCISE_MANAGEMENT_FXML);
            if (fxml == null) throw new IllegalStateException("Missing FXML resource: " + EXERCISE_MANAGEMENT_FXML);
            FXMLLoader loader = new FXMLLoader(fxml, AppI18n.bundle());
            loader.setControllerFactory(type -> {
                if (type == ExerciseManagementController.class) {
                    return new ExerciseManagementController(exerciseManagementService, applicationExceptionMapper);
                }
                throw new IllegalStateException("Unexpected controller type for exercise management: " + type);
            });
            try {
                exerciseManagementPage = loader.load();
            } catch (IOException error) {
                throw new IllegalStateException("Failed to load " + EXERCISE_MANAGEMENT_FXML, error);
            }
        }
        return exerciseManagementPage;
    }

    private Node exerciseProgressPage() {
        if (exerciseProgressPage == null) {
            URL fxml = MainWindowController.class.getResource(EXERCISE_PROGRESS_FXML);
            if (fxml == null) throw new IllegalStateException("Missing FXML resource: " + EXERCISE_PROGRESS_FXML);
            FXMLLoader loader = new FXMLLoader(fxml, AppI18n.bundle());
            loader.setControllerFactory(type -> {
                if (type == ExerciseProgressController.class) {
                    return new ExerciseProgressController(
                        learningAnalyticsService, exerciseCatalogService, dataMaintenanceService,
                        interventionService, this::onNavigateTeachingContent, applicationExceptionMapper
                    );
                }
                throw new IllegalStateException("Unexpected controller type for exercise progress: " + type);
            });
            try {
                exerciseProgressPage = loader.load();
            } catch (IOException error) {
                throw new IllegalStateException("Failed to load " + EXERCISE_PROGRESS_FXML, error);
            }
        }
        return exerciseProgressPage;
    }

    private Node knowledgeCenterPage() {
        if (knowledgeCenterPage == null) {
            URL fxml = MainWindowController.class.getResource(KNOWLEDGE_CENTER_FXML);
            if (fxml == null) throw new IllegalStateException("Missing FXML resource: " + KNOWLEDGE_CENTER_FXML);
            FXMLLoader loader = new FXMLLoader(fxml, AppI18n.bundle());
            loader.setControllerFactory(type -> {
                if (type == KnowledgeCenterController.class) {
                    knowledgeCenterController = new KnowledgeCenterController(
                        knowledgeDocumentService,
                        courseKnowledgeService,
                        groundedKnowledgeExplanationService,
                        groundedTutorService,
                        hybridKnowledgeRetrievalService,
                        knowledgeIndexService,
                        knowledgeReadStateService,
                        webSearchProvider,
                        safeWebContentFetcher,
                        exerciseCatalogService,
                        this::openExercise,
                        accessProfile.kind() == DesktopAccessProfile.Kind.TEACHER
                            || accessProfile.kind() == DesktopAccessProfile.Kind.ADMIN,
                        applicationExceptionMapper
                    );
                    return knowledgeCenterController;
                }
                throw new IllegalStateException("Unexpected controller type for knowledge center: " + type);
            });
            try {
                knowledgeCenterPage = loader.load();
            } catch (IOException error) {
                throw new IllegalStateException("Failed to load " + KNOWLEDGE_CENTER_FXML, error);
            }
        }
        return knowledgeCenterPage;
    }

    private Node cloudCenterPage() {
        if (cloudCenterPage == null) {
            URL fxml = MainWindowController.class.getResource(CLOUD_CENTER_FXML);
            if (fxml == null) throw new IllegalStateException("Missing FXML resource: " + CLOUD_CENTER_FXML);
            FXMLLoader loader = new FXMLLoader(fxml, AppI18n.bundle());
            loader.setControllerFactory(type -> {
                if (type == CloudCenterController.class) {
                    return new CloudCenterController(
                        cloudApiClient,
                        cloudSessionService,
                        cloudLearningSyncService,
                        assignmentDeliveryService,
                        accessProfile,
                        switchIdentityAction,
                        this::openAssignment
                    );
                }
                throw new IllegalStateException("Unexpected controller type for cloud center: " + type);
            });
            try {
                cloudCenterPage = loader.load();
            } catch (IOException error) {
                throw new IllegalStateException("Failed to load " + CLOUD_CENTER_FXML, error);
            }
        }
        return cloudCenterPage;
    }

    private Node teachingContentPage() {
        if (teachingContentPage == null) {
            URL fxml = MainWindowController.class.getResource(TEACHING_CONTENT_FXML);
            if (fxml == null) throw new IllegalStateException("Missing FXML resource: " + TEACHING_CONTENT_FXML);
            FXMLLoader loader = new FXMLLoader(fxml, AppI18n.bundle());
            loader.setControllerFactory(type -> {
                if (type == TeachingContentController.class) {
                    return new TeachingContentController(cloudApiClient, cloudSessionService, accessProfile,
                        feedbackDraftEnhancer, teachingContentCache);
                }
                throw new IllegalStateException("Unexpected controller type for teaching content: " + type);
            });
            try {
                teachingContentPage = loader.load();
            } catch (IOException error) {
                throw new IllegalStateException("Failed to load " + TEACHING_CONTENT_FXML, error);
            }
        }
        return teachingContentPage;
    }

    private void openAssignment(AssignmentTaskContext task) {
        requireCapability(DesktopCapability.STUDENT_EXERCISE);
        Node page = studentExercisePage();
        studentExerciseController.openAssignment(task);
        selectNav(studentExerciseNavButton);
        showPage(page);
    }

    /**
     * 加载 {@code home.fxml} 并内嵌到右侧插槽。
     */
    private Node loadHomePage() {
        URL fxml = MainWindowController.class.getResource(HOME_FXML);
        if (fxml == null) {
            throw new IllegalStateException("Missing FXML resource on classpath: " + HOME_FXML);
        }
        FXMLLoader loader = new FXMLLoader(fxml, AppI18n.bundle());
        loader.setControllerFactory(type -> {
            if (type == HomeController.class) {
                HomeController controller = new HomeController(studentLearningQueueService, applicationExceptionMapper);
                controller.setOnNavigateAiAssistant(this::onNavigateAiAssistant);
                controller.setOnNavigateSqlPractice(this::onNavigateSqlPractice);
                controller.setOnNavigateTableSchema(this::onNavigateTableSchema);
                controller.setOnOpenExercise(this::openExercise);
                controller.setOnOpenKnowledge(this::openKnowledgePoint);
                controller.setOnOpenAssignment(this::openAssignment);
                controller.setOnReviewFeedback(this::onNavigateTeachingContent);
                return controller;
            }
            throw new IllegalStateException("Unexpected controller type for home.fxml: " + type);
        });
        try {
            return loader.load();
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load " + HOME_FXML, error);
        }
    }

    private void openExercise(String exerciseId) {
        requireCapability(DesktopCapability.STUDENT_EXERCISE);
        Node page = studentExercisePage();
        studentExerciseController.openExercise(exerciseId);
        selectNav(studentExerciseNavButton);
        showPage(page);
    }

    private void openKnowledgePoint(String knowledgePoint) {
        requireCapability(DesktopCapability.KNOWLEDGE_CENTER);
        Node page = knowledgeCenterPage();
        knowledgeCenterController.focusKnowledgePoint(knowledgePoint);
        selectNav(knowledgeCenterNavButton);
        showPage(page);
    }
}
