package com.sqlteacher.desktop;

import com.sqlteacher.application.database.DatabaseInitializationService;
import com.sqlteacher.application.ai.AiModelSelectionService;
import com.sqlteacher.application.analytics.LearningAnalyticsService;
import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.connection.DatabaseConnectionTestService;
import com.sqlteacher.application.connection.DatabaseCredentialSession;
import com.sqlteacher.application.error.ApplicationExceptionMapper;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.application.exercise.ExerciseCatalogService;
import com.sqlteacher.application.exercise.ExerciseManagementService;
import com.sqlteacher.application.exercise.ExercisePracticeService;
import com.sqlteacher.application.knowledge.KnowledgeDocumentService;
import com.sqlteacher.application.knowledge.KnowledgeSearchService;
import com.sqlteacher.application.maintenance.DataMaintenanceService;
import com.sqlteacher.application.maintenance.ApplicationBackupService;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.collaboration.CloudApiClient;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.CloudLearningSyncService;
import com.sqlteacher.application.collaboration.DesktopAccessProfile;
import com.sqlteacher.application.ai.NetworkAiSettingsService;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.nl2sql.Nl2SqlSafetyService;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.desktop.controller.MainWindowController;
import com.sqlteacher.desktop.controller.LoginGateController;
import com.sqlteacher.infrastructure.spring.SqlTeacherApplicationConfig;
import com.sqlteacher.infrastructure.cloud.InMemoryLearningEventOwnerContext;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;

/**
 * JavaFX 桌面端离线启动器（{@code mvn javafx:run} 的入口，对应 pom.xml 中的 mainClass）。
 *
 * <p>本类是整个桌面程序<strong>唯一</strong>的顶层窗口入口：{@link #start(Stage)} 仅加载
 * {@code /fxml/MainWindow.fxml} 一个顶层窗口，右侧内容区由 {@link MainWindowController} 负责
 * 内嵌各子页面（SQL 练习、表结构浏览等）。
 *
 * <p>应用在 JavaFX {@link #init()} 生命周期中启动 Spring Context 并初始化 SQLite 数据库。
 * {@link #start(Stage)} 只负责创建界面，并把 Spring 提供的 {@link SqlExecutionService} 与
 * {@link DatabaseMetadataService} 构造注入到控制器；{@link #stop()} 负责关闭 Spring Context。
 */
public final class SqlTeacherFxApp extends Application {

    private static final Logger LOG = LoggerFactory.getLogger(SqlTeacherFxApp.class);
    private static final String MAIN_WINDOW_FXML = "/fxml/MainWindow.fxml";
    private static final String LOGIN_GATE_FXML = "/fxml/login-gate.fxml";

    /** 默认窗口尺寸：在 1366x768 分辨率下留出任务栏与边距的舒适可视区。 */
    private static final double DEFAULT_WIDTH = 1180.0;
    private static final double DEFAULT_HEIGHT = 720.0;

    private AnnotationConfigApplicationContext applicationContext;
    private SqlExecutionService sqlExecutionService;
    private DatabaseMetadataService databaseMetadataService;
    private Nl2SqlSafetyService nl2SqlSafetyService;
    private AiModelSelectionService aiModelSelectionService;
    private SqlRiskAnalysisService sqlRiskAnalysisService;
    private ConnectionManagementService connectionManagementService;
    private DatabaseConnectionTestService databaseConnectionTestService;
    private ApplicationExceptionMapper applicationExceptionMapper;
    private DatabaseCredentialSession databaseCredentialSession;
    private ExerciseCatalogService exerciseCatalogService;
    private ExercisePracticeService exercisePracticeService;
    private ExerciseManagementService exerciseManagementService;
    private LearningAnalyticsService learningAnalyticsService;
    private DataMaintenanceService dataMaintenanceService;
    private KnowledgeDocumentService knowledgeDocumentService;
    private KnowledgeSearchService knowledgeSearchService;
    private ApplicationBackupService applicationBackupService;
    private SqlTeacherConfiguration configuration;
    private CloudApiClient cloudApiClient;
    private CloudSessionService cloudSessionService;
    private CloudLearningSyncService cloudLearningSyncService;
    private NetworkAiSettingsService networkAiSettingsService;
    private InMemoryLearningEventOwnerContext learningEventOwnerContext;

