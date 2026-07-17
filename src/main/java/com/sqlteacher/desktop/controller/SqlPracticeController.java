package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.execution.SqlExecutionRequest;
import com.sqlteacher.application.execution.SqlExecutionResult;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.desktop.DesktopExecutors;
import com.sqlteacher.desktop.GlobalLoading;
import com.sqlteacher.desktop.SqlRiskConfirmDialogUtil;
import com.sqlteacher.desktop.viewmodel.DesktopConnections;
import com.sqlteacher.desktop.viewmodel.SqlExecutionViewModel;
import com.sqlteacher.desktop.viewmodel.SqlResultRowViewModel;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;

import java.time.Duration;
import java.util.List;

/**
 * SQL 练习子页面控制器：输入 → 后台执行 → 结果、空态或错误状态渲染。
 *
 * <p><b>依赖注入</b>：本控制器使用<strong>构造注入</strong>，依赖类型为应用层接口
 * {@link SqlExecutionService}。运行期由桌面启动器从 Spring Context 获取真实实现并注入，
 * 控制器不依赖具体 JDBC 类型。
 *
 * <p><b>三套渲染分支</b>（点击执行后按服务返回状态自动切换）：
 * <ul>
 *   <li>NORMAL：动态依据结果列生成 {@link TableColumn} 并填充数据行，展开表格、隐藏占位与错误提示；</li>
 *   <li>EMPTY：清空表格，居中展示「暂无查询结果」占位，隐藏错误提示；</li>
 *   <li>ERROR：清空表格数据、隐藏占位，底部错误提示 Label 展示报错文案。</li>
 * </ul>
 *
 * <p><b>线程模型</b>（参见 {@link #onExecuteSql()}）：
 * SQL 执行放到共享后台守护线程池（{@code DesktopExecutors.background()}）上的
 * {@link javafx.concurrent.Task} 执行，点击后立即禁用按钮并展示 loading；成功 / 失败回调通过
 * {@link javafx.application.Platform#runLater(Runnable)} 切回 FX Application Thread 刷新表格 /
 * 错误提示并恢复按钮，杜绝耗时操作冻结窗口。
 *
 * <p><b>异常处理</b>（参见 {@link #onExecuteSql()}）：
 * <ul>
 *   <li>用户输入空白 / null 的 SQL：在 FX 线程前置校验，直接进入 ERROR 态展示固定文案
 *       {@code "sql must not be blank"}，不启动后台任务；</li>
 *   <li>Mock 后端类异常：{@code com.sqlteacher.desktop.mock.MockBackendException} 是
 *       {@link RuntimeException} 的子类，在后台 {@code Task.call()} 中抛出后由
 *       {@code Task.setOnFailed} 回调统一取 {@code task.getException()} 展示报错文案；</li>
 *   <li>Mock 以 {@code success = false} 内联表达的失败（{@code SqlExecutionMockService} 的
 *       ERROR 场景与安全校验拦截不抛异常，而是返回失败 DTO）：在 {@link #renderExecution} 中转为 ERROR 展示。</li>
 * </ul>
 *
 * <p>空态占位采用独立 StackPane + Label 叠加实现，不依赖 TableView 内置 placeholder，
 * 从而稳定显示「暂无查询结果」，规避 JavaFX 在零列时显示「表中无列」的内置文案。
 */
public final class SqlPracticeController {

    /** 演示环境默认返回行数上限，仅用于构造执行请求。 */
    private static final int DEFAULT_MAX_ROWS = 100;

    /** 演示环境默认执行超时，仅用于构造执行请求。 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    /** 空白 / null SQL 的固定错误文案（分支①）。 */
    private static final String BLANK_SQL_MESSAGE = "sql must not be blank";

    /** 错误文案兜底：异常 message 为空时的默认展示文案。 */
    private static final String FALLBACK_ERROR_MESSAGE = "SQL 执行失败";

    /** 空态占位文案（与 SqlPractice.fxml 中 emptyPlaceholder 初始文本保持一致）。 */
    private static final String EMPTY_PLACEHOLDER_MESSAGE = "暂无查询结果";

    /** 执行中占位文案（后台执行期间展示 loading）。 */
    private static final String LOADING_MESSAGE = "正在执行SQL，请稍候…";

    /** SQL 执行服务（应用层接口）；运行期实现由 Spring Context 提供。 */
    private final SqlExecutionService sqlExecutionService;

    /** 多行 SQL 输入框，对应 FXML 中 fx:id="sqlInputArea"。 */
    @FXML
    private TextArea sqlInputArea;

