package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.ai.AiModelSelection;
import com.sqlteacher.application.ai.AiModelSelectionService;
import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.nl2sql.Nl2SqlPlan;
import com.sqlteacher.application.nl2sql.Nl2SqlRequest;
import com.sqlteacher.application.nl2sql.Nl2SqlSafetyResult;
import com.sqlteacher.application.nl2sql.Nl2SqlSafetyService;
import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.application.risk.SqlRiskLevel;
import com.sqlteacher.desktop.DesktopExecutors;
import com.sqlteacher.desktop.GlobalLoading;
import com.sqlteacher.desktop.viewmodel.DesktopConnections;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.Parent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * AI 助手页面控制器：接收用户自然语言提问，调用应用层 NL2SQL 服务生成 SQL 查询语句并展示解释。
 *
 * <p><b>依赖注入</b>：通过构造函数注入 {@link Nl2SqlSafetyService} 和 {@link Consumer} 回调。
 *
 * <p><b>线程模型</b>：所有耗时操作通过 {@link Task} 提交到桌面共享后台线程池，
 * Task 的成功 / 失败事件在 FX 线程更新 UI。
 * 同时调用 {@link GlobalLoading} 显示 / 隐藏全局 Loading 遮罩。
 *
 * <p><b>离线降级</b>：当 NL2SQL 服务调用失败时，显示离线提示文案，禁用生成按钮。
 */
public final class AiAssistantController {

    private static final Logger log = LoggerFactory.getLogger(AiAssistantController.class);

    private static final String GENERATING_MESSAGE = "AI 正在生成 SQL…";
    private static final String OFFLINE_MESSAGE = "当前未部署 Ollama，展示模拟演示数据";
    private static final String EMPTY_INPUT_MESSAGE = "请输入自然语言提问";
    private static final String ERROR_MESSAGE = "AI 生成失败，请稍后重试";

    private final Nl2SqlSafetyService nl2SqlSafetyService;
    private final AiModelSelectionService aiModelSelectionService;
    private final Consumer<String> fillSqlCallback;
    private final Runnable switchPageCallback;
    private final SqlRiskAnalysisService sqlRiskAnalysisService;
    private final ConnectionManagementService connectionManagementService;
    private Nl2SqlSafetyResult currentResult;
    private boolean applyingModelSelection;
    private boolean modelOperationInProgress;
    private boolean generationInProgress;

    @FXML
    private TextArea questionInput;

    @FXML
    private ComboBox<String> modelSelector;

    @FXML
    private Button refreshModelsButton;

    @FXML
    private Label modelStatusLabel;

    @FXML
    private Button generateButton;

    @FXML
    private TextArea sqlPreviewArea;

    @FXML
    private Label sqlPlaceholder;

    @FXML
    private Button copyToPracticeButton;

    @FXML
    private TextArea aiExplanationArea;

    @FXML
    private Label explanationPlaceholder;

    @FXML
    private Label offlineHint;

    public AiAssistantController(
        Nl2SqlSafetyService nl2SqlSafetyService,
        AiModelSelectionService aiModelSelectionService,
        SqlRiskAnalysisService sqlRiskAnalysisService,
        ConnectionManagementService connectionManagementService,
        Consumer<String> fillSqlCallback,
        Runnable switchPageCallback
    ) {
        this.nl2SqlSafetyService = Objects.requireNonNull(
            nl2SqlSafetyService,
            "nl2SqlSafetyService must not be null"
        );
        this.aiModelSelectionService = Objects.requireNonNull(
            aiModelSelectionService,
            "aiModelSelectionService must not be null"
        );
        this.sqlRiskAnalysisService = Objects.requireNonNull(
            sqlRiskAnalysisService,
            "sqlRiskAnalysisService must not be null"
        );
        this.connectionManagementService = Objects.requireNonNull(
            connectionManagementService,
            "connectionManagementService must not be null"
        );
        this.fillSqlCallback = Objects.requireNonNull(fillSqlCallback, "fillSqlCallback must not be null");
        this.switchPageCallback = Objects.requireNonNull(switchPageCallback, "switchPageCallback must not be null");
    }