    /**
     * JavaFX 在非 Application Thread 上调用本方法，数据库初始化不会阻塞界面线程。
     */
    @Override
    public void init() {
        AnnotationConfigApplicationContext context =
            new AnnotationConfigApplicationContext(SqlTeacherApplicationConfig.class);
        try {
            context.getBean(DatabaseInitializationService.class).initialize();
            sqlExecutionService = context.getBean(SqlExecutionService.class);
            databaseMetadataService = context.getBean(DatabaseMetadataService.class);
            nl2SqlSafetyService = context.getBean(Nl2SqlSafetyService.class);
            aiModelSelectionService = context.getBean(AiModelSelectionService.class);
            sqlRiskAnalysisService = context.getBean(SqlRiskAnalysisService.class);
            connectionManagementService = context.getBean(ConnectionManagementService.class);
            databaseConnectionTestService = context.getBean(DatabaseConnectionTestService.class);
            applicationExceptionMapper = context.getBean(ApplicationExceptionMapper.class);
            databaseCredentialSession = context.getBean(DatabaseCredentialSession.class);
            exerciseCatalogService = context.getBean(ExerciseCatalogService.class);
            exercisePracticeService = context.getBean(ExercisePracticeService.class);
            exerciseManagementService = context.getBean(ExerciseManagementService.class);
            learningAnalyticsService = context.getBean(LearningAnalyticsService.class);
            dataMaintenanceService = context.getBean(DataMaintenanceService.class);
            knowledgeDocumentService = context.getBean(KnowledgeDocumentService.class);
            knowledgeSearchService = context.getBean(KnowledgeSearchService.class);
            applicationBackupService = context.getBean(ApplicationBackupService.class);
            configuration = context.getBean(SqlTeacherConfiguration.class);
            cloudApiClient = context.getBean(CloudApiClient.class);
            cloudSessionService = context.getBean(CloudSessionService.class);
            cloudLearningSyncService = context.getBean(CloudLearningSyncService.class);
            networkAiSettingsService = context.getBean(NetworkAiSettingsService.class);
            learningEventOwnerContext = context.getBean(InMemoryLearningEventOwnerContext.class);
            applicationContext = context;
        } catch (RuntimeException error) {
            context.close();
            throw error;
        }
    }

    @Override
    public void start(Stage stage) throws IOException {
        if (sqlExecutionService == null || databaseMetadataService == null
            || nl2SqlSafetyService == null || aiModelSelectionService == null
            || sqlRiskAnalysisService == null || connectionManagementService == null
            || databaseConnectionTestService == null || applicationExceptionMapper == null
            || databaseCredentialSession == null || exerciseCatalogService == null
            || exercisePracticeService == null || exerciseManagementService == null
            || learningAnalyticsService == null || dataMaintenanceService == null
            || knowledgeDocumentService == null || knowledgeSearchService == null
            || applicationBackupService == null || configuration == null
            || cloudApiClient == null || cloudSessionService == null || cloudLearningSyncService == null
            || networkAiSettingsService == null || learningEventOwnerContext == null) {
            throw new IllegalStateException("Services are unavailable because application initialization did not complete");
        }

        stage.setTitle("SQLTeacher");
        URL icon = SqlTeacherFxApp.class.getResource("/images/sqlteacher-icon.png");
        if (icon != null) {
            stage.getIcons().add(new Image(icon.toExternalForm()));
        }
        showLoginGate(stage);
    }

    private void showLoginGate(Stage stage) {
        learningEventOwnerContext.useGuest();
        cloudSessionService.current().ifPresentOrElse(
            session -> showMainWindow(stage, DesktopAccessProfile.from(session)),
            () -> showLoginGateContent(stage)
        );
    }