    /** 执行按钮，对应 FXML 中 fx:id="executeSqlButton"（预留：执行期可临时禁用）。 */
    @FXML
    private Button executeSqlButton;

    /** 清空按钮，对应 FXML 中 fx:id="clearSqlBtn"。 */
    @FXML
    private Button clearSqlBtn;

    /**
     * 结果表格，对应 fx:id="resultTable"。行类型为 {@link SqlResultRowViewModel}；
     * 列在运行期依据结果列名动态生成。NORMAL 显示，EMPTY / ERROR / 初始隐藏。
     */
    @FXML
    private TableView<SqlResultRowViewModel> resultTable;

    /** 空态占位 Label，对应 fx:id="emptyPlaceholder"；仅 EMPTY / 初始可见。 */
    @FXML
    private Label emptyPlaceholder;

    /** 底部错误提示 Label，对应 fx:id="errorLabel"；仅 ERROR 场景可见。 */
    @FXML
    private Label errorLabel;

    /** 成功结果摘要；用于显示行数、耗时和截断提示。 */
    @FXML
    private Label resultStatusLabel;

    /** DDL执行成功后回调，用于刷新表结构。 */
    private Runnable onDdlSuccessCallback;

    /**
     * 构造注入 SQL 执行服务。
     *
     * @param sqlExecutionService 应用层 SQL 执行服务接口，不可为 {@code null}
     */
    public SqlPracticeController(SqlExecutionService sqlExecutionService) {
        this.sqlExecutionService = sqlExecutionService;
    }

    /**
     * 设置DDL执行成功后的回调。
     *
     * @param callback DDL执行成功后要执行的回调
     */
    public void setOnDdlSuccessCallback(Runnable callback) {
        this.onDdlSuccessCallback = callback;
    }

    /**
     * FXML 控件注入完成后由 JavaFX 自动回调。
     * 配置表格列宽自适应策略，并进入初始空态（展示「暂无查询结果」、隐藏错误提示）。
     */
    @FXML
    private void initialize() {
        // 列宽自适应：在所有列之间均分表格宽度，随右侧主容器缩放自动铺满，无需为列写死宽度。
        resultTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        // 绑定宽度变化监听，窗口拉伸时自动重新均分列宽。
        resultTable.widthProperty().addListener((obs, oldWidth, newWidth) -> {
            Platform.runLater(() -> resultTable.layout());
        });

        // 初始为空态：隐藏表格与错误提示、居中展示占位。
        showEmptyState();
    }

    /**
     * 「执行 SQL」按钮点击回调：读取输入 → 校验 → 后台线程执行 → 回 UI 线程三态渲染。
     *
     * <p>严禁在 FX Application Thread 上同步调用
     * {@code sqlExecutionService.execute(request)}（数据库 / AI 等潜在耗时操作会冻结窗口）。
     * 改造要点：
     * <ol>
     *   <li>FX 线程先做空白校验：空 SQL 立即进入错误态并返回，不启动后台任务；</li>
     *   <li>点击后立即禁用执行按钮、展示 loading 占位，避免重复点击与界面误解；</li>
     *  <li>用 {@link Task} + {@code DesktopExecutors.background()} 把执行放到后台子线程；
     *     Task 重写方法内部只做业务调用，绝不触碰 UI 控件；</li>
     *   <li>成功 / 失败回调统一用 {@link Platform#runLater(Runnable)} 切回 FX 线程更新 UI：
     *       渲染结果表格、展示错误提示、恢复按钮可用。</li>
     * </ol>
     */
    @FXML
    private void onExecuteSql() {
        // ① FX 线程内的前置空白校验：空白 / null SQL 立即进入错误态，不启动后台任务。
        String sql = sqlInputArea.getText() == null ? "" : sqlInputArea.getText().strip();
        if (sql.isEmpty()) {
            showErrorState(BLANK_SQL_MESSAGE);
            return;
        }

        // ② 高危SQL风险检查：命中高危规则弹出二次确认弹窗。
        String riskType = SqlRiskConfirmDialogUtil.checkRisk(sql);
        if (riskType != null) {
            SqlRiskConfirmDialogUtil.showRiskConfirmDialog(sql, () -> executeSqlInternal(sql));
            return;
        }

        // ③ 低风险SQL直接执行。
        executeSqlInternal(sql);
    }

