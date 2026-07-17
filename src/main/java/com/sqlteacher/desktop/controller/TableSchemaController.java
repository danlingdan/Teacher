package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.execution.SqlExecutionRequest;
import com.sqlteacher.application.execution.SqlExecutionResult;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.desktop.DesktopExecutors;
import com.sqlteacher.desktop.GlobalLoading;
import com.sqlteacher.desktop.viewmodel.ColumnMetaViewModel;
import com.sqlteacher.desktop.viewmodel.DesktopConnections;
import com.sqlteacher.desktop.viewmodel.SqlExecutionViewModel;
import com.sqlteacher.desktop.viewmodel.SqlResultRowViewModel;
import com.sqlteacher.desktop.viewmodel.TableMetaViewModel;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 表结构浏览子页面控制器：左侧树形展示库/表/字段，右侧即时预览选中表数据。
 *
 * <p><b>依赖注入</b>：构造注入应用层 {@link DatabaseMetadataService}、{@link SqlExecutionService}
 * 与一个 {@link Consumer} 回调。回调由 {@code MainWindowController} 提供，用于在用户点击表名时
 * 把 {@code "SELECT * FROM 表名"} 同步填充到 SQL 练习页输入框（不跳转页面，不影响右侧即时预览）。
 *
 * <p><b>线程模型</b>：所有耗时操作（元数据加载、数据预览查询）均通过 {@link Task} 提交到共享守护线程池
 * {@code DesktopExecutors.background()}，绝不阻塞 JavaFX Application Thread。
 * 成功 / 失败回调均通过 {@link Platform#runLater(Runnable)} 切回 FX 线程更新 UI。
 * 同时调用 {@link GlobalLoading} 显示 / 隐藏全局 Loading 遮罩。
 *
 * <p><b>树形结构</b>：
 * <ul>
 *   <li>数据库根节点：{@code 🗄 demo 数据库}；</li>
 *   <li>表节点：{@code 📊 表名}；</li>
 *   <li>字段节点：{@code 📋 字段名 · 类型 [PK] [NOT NULL]}，主键字段使用 🔑 图标。</li>
 * </ul>
 *
 * <p><b>懒加载</b>：根节点初始带一个占位子节点；用户展开根节点时后台请求元数据，
 * 成功后替换为真实表节点。表节点下的字段数据由首次加载时一并缓存，展开表节点时直接渲染。
 *
 * <p><b>数据预览</b>：点击左侧表节点时，当前页面右侧自动执行
 * {@code SELECT * FROM 表名 LIMIT 100} 并展示前 100 行；同时可选把相同 SQL 填充到 SQL 练习页。
 */
public final class TableSchemaController {

    /** 树根标签：展示 demo 数据库的层级根节点。 */
    private static final String ROOT_LABEL = "demo 数据库";

    /** 懒加载占位子节点文案。 */
    private static final String LOADING_CHILD_LABEL = "加载中…";

    /** 表结构加载中占位文案。 */
    private static final String SCHEMA_LOADING_MESSAGE = "正在加载表结构…";

    /** 数据预览加载中占位文案。 */
    private static final String PREVIEW_LOADING_MESSAGE = "正在加载数据预览…";

    /** 预览空态占位文案。 */
    private static final String PREVIEW_EMPTY_MESSAGE = "点击左侧数据表预览数据";

    /** 表结构空态占位文案。 */
    private static final String EMPTY_MESSAGE = "暂无表结构数据";

    /** 错误兜底文案。 */
    private static final String FALLBACK_ERROR_MESSAGE = "加载失败";

    /** 预览查询默认返回行数上限。 */
    private static final int PREVIEW_MAX_ROWS = 100;

    /** 预览查询默认执行超时。 */
    private static final Duration PREVIEW_TIMEOUT = Duration.ofSeconds(5);

    /** 错误弹窗标题。 */
    private static final String ERROR_ALERT_TITLE = "加载失败";

    /** 节点图标。 */
    private static final String ICON_DATABASE = "🗄";
    private static final String ICON_TABLE = "📊";
    private static final String ICON_COLUMN = "📋";
    private static final String ICON_PRIMARY_KEY = "🔑";

    /** 应用层表元数据服务接口。 */
    private final DatabaseMetadataService metadataService;

    /** 应用层 SQL 执行服务接口。 */
    private final SqlExecutionService sqlExecutionService;

    /** 表名选中回调：把 {@code "SELECT * FROM 表名"} 传回主窗口控制器联动 SQL 练习页。 */
    private final Consumer<String> onTableSelected;

    /** 树形控件，分层展示 库 → 表 → 字段。 */
    @FXML
    private TreeView<String> schemaTree;

    /** 树形区空态 / 加载态占位 Label。 */
    @FXML
    private Label emptyPlaceholder;

    /** 树形区底部错误提示 Label。 */
    @FXML
    private Label errorLabel;

    /** 刷新按钮：点击重新加载表元数据。 */
    @FXML
    private Button refreshButton;

    /** 右侧数据预览表格。 */
    @FXML
    private TableView<SqlResultRowViewModel> previewTable;

    /** 右侧预览区空态 / 加载态占位 Label。 */
    @FXML
    private Label previewPlaceholder;

    /** 右侧预览区错误提示 Label。 */
    @FXML
    private Label previewErrorLabel;

    /** 右侧预览区状态 Label（行数 / 耗时）。 */
    @FXML
    private Label previewStatusLabel;

    /** 右侧预览区标题 Label（显示当前预览的表名）。 */
    @FXML
    private Label previewTitleLabel;

    /** 树根节点引用。 */
    private TreeItem<String> rootItem;

    /** 表名 → 字段列表缓存，用于表节点展开时同步渲染字段子节点。 */
    private final Map<String, List<ColumnMetaViewModel>> tableColumnCache = new HashMap<>();

    /**
     * 构造注入元数据服务、SQL 执行服务与表名选中回调。
     *
     * @param metadataService    应用层表元数据服务接口，不可为 {@code null}
     * @param sqlExecutionService 应用层 SQL 执行服务接口，不可为 {@code null}
     * @param onTableSelected    表名选中回调，参数为完整 SQL 语句；可为 {@code null} 表示不联动
     */
    public TableSchemaController(DatabaseMetadataService metadataService,
                                 SqlExecutionService sqlExecutionService,
                                 Consumer<String> onTableSelected) {
        this.metadataService = metadataService;
        this.sqlExecutionService = sqlExecutionService;
        this.onTableSelected = onTableSelected;
    }

    /**
     * FXML 控件注入完成后由 JavaFX 自动回调。初始化树根、注册展开与选择监听器、
     * 首次懒加载元数据（根节点默认展开时触发），并配置预览表格列宽策略。
     */
    @FXML
    private void initialize() {
        rootItem = createDatabaseRootItem();
        schemaTree.setRoot(rootItem);
        schemaTree.setShowRoot(true);

        // 展开根节点时执行懒加载：首次展开或手动刷新时从后台加载真实数据。
        rootItem.expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
            if (isExpanded && needsLoad(rootItem)) {
                loadMetadata();
            }
        });

        // 选择监听器：仅当选中表节点（父节点为根节点）时触发右侧预览与 SQL 练习页联动。
        schemaTree.getSelectionModel().selectedItemProperty().addListener((obs, oldSel, newSel) -> {
            if (newSel != null && newSel.getParent() == rootItem) {
                String tableName = stripIcon(newSel.getValue());
                String sql = "SELECT * FROM " + tableName + " LIMIT " + PREVIEW_MAX_ROWS;
                loadPreview(tableName, sql);
                if (onTableSelected != null) {
                    onTableSelected.accept(sql);
                }
            }
        });

        // 预览表格列宽自适应：按内容自动分配。
        previewTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        // 绑定宽度变化监听，窗口拉伸时自动重新均分列宽。
        previewTable.widthProperty().addListener((obs, oldWidth, newWidth) -> {
            Platform.runLater(() -> {
                double availableWidth = previewTable.getWidth() - 20;
                List<TableColumn<SqlResultRowViewModel, ?>> columns = previewTable.getColumns();
                if (!columns.isEmpty() && availableWidth > 0) {
                    double columnWidth = availableWidth / columns.size();
                    for (TableColumn<SqlResultRowViewModel, ?> col : columns) {
                        col.setPrefWidth(columnWidth);
                    }
                }
                previewTable.layout();
            });
        });

        // 默认展开根节点以触发首次加载。
        rootItem.setExpanded(true);
    }

    /**
     * 刷新按钮点击回调：清空当前树数据并重新加载表元数据。
     */
    @FXML
    private void onRefreshSchema() {
        refreshTableSchema();
    }

    /**
     * 刷新表结构：清空当前树数据并重新加载表元数据。
     * 供外部调用（如DDL执行成功后触发刷新）。
     */
    public void refreshTableSchema() {
        rootItem.getChildren().clear();
        rootItem.getChildren().add(createLoadingPlaceholderItem());
        loadMetadata();
    }

    /**
     * 异步加载表元数据：Task 提交到共享后台线程池，成功 / 失败回调切回 FX 线程。
     * 自动唤起全局 Loading 遮罩，并在完成或失败时强制关闭。
     */
    private void loadMetadata() {
        showSchemaLoadingState();
        refreshButton.setDisable(true);
        GlobalLoading.show(SCHEMA_LOADING_MESSAGE);

        Task<List<TableMetaViewModel>> loadTask = new Task<>() {
            @Override
            protected List<TableMetaViewModel> call() {
                return metadataService.listTables(DesktopConnections.DEMO).stream()
                    .map(TableMetaViewModel::from)
                    .toList();
            }
        };
        loadTask.setOnSucceeded(event -> Platform.runLater(() -> {
            try {
                List<TableMetaViewModel> tables = loadTask.getValue();
                if (tables.isEmpty()) {
                    showSchemaEmptyState();
                } else {
                    renderTables(tables);
                }
            } finally {
                refreshButton.setDisable(false);
                GlobalLoading.hide();
            }
        }));
        loadTask.setOnFailed(event -> Platform.runLater(() -> {
            Throwable error = loadTask.getException();
            String message = readableThrowable(error);
            showSchemaErrorState(message);
            refreshButton.setDisable(false);
            GlobalLoading.forceHide();
            showErrorAlert(message);
        }));

        DesktopExecutors.background().execute(loadTask);
    }

    /**
     * 异步加载选中表的数据预览：后台执行 {@code SELECT * FROM 表名 LIMIT 100}，
     * 成功后右侧展示表格，失败时右侧展示友好错误提示。
     *
     * @param tableName 要预览的表名
     * @param sql       实际执行的 SQL 语句
     */
    private void loadPreview(String tableName, String sql) {
        showPreviewLoadingState(tableName);
        GlobalLoading.show(PREVIEW_LOADING_MESSAGE);

        SqlExecutionRequest request = new SqlExecutionRequest(
            DesktopConnections.DEMO, sql, PREVIEW_MAX_ROWS, PREVIEW_TIMEOUT
        );

        Task<SqlExecutionViewModel> previewTask = new Task<>() {
            @Override
            protected SqlExecutionViewModel call() {
                SqlExecutionResult result = sqlExecutionService.execute(request);
                return SqlExecutionViewModel.from(request, result);
            }
        };
        previewTask.setOnSucceeded(event -> Platform.runLater(() -> {
            try {
                renderPreview(tableName, previewTask.getValue());
            } finally {
                GlobalLoading.hide();
            }
        }));
        previewTask.setOnFailed(event -> Platform.runLater(() -> {
            try {
                showPreviewErrorState(tableName, readableThrowable(previewTask.getException()));
            } finally {
                GlobalLoading.forceHide();
            }
        }));

        DesktopExecutors.background().execute(previewTask);
    }

    /**
     * NORMAL 渲染：构建 库 → 表 → 字段 的三级树并展开。
     *
     * @param tables 表元数据视图模型列表
     */
    private void renderTables(List<TableMetaViewModel> tables) {
        rootItem.getChildren().clear();
        for (TableMetaViewModel table : tables) {
            TreeItem<String> tableItem = createTableItem(table);
            rootItem.getChildren().add(tableItem);
        }
        rootItem.setExpanded(true);

        setNodeVisible(schemaTree, true);
        setNodeVisible(emptyPlaceholder, false);
        setNodeVisible(errorLabel, false);
    }

    /** 创建数据库根节点，带有一个占位子节点用于触发懒加载。 */
    private TreeItem<String> createDatabaseRootItem() {
        TreeItem<String> root = new TreeItem<>(withIcon(ICON_DATABASE, ROOT_LABEL));
        root.setExpanded(false);
        root.getChildren().add(createLoadingPlaceholderItem());
        return root;
    }

    /** 创建懒加载占位子节点。 */
    private TreeItem<String> createLoadingPlaceholderItem() {
        return new TreeItem<>(withIcon("", LOADING_CHILD_LABEL));
    }

    /** 创建表节点，并预先把字段数据缓存到控制器字段中供展开时渲染。 */
    private TreeItem<String> createTableItem(TableMetaViewModel table) {
        String tableName = table.name();
        tableColumnCache.put(tableName, table.columns());

        TreeItem<String> tableItem = new TreeItem<>(withIcon(ICON_TABLE, tableName));
        tableItem.getChildren().add(createLoadingPlaceholderItem());

        // 表节点展开时：如果子节点仍是占位，则从缓存取出字段列表并渲染。
        tableItem.expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
            if (isExpanded && hasPlaceholderChild(tableItem)) {
                List<ColumnMetaViewModel> columns = tableColumnCache.getOrDefault(tableName, List.of());
                renderColumns(tableItem, columns);
            }
        });
        return tableItem;
    }

    /** 用缓存的字段数据渲染表节点下的字段子节点。 */
    private void renderColumns(TreeItem<String> tableItem, List<ColumnMetaViewModel> columns) {
        tableItem.getChildren().clear();
        for (ColumnMetaViewModel column : columns) {
            String icon = column.primaryKey() ? ICON_PRIMARY_KEY : ICON_COLUMN;
            String label = withIcon(icon, formatColumn(column));
            TreeItem<String> columnItem = new TreeItem<>(label);
            columnItem.setGraphic(null);
            tableItem.getChildren().add(columnItem);
        }
    }

    /** 判断节点是否只包含懒加载占位子节点。 */
    private boolean hasPlaceholderChild(TreeItem<String> item) {
        return item.getChildren().size() == 1
            && item.getChildren().get(0).getValue().contains(LOADING_CHILD_LABEL);
    }

    /** 判断根节点是否需要加载真实数据。 */
    private boolean needsLoad(TreeItem<String> item) {
        return item.getChildren().isEmpty()
            || (item.getChildren().size() == 1 && hasPlaceholderChild(item));
    }

    /** 格式化字段显示文本：{@code name · typeName [PK] [NOT NULL]}（标记按需追加）。 */
    private static String formatColumn(ColumnMetaViewModel column) {
        StringBuilder sb = new StringBuilder(column.name())
            .append(" · ").append(column.typeName());
        if (column.primaryKey()) {
            sb.append(" [PK]");
        }
        if (!column.nullable()) {
            sb.append(" [NOT NULL]");
        }
        return sb.toString();
    }

    /** 在文本前追加图标与间隔。 */
    private static String withIcon(String icon, String text) {
        if (icon == null || icon.isBlank()) {
            return text;
        }
        return icon + " " + text;
    }

    /** 去掉图标前缀，提取纯文本（用于从表节点文本还原表名）。 */
    private static String stripIcon(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int firstSpace = value.indexOf(' ');
        if (firstSpace < 0 || firstSpace == value.length() - 1) {
            return value;
        }
        return value.substring(firstSpace + 1);
    }

    /** LOADING 渲染：树形区展示加载占位。 */
    private void showSchemaLoadingState() {
        rootItem.getChildren().clear();
        rootItem.getChildren().add(createLoadingPlaceholderItem());
        emptyPlaceholder.setText(SCHEMA_LOADING_MESSAGE);
        setNodeVisible(schemaTree, false);
        setNodeVisible(emptyPlaceholder, true);
        setNodeVisible(errorLabel, false);
    }

    /** EMPTY 渲染：树形区展示空态占位。 */
    private void showSchemaEmptyState() {
        rootItem.getChildren().clear();
        emptyPlaceholder.setText(EMPTY_MESSAGE);
        setNodeVisible(schemaTree, false);
        setNodeVisible(emptyPlaceholder, true);
        setNodeVisible(errorLabel, false);
    }

    /**
     * ERROR 渲染：树形区展示报错文案。
     *
     * @param message 报错文案
     */
    private void showSchemaErrorState(String message) {
        rootItem.getChildren().clear();
        rootItem.getChildren().add(createLoadingPlaceholderItem());
        errorLabel.setText(message);
        setNodeVisible(schemaTree, false);
        setNodeVisible(emptyPlaceholder, false);
        setNodeVisible(errorLabel, true);
    }

    /** 预览区 LOADING 渲染。 */
    private void showPreviewLoadingState(String tableName) {
        previewTitleLabel.setText(tableName.isBlank() ? "" : "· " + tableName);
        previewPlaceholder.setText(PREVIEW_LOADING_MESSAGE);
        clearPreviewTable();
        setNodeVisible(previewTable, false);
        setNodeVisible(previewPlaceholder, true);
        setNodeVisible(previewErrorLabel, false);
        setNodeVisible(previewStatusLabel, false);
    }

    /** 预览区 EMPTY 渲染。 */
    private void showPreviewEmptyState() {
        previewTitleLabel.setText("");
        previewPlaceholder.setText(PREVIEW_EMPTY_MESSAGE);
        clearPreviewTable();
        setNodeVisible(previewTable, false);
        setNodeVisible(previewPlaceholder, true);
        setNodeVisible(previewErrorLabel, false);
        setNodeVisible(previewStatusLabel, false);
    }

    /**
     * 预览区 ERROR 渲染。
     *
     * @param tableName 预览失败的表名
     * @param message   报错文案
     */
    private void showPreviewErrorState(String tableName, String message) {
        previewTitleLabel.setText(tableName.isBlank() ? "" : "· " + tableName);
        previewErrorLabel.setText(message);
        clearPreviewTable();
        setNodeVisible(previewTable, false);
        setNodeVisible(previewPlaceholder, false);
        setNodeVisible(previewErrorLabel, true);
        setNodeVisible(previewStatusLabel, false);
    }

    /** 渲染预览数据到右侧表格。 */
    private void renderPreview(String tableName, SqlExecutionViewModel viewModel) {
        previewTitleLabel.setText(tableName.isBlank() ? "" : "· " + tableName);
        if (!viewModel.success()) {
            showPreviewErrorState(tableName, viewModel.message());
            return;
        }
        if (viewModel.rowCount() == 0) {
            previewPlaceholder.setText("该表暂无数据");
            clearPreviewTable();
            setNodeVisible(previewTable, false);
            setNodeVisible(previewPlaceholder, true);
            setNodeVisible(previewErrorLabel, false);
            setNodeVisible(previewStatusLabel, false);
            return;
        }

        rebuildPreviewColumns(viewModel.columns());
        previewTable.setItems(FXCollections.observableArrayList(viewModel.rows()));
        previewTable.layout();
        setNodeVisible(previewTable, true);
        setNodeVisible(previewPlaceholder, false);
        setNodeVisible(previewErrorLabel, false);
        previewStatusLabel.setText(viewModel.truncated()
            ? "已显示前 " + viewModel.rowCount() + " 行，结果已按上限截断"
            : "共 " + viewModel.rowCount() + " 行 · " + viewModel.executionMillis() + " ms");
        setNodeVisible(previewStatusLabel, true);
    }

    /** 清空预览表格的列与数据行。 */
    private void clearPreviewTable() {
        previewTable.getColumns().clear();
        previewTable.getItems().clear();
    }

    /** 依据列名列表动态重建预览表格列。 */
    private void rebuildPreviewColumns(List<String> columns) {
        previewTable.getColumns().clear();
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
            previewTable.getColumns().add(column);
        }
    }

    /** 弹出错误提示 Alert（非阻塞，避免卡死 FX 线程）。 */
    private void showErrorAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(ERROR_ALERT_TITLE);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.getButtonTypes().setAll(javafx.scene.control.ButtonType.OK);
        alert.show();
    }

    /** 报错文案兜底：为空 / 空白时回退到默认文案。 */
    private static String readableError(String message) {
        return message == null || message.isBlank() ? FALLBACK_ERROR_MESSAGE : message;
    }

    /** 从后台任务抛出的异常提取可读文案。 */
    private static String readableThrowable(Throwable error) {
        return readableError(error == null ? null : error.getMessage());
    }

    /** 同步切换节点的可见性与布局占位（不可见时不参与布局，避免留白）。 */
    private static void setNodeVisible(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
