package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.execution.SqlExecutionRequest;
import com.sqlteacher.application.execution.SqlExecutionResult;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.desktop.viewmodel.DesktopConnections;
import com.sqlteacher.desktop.viewmodel.SqlExecutionViewModel;
import com.sqlteacher.desktop.viewmodel.SqlResultRowViewModel;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
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
 * SQL 练习子页面控制器（离线 Mock 开发阶段 · 第 4 步：输入 → 执行 → 三态渲染 + 底部错误提示区）。
 *
 * <p><b>依赖注入</b>：本控制器使用<strong>构造注入</strong>，依赖类型为应用层接口
 * {@link SqlExecutionService}。运行期注入的实现固定为桌面测试目录中的
 * {@code com.sqlteacher.desktop.mock.SqlExecutionMockService}（该 Mock 实现了本接口）。
 * 之所以按接口类型注入而非直接写 Mock 具体类型：Mock 位于 {@code src/test} 源集，
 * 主源码（{@code src/main}）在编译期不可见测试类，直接引用会导致编译失败；按接口注入
 * 既满足"构造注入 + 运行期只用 Mock 实现"，也符合 {@code desktop -> application} 的依赖方向。
 *
 * <p><b>三套渲染分支</b>（点击执行后按输入与 Mock 返回状态自动切换，全链路闭环）：
 * <ul>
 *   <li>NORMAL：动态依据结果列生成 {@link TableColumn} 并填充数据行，展开表格、隐藏占位与错误提示；</li>
 *   <li>EMPTY：清空表格，居中展示「暂无查询结果」占位，隐藏错误提示；</li>
 *   <li>ERROR：清空表格数据、隐藏占位，底部错误提示 Label 展示报错文案。</li>
 * </ul>
 *
 * <p><b>异常处理</b>（参见 {@link #onExecuteSql()}）：
 * <ul>
 *   <li>用户输入空白 / null 的 SQL：以 {@link IllegalArgumentException} 承载固定文案
 *       {@code "sql must not be blank"}，由下方 {@code catch (IllegalArgumentException)} 统一展示；</li>
 *   <li>Mock 后端类异常：{@code com.sqlteacher.desktop.mock.MockBackendException} 是
 *       {@link RuntimeException} 的子类，但其位于 {@code src/test} 源集，主源码无法按类型名
 *       编译期引用，故由 {@code catch (RuntimeException)} 以父类型统一捕获（多态捕获，等价于
 *       "专门捕获 MockBackendException"）；</li>
 *   <li>Mock 以 {@code success = false} 内联表达的失败（{@code SqlExecutionMockService} 的
 *       ERROR 场景不抛异常，而是返回失败 DTO）：在 {@link #renderExecution} 中转为 ERROR 展示。</li>
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

    /** SQL 执行服务（应用层接口）；运行期实现为 SqlExecutionMockService。 */
    private final SqlExecutionService sqlExecutionService;

    /** 多行 SQL 输入框，对应 FXML 中 fx:id="sqlInputArea"。 */
    @FXML
    private TextArea sqlInputArea;

    /** 执行按钮，对应 FXML 中 fx:id="executeSqlButton"（预留：执行期可临时禁用）。 */
    @FXML
    private Button executeSqlButton;

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

    /**
     * 构造注入 SQL 执行服务（运行期传入 {@code SqlExecutionMockService}）。
     *
     * @param sqlExecutionService 应用层 SQL 执行服务接口，不可为 {@code null}
     */
    public SqlPracticeController(SqlExecutionService sqlExecutionService) {
        this.sqlExecutionService = sqlExecutionService;
    }

    /**
     * FXML 控件注入完成后由 JavaFX 自动回调。
     * 配置表格列宽自适应策略，并进入初始空态（展示「暂无查询结果」、隐藏错误提示）。
     */
    @FXML
    private void initialize() {
        // 列宽自适应：在所有列之间均分表格宽度，随右侧主容器缩放自动铺满，无需为列写死宽度。
        resultTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        // 初始为空态：隐藏表格与错误提示、居中展示占位。
        showEmptyState();
    }

    /**
     * 「执行 SQL」按钮点击回调：读取输入 → 校验 → 调用 Mock 执行服务，
     * 依据输入与返回状态自动切换 NORMAL / EMPTY / ERROR 渲染分支，形成完整交互闭环。
     */
    @FXML
    private void onExecuteSql() {
        try {
            // 将 TextArea 输入绑定到局部字符串变量并去除首尾空白。
            String sql = sqlInputArea.getText() == null ? "" : sqlInputArea.getText().strip();
            // 分支①：空白 / null SQL —— 以非法参数异常承载固定文案，交由下方 catch 统一展示。
            if (sql.isEmpty()) {
                throw new IllegalArgumentException(BLANK_SQL_MESSAGE);
            }

            SqlExecutionRequest request = new SqlExecutionRequest(
                DesktopConnections.DEMO, sql, DEFAULT_MAX_ROWS, DEFAULT_TIMEOUT
            );
            SqlExecutionResult result = sqlExecutionService.execute(request);
            SqlExecutionViewModel viewModel = SqlExecutionViewModel.from(request, result);
            renderExecution(viewModel);
        } catch (IllegalArgumentException error) {
            // 分支①/参数类异常：空白 SQL 的固定文案，或 Mock 抛出的 IllegalArgumentException。
            showErrorState(error.getMessage());
        } catch (RuntimeException error) {
            // 分支②：Mock 后端类异常（含 MockBackendException —— 其为 RuntimeException 子类，
            // 位于 test 源集主源码无法按类型名引用，此处以父类型多态捕获），展示真实异常文案。
            showErrorState(error.getMessage());
        }
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
            sqlInputArea.setText(exampleSql);
            sqlInputArea.requestFocus();
            sqlInputArea.positionCaret(exampleSql.length());
        }
    }

    /**
     * 执行结果分发：按 success / rowCount 切换 NORMAL / EMPTY / ERROR 三套渲染分支。
     * {@code SqlExecutionMockService} 的 ERROR 场景以 {@code success = false} 内联表达失败，
     * 在此转为底部错误提示展示。
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
        showResultRows(viewModel.columns(), viewModel.rows());
    }

    /**
     * NORMAL 渲染：依据列名动态重建表格列并填充结果行，展开表格、隐藏占位与错误提示。
     */
    private void showResultRows(List<String> columns, List<SqlResultRowViewModel> rows) {
        rebuildColumns(columns);
        resultTable.setItems(FXCollections.observableArrayList(rows));
        setNodeVisible(resultTable, true);
        setNodeVisible(emptyPlaceholder, false);
        setNodeVisible(errorLabel, false);
    }

    /**
     * EMPTY 渲染：清空表格并隐藏之与错误提示，居中展示「暂无查询结果」占位。
     */
    private void showEmptyState() {
        clearTable();
        setNodeVisible(resultTable, false);
        setNodeVisible(emptyPlaceholder, true);
        setNodeVisible(errorLabel, false);
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

    /** 同步切换节点的可见性与布局占位（不可见时不参与布局，避免留白）。 */
    private void setNodeVisible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