    /**
     * 内部执行SQL方法：进入执行中状态、创建后台任务、处理成功/失败回调。
     */
    private void executeSqlInternal(String sql) {
        // ① 立即进入执行中：禁用按钮、展示 loading 占位，并唤起全局 Loading 遮罩。
        setExecuting(true);
        showLoadingState();
        GlobalLoading.show(LOADING_MESSAGE);

        SqlExecutionRequest request = new SqlExecutionRequest(
            DesktopConnections.DEMO, sql, DEFAULT_MAX_ROWS, DEFAULT_TIMEOUT
        );

        // ② 后台任务：call() 在子线程执行，仅做业务调用与 ViewModel 转换，不触碰任何 UI 控件。
        Task<SqlExecutionViewModel> executionTask = new Task<>() {
            @Override
            protected SqlExecutionViewModel call() {
                SqlExecutionResult result = sqlExecutionService.execute(request);
                return SqlExecutionViewModel.from(request, result);
            }
        };
        // ③ 成功 / 失败回调均通过 Platform.runLater 显式切回 FX 线程刷新 UI 并恢复按钮。
        executionTask.setOnSucceeded(event -> Platform.runLater(() -> {
            try {
                renderExecution(executionTask.getValue());
                if (isDdlStatement(sql) && onDdlSuccessCallback != null) {
                    onDdlSuccessCallback.run();
                }
            } finally {
                setExecuting(false);
                GlobalLoading.hide();
            }
        }));
        executionTask.setOnFailed(event -> Platform.runLater(() -> {
            try {
                // 后端类异常（含 MockBackendException，RuntimeException 子类）经 Task 传播到此。
                showErrorState(readableThrowable(executionTask.getException()));
            } finally {
                setExecuting(false);
                GlobalLoading.forceHide();
            }
        }));

        DesktopExecutors.background().execute(executionTask);
    }

    /**
     * 「示例 SQL」标签点击回调：把被点击标签按钮的文本填充到 SQL 输入框，方便一键试运行。
     *
     * <p>仅做输入填充与光标定位，<strong>不</strong>触发执行，也不改动任何三态渲染或异常
     * 捕获逻辑；执行仍由用户点击「执行 SQL」按钮走 {@link #onExecuteSql()} 原有链路。
     * 多个示例标签共用本处理器，通过事件源按钮的文本区分具体 SQL。
     *
     * @param event 标签按钮的动作事件，事件源为触发点击的示例标签 {@link Button}
     */
    @FXML
    private void onFillExampleSql(ActionEvent event) {
        if (event.getSource() instanceof Button chip) {
            String exampleSql = chip.getText();
            fillSql(exampleSql);
        }
    }

    /**
     * 「清空」按钮点击回调：清空 SQL 输入区内容，同时清空查询结果表格、状态提示、错误提示，
     * 恢复到初始空态，输入框自动获取焦点。
     */
    @FXML
    private void onClearSql() {
        sqlInputArea.clear();
        showEmptyState();
        errorLabel.setText("");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        sqlInputArea.requestFocus();
    }

    /**
     * 把指定 SQL 填充到输入框并定位光标到末尾。
     *
     * <p>供 {@link MainWindowController} 在表结构页点击表名时联动调用：把
     * {@code "SELECT * FROM 表名"} 填入输入框，实现表结构页 → SQL 练习页的双向联动。
     * 仅做输入填充与光标定位，不触发执行。
     *
     * @param sql 要填充的 SQL 语句，不可为 {@code null}
     */
    public void fillSql(String sql) {
        sqlInputArea.setText(sql);
        sqlInputArea.requestFocus();
        sqlInputArea.positionCaret(sql.length());
    }

    /**
     * 执行结果分发：按 success / rowCount 切换 NORMAL / EMPTY / ERROR 三套渲染分支。
     * 服务以 {@code success = false} 内联表达的失败在此转为底部错误提示展示。
     */
    private void renderExecution(SqlExecutionViewModel viewModel) {
        if (!viewModel.success()) {
            showErrorState(viewModel.message());
            return;
        }
        if (viewModel.rowCount() == 0) {
            showEmptyState();
            return;
        }
        showResultRows(viewModel);
    }

    /**
     * NORMAL 渲染：依据列名动态重建表格列并填充结果行，展开表格、隐藏占位与错误提示。
     */
    private void showResultRows(SqlExecutionViewModel viewModel) {
        rebuildColumns(viewModel.columns());
        resultTable.setItems(FXCollections.observableArrayList(viewModel.rows()));
        resultTable.layout();
        setNodeVisible(resultTable, true);
        setNodeVisible(emptyPlaceholder, false);
        setNodeVisible(errorLabel, false);
        resultStatusLabel.setText(viewModel.truncated()
            ? "已显示前 " + viewModel.rowCount() + " 行，结果已按上限截断"
            : "共 " + viewModel.rowCount() + " 行 · " + viewModel.executionMillis() + " ms");
        setNodeVisible(resultStatusLabel, true);
    }

