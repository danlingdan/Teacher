package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.application.ai.AiModelSelectionService;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.nl2sql.Nl2SqlSafetyService;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.desktop.GlobalLoading;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;

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
 * {@code "SELECT * FROM 表名"} 同步填充到 SQL 练习页输入框（不自动跳转页面，
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

    /**
     * 表名选中回调：表结构页点击表名时触发，把 {@code "SELECT * FROM 表名"}
     * 填充到 SQL 练习页输入框（不自动跳转页面，避免打断右侧即时预览）。在构造器中初始化。
     */
    private final Consumer<String> fillSqlCallback;

    /** 主内容层 BorderPane（FXML 根节点改为 StackPane 后，业务内容仍放在 BorderPane 内）。 */
    @FXML
    private BorderPane mainContainer;

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
                                SqlRiskAnalysisService sqlRiskAnalysisService) {
        this.sqlExecutionService = Objects.requireNonNull(sqlExecutionService, "sqlExecutionService must not be null");
        this.databaseMetadataService = Objects.requireNonNull(databaseMetadataService, "databaseMetadataService must not be null");
        this.nl2SqlSafetyService = Objects.requireNonNull(nl2SqlSafetyService, "nl2SqlSafetyService must not be null");
        this.aiModelSelectionService = Objects.requireNonNull(
            aiModelSelectionService,
            "aiModelSelectionService must not be null"
        );
        this.sqlRiskAnalysisService = Objects.requireNonNull(sqlRiskAnalysisService, "sqlRiskAnalysisService must not be null");
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
        onNavigateHome();
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
            throw new IllegalStateException("无法加载首页", error);
        }
    }

    /**
     * 导航到 SQL 练习页：高亮当前按钮并将 SQL 练习视图放入右侧容器。
     * 若子页面加载失败，不切换页面，避免空白或崩溃。
     */
    @FXML
    private void onNavigateSqlPractice() {
        selectNav(sqlPracticeNavButton);
        try {
            showPage(sqlPracticePage());
        } catch (RuntimeException error) {
            // 子页面加载失败时保持原页面，由调用方日志或后续错误区处理。
            throw new IllegalStateException("无法加载 SQL 练习页", error);
        }
    }

    /**
     * 导航到表结构页：高亮当前按钮并将表结构视图放入右侧容器。
     * 若子页面加载失败，不切换页面，避免空白或崩溃。
     */
    @FXML
    private void onNavigateTableSchema() {
        selectNav(tableSchemaNavButton);
        try {
            showPage(tableSchemaPage());
        } catch (RuntimeException error) {
            throw new IllegalStateException("无法加载表结构浏览页", error);
        }
    }

    /**
     * 导航到 AI 助手页：高亮当前按钮并将 AI 助手视图放入右侧容器。
     * 若子页面加载失败，不切换页面，避免空白或崩溃。
     */
    @FXML
    private void onNavigateAiAssistant() {
        selectNav(aiAssistantNavButton);
        try {
            showPage(aiAssistantPage());
        } catch (RuntimeException error) {
            throw new IllegalStateException("无法加载 AI 助手页", error);
        }
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
        return List.of(homeNavButton, aiAssistantNavButton, tableSchemaNavButton, sqlPracticeNavButton);
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
        FXMLLoader loader = new FXMLLoader(fxml);
        loader.setControllerFactory(type -> {
            if (type == SqlPracticeController.class) {
                sqlPracticeController = new SqlPracticeController(sqlExecutionService, sqlRiskAnalysisService);
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
        FXMLLoader loader = new FXMLLoader(fxml);
        loader.setControllerFactory(type -> {
            if (type == TableSchemaController.class) {
                tableSchemaController = new TableSchemaController(databaseMetadataService, sqlExecutionService, fillSqlCallback);
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
        FXMLLoader loader = new FXMLLoader(fxml);
        loader.setControllerFactory(type -> {
            if (type == AiAssistantController.class) {
                return new AiAssistantController(
                    nl2SqlSafetyService,
                    aiModelSelectionService,
                    sqlRiskAnalysisService,
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

    /**
     * 加载 {@code home.fxml} 并内嵌到右侧插槽。
     */
    private Node loadHomePage() {
        URL fxml = MainWindowController.class.getResource(HOME_FXML);
        if (fxml == null) {
            throw new IllegalStateException("Missing FXML resource on classpath: " + HOME_FXML);
        }
        FXMLLoader loader = new FXMLLoader(fxml);
        loader.setControllerFactory(type -> {
            if (type == HomeController.class) {
                HomeController controller = new HomeController();
                controller.setOnNavigateAiAssistant(this::onNavigateAiAssistant);
                controller.setOnNavigateSqlPractice(this::onNavigateSqlPractice);
                controller.setOnNavigateTableSchema(this::onNavigateTableSchema);
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
}