    @FXML
    private void initialize() {
        log.info("AiAssistantController initialize() called");
        if (copyToPracticeButton == null) {
            log.error("copyToPracticeButton is null, FXML binding failed");
        } else {
            copyToPracticeButton.setDisable(true);
        }
        if (generateButton == null) {
            log.error("generateButton is null, FXML binding failed");
        }
        if (questionInput == null) {
            log.error("questionInput is null, FXML binding failed");
        }
        refreshModels();
    }

    @FXML
    private void onRefreshModels() {
        refreshModels();
    }

    @FXML
    private void onModelSelected() {
        if (applyingModelSelection || modelOperationInProgress || modelSelector == null) {
            return;
        }
        String selected = modelSelector.getValue();
        if (selected == null || selected.isBlank()) {
            return;
        }
        runModelOperation(() -> aiModelSelectionService.select(selected), "正在保存模型选择…");
    }

    @FXML
    private void onGenerateSql() {
        log.info("onGenerateSql() called");
        try {
            String question = questionInput != null ? questionInput.getText() : null;
            if (question == null || question.isBlank()) {
                showAlert(Alert.AlertType.WARNING, "提示", EMPTY_INPUT_MESSAGE);
                log.warn("Empty input, returning early");
                return;
            }
            if (!aiModelSelectionService.current().hasSelection()) {
                showAlert(Alert.AlertType.WARNING, "未检测到模型", "请先启动 Ollama、安装模型并刷新模型列表");
                return;
            }

            generationInProgress = true;
            updateControlAvailability();
            GlobalLoading.show(GENERATING_MESSAGE);
            hideOfflineHint();
            log.info("Starting NL2SQL generation, questionLength={}", question.length());

            Task<Nl2SqlSafetyResult> task = new Task<>() {
                @Override
                protected Nl2SqlSafetyResult call() {
                    Nl2SqlRequest request = new Nl2SqlRequest(
                        question,
                        DesktopConnections.currentId(connectionManagementService)
                    );
                    return nl2SqlSafetyService.generateAndAssess(request);
                }
            };

            task.setOnSucceeded(event -> {
                try {
                    Nl2SqlSafetyResult result = task.getValue();

                    if (result == null || !result.draftAvailable()) {
                        log.info("AI draft unavailable, using clearly labelled mock fallback");
                        displayResult(generateMockResult(question));
                        showOfflineHint();
                    } else {
                        displayResult(result);
                    }
                } catch (Exception e) {
                    log.error("Failed to display result", e);
                    displayResult(generateMockResult(question));
                    showOfflineHint();
                } finally {
                    generationInProgress = false;
                    updateControlAvailability();
                    GlobalLoading.hide();
                }
            });

            task.setOnFailed(event -> {
                try {
                    Throwable exception = task.getException();
                    log.error("NL2SQL task failed", exception);
                    log.info("Using mock data fallback for offline mode");
                    displayResult(generateMockResult(question));
                    showOfflineHint();
                } catch (Exception e) {
                    log.error("Failed to handle task failure", e);
                    showOfflineHint();
                } finally {
                    generationInProgress = false;
                    updateControlAvailability();
                    GlobalLoading.forceHide();
                }
            });

            DesktopExecutors.background().execute(task);
        } catch (Exception e) {
            log.error("Unexpected error in onGenerateSql", e);
            generationInProgress = false;
            updateControlAvailability();
            GlobalLoading.forceHide();
            showAlert(Alert.AlertType.ERROR, "错误", ERROR_MESSAGE);
        }
    }

    @FXML
    private void onCopyToPractice() {
        log.info("onCopyToPractice() called");
        try {
            String sql = sqlPreviewArea != null ? sqlPreviewArea.getText() : null;
            if (sql != null && !sql.isBlank()) {
                copyAcceptedDraft(sql);
            } else {
                log.warn("No SQL to copy, sqlPreviewArea is empty");
                showAlert(Alert.AlertType.WARNING, "提示", "没有可复制的 SQL");
            }
        } catch (Exception e) {
            log.error("Failed to copy SQL to practice page", e);
            showAlert(Alert.AlertType.ERROR, "错误", "复制失败，请重试");
        }
    }

    private void copyAcceptedDraft(String sql) {
        if (!canCopyDraft(currentResult, sql)) {
            showAlert(Alert.AlertType.WARNING, "安全检查未通过", "该 SQL 草案未通过只读安全检查，不能复制到练习页");
            return;
        }
        log.info("Copying accepted SQL draft to practice page, sqlLength={}", sql.length());
        fillSqlCallback.accept(sql);
        switchPageCallback.run();
    }