    /**
     * EMPTY 渲染：清空表格并隐藏之与错误提示，居中展示「暂无查询结果」占位。
     * 显式复位占位文案，避免残留上一次的 loading 文案。
     */
    private void showEmptyState() {
        clearTable();
        setNodeVisible(resultTable, false);
        emptyPlaceholder.setText(EMPTY_PLACEHOLDER_MESSAGE);
        setNodeVisible(emptyPlaceholder, true);
        setNodeVisible(errorLabel, false);
        setNodeVisible(resultStatusLabel, false);
    }

    /**
     * LOADING 渲染：后台执行期间清空表格、隐藏错误提示，
     * 复用占位 Label 展示「正在执行查询…」。
     */
    private void showLoadingState() {
        clearTable();
        setNodeVisible(resultTable, false);
        emptyPlaceholder.setText(LOADING_MESSAGE);
        setNodeVisible(emptyPlaceholder, true);
        setNodeVisible(errorLabel, false);
        setNodeVisible(resultStatusLabel, false);
    }

    /**
     * 切换执行中状态：执行期间禁用按钮防止重复点击，完成后恢复。
     */
    private void setExecuting(boolean executing) {
        executeSqlButton.setDisable(executing);
    }

    /**
     * ERROR 渲染：清空表格数据、隐藏表格与空态占位，底部错误提示 Label 展示报错文案。
     *
     * @param message 报错文案（来自 IllegalArgumentException / RuntimeException 或失败 DTO），
     *                为空时回退到 {@link #FALLBACK_ERROR_MESSAGE}
     */
    private void showErrorState(String message) {
        clearTable();
        setNodeVisible(resultTable, false);
        setNodeVisible(emptyPlaceholder, false);
        errorLabel.setText(readableError(message));
        setNodeVisible(errorLabel, true);
        setNodeVisible(resultStatusLabel, false);
    }

    /** 清空表格的列与数据行（切换到 EMPTY / ERROR 前调用）。 */
    private void clearTable() {
        resultTable.getColumns().clear();
        resultTable.getItems().clear();
    }

    /**
     * 依据列名列表动态重建表格列。每列使用属性工厂读取
     * {@link SqlResultRowViewModel#cells()} 中与列序对齐的显示字符串，
     * 字段命名严格匹配 ViewModel 原有定义（{@code cells}）。
     */
    private void rebuildColumns(List<String> columns) {
        resultTable.getColumns().clear();
        for (int i = 0; i < columns.size(); i++) {
            final int columnIndex = i;
            TableColumn<SqlResultRowViewModel, String> column = new TableColumn<>(columns.get(i));
            column.setMinWidth(75);
            column.setMaxWidth(Double.MAX_VALUE);
            column.setCellValueFactory(cellData -> {
                List<String> cells = cellData.getValue().cells();
                String text = columnIndex < cells.size() ? cells.get(columnIndex) : "";
                return new ReadOnlyStringWrapper(text);
            });
            resultTable.getColumns().add(column);
        }
    }

    /** 报错文案兜底：为空 / 空白时回退到默认文案，避免展示空的错误框。 */
    private static String readableError(String message) {
        return message == null || message.isBlank() ? FALLBACK_ERROR_MESSAGE : message;
    }

    /** 从后台任务抛出的异常提取可读文案（Task 失败回调使用）。 */
    private static String readableThrowable(Throwable error) {
        return readableError(error == null ? null : error.getMessage());
    }

    /** 判断SQL语句是否为DDL语句（CREATE TABLE/ALTER/DROP/TRUNCATE）。 */
    private static boolean isDdlStatement(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        String upper = sql.toUpperCase().trim();
        return upper.startsWith("CREATE TABLE")
                || upper.startsWith("ALTER TABLE")
                || upper.startsWith("DROP TABLE")
                || upper.startsWith("TRUNCATE TABLE")
                || upper.startsWith("TRUNCATE")
                || upper.startsWith("CREATE INDEX")
                || upper.startsWith("DROP INDEX");
    }

    /** 同步切换节点的可见性与布局占位（不可见时不参与布局，避免留白）。 */
    private static void setNodeVisible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
