package com.sqlteacher.desktop;

import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.desktop.controller.MainWindowController;
import com.sqlteacher.desktop.mock.MockScenario;
import com.sqlteacher.desktop.mock.SqlExecutionMockService;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;

/**
 * JavaFX 桌面端离线启动器（{@code mvn javafx:run} 的入口，对应 pom.xml 中的 mainClass）。
 *
 * <p>本类是整个桌面程序<strong>唯一</strong>的顶层窗口入口：{@link #start(Stage)} 仅加载
 * {@code /fxml/MainWindow.fxml} 一个顶层窗口，右侧内容区由 {@link MainWindowController} 负责
 * 内嵌 {@code SqlPractice.fxml}，SQL 练习页不再作为独立窗口弹出。
 *
 * <p><b>离线 Mock 注入</b>：按团队边界约束，桌面模块全程离线开发，服务注入只允许 Mock 实现。
 * 这里构造一个 {@link SqlExecutionMockService}（默认 {@link MockScenario#NORMAL} 场景），
 * 并通过 {@link FXMLLoader#setControllerFactory} 以<strong>构造注入</strong>方式交给
 * {@link MainWindowController}，再由后者向下传递给 {@code SqlPracticeController}。全程不引入
 * Spring 容器、真实数据库或 AI 等后端实现类。
 */
public final class SqlTeacherFxApp extends Application {

    private static final String MAIN_WINDOW_FXML = "/fxml/MainWindow.fxml";

    /** 默认窗口尺寸：在 1366x768 分辨率下留出任务栏与边距的舒适可视区。 */
    private static final double DEFAULT_WIDTH = 1180.0;
    private static final double DEFAULT_HEIGHT = 720.0;

    @Override
    public void start(Stage stage) throws IOException {
        // 离线 Mock：SQL 执行服务的运行期实现，向下贯穿注入到 SqlPracticeController。
        SqlExecutionService sqlExecutionService = new SqlExecutionMockService(MockScenario.NORMAL);

        URL fxml = SqlTeacherFxApp.class.getResource(MAIN_WINDOW_FXML);
        if (fxml == null) {
            throw new IllegalStateException("Missing FXML resource on classpath: " + MAIN_WINDOW_FXML);
        }

        FXMLLoader loader = new FXMLLoader(fxml);
        // MainWindow.fxml 的控制器改为构造注入（无无参构造），故必须提供 controllerFactory。
        loader.setControllerFactory(type -> {
            if (type == MainWindowController.class) {
                return new MainWindowController(sqlExecutionService);
            }
            throw new IllegalStateException("Unexpected controller type for MainWindow.fxml: " + type);
        });

        Parent root = loader.load();

        stage.setTitle("SQLTeacher");
        stage.setScene(new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT));
        stage.setMinWidth(960.0);
        stage.setMinHeight(600.0);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