    static boolean canCopyDraft(Nl2SqlSafetyResult result, String displayedSql) {
        return result != null
            && result.accepted()
            && displayedSql != null
            && displayedSql.equals(result.plan().sqlDraft());
    }

    private void displayResult(Nl2SqlSafetyResult result) {
        currentResult = result;
        Nl2SqlPlan plan = result == null ? null : result.plan();

        if (plan == null) {
            log.warn("Plan is null, showing placeholders");
            setPlaceholderVisible(sqlPlaceholder, true);
            setPlaceholderVisible(explanationPlaceholder, true);
            copyToPracticeButton.setDisable(true);
            clearRiskHighlight();
            return;
        }

        boolean hasSql = plan.sqlDraft() != null && !plan.sqlDraft().isBlank();
        boolean hasExplanation = plan.explanation() != null && !plan.explanation().isBlank();

        log.debug("hasSql={}, hasExplanation={}", hasSql, hasExplanation);

        if (hasSql) {
            sqlPreviewArea.setText(plan.sqlDraft());
            setPlaceholderVisible(sqlPlaceholder, false);
            copyToPracticeButton.setDisable(!result.accepted());
            applyRiskHighlight(result.riskAnalysis());
            log.info("SQL draft displayed, length={}", plan.sqlDraft().length());
        } else {
            sqlPreviewArea.clear();
            setPlaceholderVisible(sqlPlaceholder, true);
            copyToPracticeButton.setDisable(true);
            clearRiskHighlight();
            log.warn("SQL draft is empty");
        }

        if (hasExplanation) {
            aiExplanationArea.setText(plan.explanation());
            setPlaceholderVisible(explanationPlaceholder, false);
            log.info("Explanation displayed, length={}", plan.explanation().length());
        } else {
            aiExplanationArea.clear();
            setPlaceholderVisible(explanationPlaceholder, true);
            log.warn("Explanation is empty");
        }
    }

    private void applyRiskHighlight(SqlRiskAnalysis analysis) {
        if (analysis == null) {
            clearRiskHighlight();
            return;
        }

        if (analysis.level() == SqlRiskLevel.HIGH || analysis.level() == SqlRiskLevel.FORBIDDEN
            || !analysis.executable() || analysis.multiStatement()) {
            if (!sqlPreviewArea.getStyleClass().contains("risk-highlight")) {
                sqlPreviewArea.getStyleClass().add("risk-highlight");
            }
        } else {
            clearRiskHighlight();
        }
    }

    private void clearRiskHighlight() {
        sqlPreviewArea.getStyleClass().remove("risk-highlight");
    }

    private Nl2SqlSafetyResult generateMockResult(String question) {
        Nl2SqlPlan plan = generateMockPlan(question);
        return new Nl2SqlSafetyResult(plan, sqlRiskAnalysisService.analyze(plan.sqlDraft()));
    }

    private Nl2SqlPlan generateMockPlan(String question) {
        if (question == null) {
            return new Nl2SqlPlan(
                "SELECT name FROM student WHERE score > 90;",
                "QUERY",
                "当前未部署 Ollama，以下为演示模拟数据",
                "mock",
                "v1"
            );
        }

        String lowerQuestion = question.toLowerCase();

        if (lowerQuestion.contains("清空") || lowerQuestion.contains("删除") ||
            lowerQuestion.contains("全部数据")) {
            return new Nl2SqlPlan(
                "DELETE FROM student;",
                "DELETE",
                "该语句会清空整张学生表所有数据，属于高危删除操作，当前未部署 Ollama，为模拟演示数据",
                "mock",
                "v1"
            );
        }

        if (lowerQuestion.contains("大于") || lowerQuestion.contains("小于") ||
            lowerQuestion.contains("分数") || lowerQuestion.contains("成绩")) {
            return new Nl2SqlPlan(
                "SELECT name, score FROM student WHERE score > 90;",
                "QUERY",
                "筛选学生表中成绩大于90分的学生姓名与分数，当前未部署 Ollama，为模拟演示数据",
                "mock",
                "v1"
            );
        }

        if (lowerQuestion.contains("查询全部") || lowerQuestion.contains("所有学生")) {
            return new Nl2SqlPlan(
                "SELECT * FROM student;",
                "QUERY",
                "查询学生表里全部字段、全部学生记录，当前未部署 Ollama，为模拟演示数据",
                "mock",
                "v1"
            );
        }

        return new Nl2SqlPlan(
            "SELECT name FROM student WHERE score > 90;",
            "QUERY",
            "当前未部署 Ollama，以下为演示模拟数据",
            "mock",
            "v1"
        );
    }

