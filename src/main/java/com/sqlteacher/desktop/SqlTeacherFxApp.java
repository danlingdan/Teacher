package com.sqlteacher.desktop;

import com.sqlteacher.application.database.DatabaseInitializationService;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.nl2sql.Nl2SqlSafetyService;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.desktop.controller.MainWindowController;
import com.sqlteacher.infrastructure.spring.SqlTeacherApplicationConfig;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

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

    private static final String MAIN_WINDOW_FXML = "/fxml/MainWindow.fxml";

    /** 默认窗口尺寸：在 1366x768 分辨率下留出任务栏与边距的舒适可视区。 */
    private static final double DEFAULT_WIDTH = 1180.0;
    private static final double DEFAULT_HEIGHT = 720.0;

    private AnnotationConfigApplicationContext applicationContext;
    private SqlExecutionService sqlExecutionService;
    private DatabaseMetadataService databaseMetadataService;
    private Nl2SqlSafetyService nl2SqlSafetyService;
    private SqlRiskAnalysisService sqlRiskAnalysisService;

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
            sqlRiskAnalysisService = context.getBean(SqlRiskAnalysisService.class);
            applicationContext = context;
        } catch (RuntimeException error) {
            context.close();
            throw error;
        }
    }

    @Override
    public void start(Stage stage) throws IOException {
        if (sqlExecutionService == null || databaseMetadataService == null
            || nl2SqlSafetyService == null || sqlRiskAnalysisService == null) {
            throw new IllegalStateException("Services are unavailable because application initialization did not complete");
        }

        URL fxml = SqlTeacherFxApp.class.getResource(MAIN_WINDOW_FXML);
        if (fxml == null) {
            throw new IllegalStateException("Missing FXML resource on classpath: " + MAIN_WINDOW_FXML);
        }

        FXMLLoader loader = new FXMLLoader(fxml);
        // MainWindow.fxml 的控制器改为构造注入（无无参构造），故必须提供 controllerFactory。
        loader.setControllerFactory(type -> {
            if (type == MainWindowController.class) {
                return new MainWindowController(
                    sqlExecutionService,
                    databaseMetadataService,
                    nl2SqlSafetyService,
                    sqlRiskAnalysisService
                );
            }
            throw new IllegalStateException("Unexpected controller type for MainWindow.fxml: " + type);
        });

        Parent root = loader.load();

        stage.setTitle("SQLTeacher");
        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        // 以绝对类路径加载 CSS，确保无论 FXML 相对路径如何解析，主题样式都能生效。
        URL css = SqlTeacherFxApp.class.getResource("/css/app.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        // 设置 Scene 填充色，与 CSS 主题底色一致，作为兜底防止出现白色背景。
        scene.setFill(javafx.scene.paint.Color.web("#141c30"));
        stage.setScene(scene);
        stage.setMinWidth(960.0);
        stage.setMinHeight(600.0);
        stage.show();
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
        sqlRiskAnalysisService = null;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
