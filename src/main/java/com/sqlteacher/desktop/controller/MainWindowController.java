package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.execution.SqlExecutionService;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;

import java.io.IOException;
import java.net.URL;
import java.util.List;

/**
 * 主窗口控制器：负责左侧侧边导航栏与右侧页面容器之间的路由，并把 SQL 练习页
 * <strong>内嵌</strong>进右侧 {@code pageContainer} 插槽（不再作为独立窗口弹出）。
 *
 * <p><b>依赖注入</b>：本控制器使用<strong>构造注入</strong>，由 {@code SqlTeacherFxApp} 通过
 * {@link FXMLLoader#setControllerFactory} 传入 {@link SqlExecutionService}（运行期实现由
 * Spring Context 提供）。加载 {@code SqlPractice.fxml} 时，再以同样的方式把该服务
 * 构造注入到 {@code SqlPracticeController}，形成 {@code MainWindow -> SqlPractice} 的贯穿注入。
 *
 * <p><b>路由策略</b>：SQL 练习页在首次导航时懒加载一次并缓存复用（保留输入与结果状态，
 * 避免每次点击重复加载）。后续新增页面同样通过 {@link #showPage(Node)} 复用同一插槽。
 */
public final class MainWindowController {

    /** 选中态样式类，与 css/app.css 中的 {@code .nav-button.selected} 对应。 */
    private static final String SELECTED_STYLE_CLASS = "selected";

    /** SQL 练习子页面 FXML 的类路径位置。 */
    private static final String SQL_PRACTICE_FXML = "/fxml/SqlPractice.fxml";

    /** SQL 执行服务（应用层接口）；运行期实现由 Spring 提供，向下注入到子页面控制器。 */
    private final SqlExecutionService sqlExecutionService;

    /** SQL 练习导航按钮（左侧导航栏，当前唯一可跳转入口）。 */
    @FXML
    private Button sqlPracticeNavButton;

    /** 右侧页面容器，导航切换时替换其中的内容节点。 */
    @FXML
    private StackPane pageContainer;

    /** SQL 练习页视图，懒加载一次后缓存复用（内嵌于 pageContainer，绝不独立弹窗）。 */
    private Node sqlPracticePage;

    /**
     * 构造注入 SQL 执行服务，供加载 SQL 练习页时向下传递给 {@code SqlPracticeController}。
     *
     * @param sqlExecutionService 应用层 SQL 执行服务接口，不可为 {@code null}
     */
    public MainWindowController(SqlExecutionService sqlExecutionService) {
        this.sqlExecutionService = sqlExecutionService;
    }

    /**
     * FXML 加载完成、控件注入后由 JavaFX 自动回调。默认进入 SQL 练习页。
     */
    @FXML
    private void initialize() {
        onNavigateSqlPractice();
    }

    /**
     * 导航到 SQL 练习页：高亮当前按钮并将 SQL 练习视图放入右侧容器。
     */
    @FXML
    private void onNavigateSqlPractice() {
        selectNav(sqlPracticeNavButton);
        showPage(sqlPracticePage());
    }

    /**
     * 切换导航选中态：清除所有导航按钮的选中样式，仅对目标按钮追加选中样式。
     */
    private void selectNav(Button target) {
        for (Button navButton : navButtons()) {
            navButton.getStyleClass().remove(SELECTED_STYLE_CLASS);
        }
        if (!target.getStyleClass().contains(SELECTED_STYLE_CLASS)) {
            target.getStyleClass().add(SELECTED_STYLE_CLASS);
        }
    }

    /** 当前全部导航按钮集合，新增页面时在此登记。 */
    private List<Button> navButtons() {
        return List.of(sqlPracticeNavButton);
    }

    /** 将目标节点设为右侧容器的唯一子节点，实现页面切换。 */
    private void showPage(Node page) {
        pageContainer.getChildren().setAll(page);
    }

    /** 懒加载并缓存 SQL 练习页视图，避免重复加载丢失界面状态。 */
    private Node sqlPracticePage() {
        if (sqlPracticePage == null) {
            sqlPracticePage = loadSqlPracticePage();
        }
        return sqlPracticePage;
    }

    /**
     * 加载 {@code SqlPractice.fxml} 并内嵌到右侧插槽。
     *
     * <p>因 {@code SqlPracticeController} 使用构造注入（无无参构造），此处通过
     * {@link FXMLLoader#setControllerFactory} 提供已注入 {@link #sqlExecutionService} 的实例，
     * 不能使用无参默认 {@code load()}。返回的根节点直接放入 {@code pageContainer}，
     * SQL 练习页因此作为主窗口内的嵌套区域呈现，而非独立顶层窗口。
     */
    private Node loadSqlPracticePage() {
        URL fxml = MainWindowController.class.getResource(SQL_PRACTICE_FXML);
        if (fxml == null) {
            throw new IllegalStateException("Missing FXML resource on classpath: " + SQL_PRACTICE_FXML);
        }
        FXMLLoader loader = new FXMLLoader(fxml);
        loader.setControllerFactory(type -> {
            if (type == SqlPracticeController.class) {
                return new SqlPracticeController(sqlExecutionService);
            }
            throw new IllegalStateException("Unexpected controller type for SqlPractice.fxml: " + type);
        });
        try {
            return loader.load();
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load " + SQL_PRACTICE_FXML, error);
        }
    }
}