    private void showOfflineHint() {
        log.info("Showing offline hint");
        if (offlineHint != null) {
            offlineHint.setText(OFFLINE_MESSAGE);
            Parent parent = offlineHint.getParent();
            if (parent != null) {
                parent.setVisible(true);
                parent.setManaged(true);
            } else {
                log.warn("offlineHint parent is null, cannot show offline hint");
                showAlert(Alert.AlertType.WARNING, "服务离线", OFFLINE_MESSAGE);
            }
        } else {
            log.error("offlineHint is null, cannot show offline hint");
            showAlert(Alert.AlertType.WARNING, "服务离线", OFFLINE_MESSAGE);
        }
    }

    private void hideOfflineHint() {
        if (offlineHint != null) {
            Parent parent = offlineHint.getParent();
            if (parent != null) {
                parent.setVisible(false);
                parent.setManaged(false);
            }
        }
    }

    private void refreshModels() {
        runModelOperation(aiModelSelectionService::refresh, "正在检测本地 Ollama 模型…");
    }

    private void runModelOperation(
        java.util.concurrent.Callable<AiModelSelection> operation,
        String statusMessage
    ) {
        if (modelOperationInProgress) {
            return;
        }
        modelOperationInProgress = true;
        if (modelStatusLabel != null) {
            modelStatusLabel.setText(statusMessage);
        }
        updateControlAvailability();

        Task<AiModelSelection> task = new Task<>() {
            @Override
            protected AiModelSelection call() throws Exception {
                return operation.call();
            }
        };
        task.setOnSucceeded(event -> {
            modelOperationInProgress = false;
            applyModelSelection(task.getValue());
            updateControlAvailability();
        });
        task.setOnFailed(event -> {
            modelOperationInProgress = false;
            applyModelSelection(aiModelSelectionService.current());
            updateControlAvailability();
            log.warn("Failed to update Ollama model selection", task.getException());
            if (modelStatusLabel != null) {
                modelStatusLabel.setText("模型检测失败，请确认 Ollama 已启动");
            }
        });
        DesktopExecutors.background().execute(task);
    }

    private void applyModelSelection(AiModelSelection selection) {
        if (selection == null || modelSelector == null) {
            return;
        }
        applyingModelSelection = true;
        try {
            modelSelector.getItems().setAll(selection.installedModels());
            modelSelector.setValue(selection.hasSelection() ? selection.selectedModel() : null);
            if (modelStatusLabel != null) {
                modelStatusLabel.setText(selection.hasSelection()
                    ? "当前模型：" + selection.selectedModel()
                    : "未检测到已安装模型");
            }
        } finally {
            applyingModelSelection = false;
        }
    }

    private void updateControlAvailability() {
        boolean hasModel = aiModelSelectionService.current().hasSelection();
        if (generateButton != null) {
            generateButton.setDisable(modelOperationInProgress || generationInProgress || !hasModel);
        }
        if (modelSelector != null) {
            modelSelector.setDisable(modelOperationInProgress || generationInProgress);
        }
        if (refreshModelsButton != null) {
            refreshModelsButton.setDisable(modelOperationInProgress || generationInProgress);
        }
    }

    private static void setPlaceholderVisible(Label label, boolean visible) {
        if (label != null) {
            label.setVisible(visible);
            label.setManaged(visible);
        }
    }

    private static void showAlert(Alert.AlertType type, String title, String message) {
        try {
            Runnable show = () -> {
                Alert alert = new Alert(type);
                alert.setTitle(title);
                alert.setHeaderText(null);
                alert.setContentText(message);
                alert.showAndWait();
            };
            if (Platform.isFxApplicationThread()) {
                show.run();
            } else {
                Platform.runLater(show);
            }
        } catch (Exception e) {
            log.error("Failed to show alert", e);
        }
    }
}