    private void showLoginGateContent(Stage stage) {
        URL fxml = requiredResource(LOGIN_GATE_FXML);
        FXMLLoader loader = new FXMLLoader(fxml);
        loader.setControllerFactory(type -> {
            if (type == LoginGateController.class) {
                return new LoginGateController(
                    cloudApiClient,
                    cloudSessionService,
                    cloudLearningSyncService,
                    profile -> showMainWindow(stage, profile)
                );
            }
            throw new IllegalStateException("Unexpected controller type for login gate: " + type);
        });
        try {
            Parent root = loader.load();
            Scene scene = themedScene(root, 1020.0, 650.0);
            stage.setMinWidth(880.0);
            stage.setMinHeight(600.0);
            stage.setScene(scene);
            stage.centerOnScreen();
            stage.show();
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load " + LOGIN_GATE_FXML, error);
        }
    }

    private void showMainWindow(Stage stage, DesktopAccessProfile accessProfile) {
        if (accessProfile.isGuest()) learningEventOwnerContext.useGuest();
        else learningEventOwnerContext.useAuthenticatedUser(accessProfile.userId());
        URL fxml = requiredResource(MAIN_WINDOW_FXML);
        FXMLLoader loader = new FXMLLoader(fxml);
        loader.setControllerFactory(type -> {
            if (type == MainWindowController.class) {
                return new MainWindowController(
                    sqlExecutionService,
                    databaseMetadataService,
                    nl2SqlSafetyService,
                    aiModelSelectionService,
                    sqlRiskAnalysisService,
                    connectionManagementService,
                    databaseConnectionTestService,
                    applicationExceptionMapper,
                    databaseCredentialSession,
                    exerciseCatalogService,
                    exercisePracticeService,
                    exerciseManagementService,
                    learningAnalyticsService,
                    dataMaintenanceService,
                    knowledgeDocumentService,
                    knowledgeSearchService,
                    applicationBackupService,
                    configuration,
                    cloudApiClient,
                    cloudSessionService,
                    cloudLearningSyncService,
                    networkAiSettingsService,
                    accessProfile,
                    () -> switchToLogin(stage)
                );
            }
            throw new IllegalStateException("Unexpected controller type for MainWindow.fxml: " + type);
        });
        try {
            Parent root = loader.load();
            MainWindowController controller = loader.getController();
            Scene scene = themedScene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
            controller.registerKeyboardShortcuts(scene);
            stage.setMinWidth(960.0);
            stage.setMinHeight(600.0);
            stage.setScene(scene);
            stage.centerOnScreen();
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load " + MAIN_WINDOW_FXML, error);
        }
    }

    private void switchToLogin(Stage stage) {
        var current = cloudSessionService.current();
        cloudSessionService.signOut();
        current.ifPresent(value -> DesktopExecutors.background().execute(() -> {
            try {
                cloudApiClient.logout(value.accessToken(), value.refreshToken());
            } catch (RuntimeException error) {
                LOG.warn("Cloud token revocation failed while switching identity: {}", error.getMessage());
            }
        }));
        showLoginGate(stage);
    }

    private static Scene themedScene(Parent root, double width, double height) {
        Scene scene = new Scene(root, width, height);
        URL css = SqlTeacherFxApp.class.getResource("/css/app.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        scene.setFill(javafx.scene.paint.Color.web("#141c30"));
        return scene;
    }

    private static URL requiredResource(String path) {
        URL resource = SqlTeacherFxApp.class.getResource(path);
        if (resource == null) throw new IllegalStateException("Missing FXML resource on classpath: " + path);
        return resource;
    }

    @Override
    public void stop() {
        if (applicationContext != null) {
            applicationContext.close();
            applicationContext = null;
        }
        sqlExecutionService = null;
        databaseMetadataService = null;
        nl2SqlSafetyService = null;
        aiModelSelectionService = null;
        sqlRiskAnalysisService = null;
        connectionManagementService = null;
        databaseConnectionTestService = null;
        applicationExceptionMapper = null;
        databaseCredentialSession = null;
        exerciseCatalogService = null;
        exercisePracticeService = null;
        exerciseManagementService = null;
        learningAnalyticsService = null;
        dataMaintenanceService = null;
        knowledgeDocumentService = null;
        knowledgeSearchService = null;
        cloudApiClient = null;
        cloudSessionService = null;
        cloudLearningSyncService = null;
        networkAiSettingsService = null;
        learningEventOwnerContext = null;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
