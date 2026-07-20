package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.nl2sql.Nl2SqlPlan;
import com.sqlteacher.application.nl2sql.Nl2SqlRequest;
import com.sqlteacher.application.nl2sql.Nl2SqlService;
import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.application.risk.SqlRiskLevel;
import com.sqlteacher.desktop.GlobalLoading;
import com.sqlteacher.desktop.SqlRiskConfirmDialogUtil;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
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
 * <p><b>依赖注入</b>：通过构造函数注入 {@link Nl2SqlService} 和 {@link Consumer} 回调。
 *
 * <p><b>线程模型</b>：所有耗时操作通过 {@link Task} 提交到后台线程，成功 / 失败回调
 * 均通过 {@link Platform#runLater(Runnable)} 切回 FX 线程更新 UI。
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

    private final Nl2SqlService nl2SqlService;
    private final Consumer<String> fillSqlCallback;
    private final Runnable switchPageCallback;
    private final SqlRiskAnalysisService sqlRiskAnalysisService;

    @FXML
    private TextArea questionInput;

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

    public AiAssistantController(Nl2SqlService nl2SqlService, Consumer<String> fillSqlCallback, Runnable switchPageCallback, SqlRiskAnalysisService sqlRiskAnalysisService) {
        this.nl2SqlService = Objects.requireNonNull(nl2SqlService, "nl2SqlService must not be null");
        this.fillSqlCallback = Objects.requireNonNull(fillSqlCallback, "fillSqlCallback must not be null");
        this.switchPageCallback = Objects.requireNonNull(switchPageCallback, "switchPageCallback must not be null");
        this.sqlRiskAnalysisService = Objects.requireNonNull(sqlRiskAnalysisService, "sqlRiskAnalysisService must not be null");
        log.info("AiAssistantController initialized, nl2SqlService={}", nl2SqlService.getClass().getSimpleName());
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

            generateButton.setDisable(true);
            GlobalLoading.show(GENERATING_MESSAGE);
            hideOfflineHint();
            log.info("Starting NL2SQL generation for question: {}", question);

            Task<Nl2SqlPlan> task = new Task<>() {
                @Override
                protected Nl2SqlPlan call() throws Exception {
                    try {
                        Nl2SqlRequest request = new Nl2SqlRequest(question, "demo");
                        log.debug("Calling nl2SqlService.generate() with request: {}", request);
                        Nl2SqlPlan result = nl2SqlService.generate(request);
                        log.info("NL2SQL generation succeeded, sqlDraft length={}", result != null && result.sqlDraft() != null ? result.sqlDraft().length() : 0);
                        return result;
                    } catch (Exception e) {
                        log.error("NL2SQL generation failed", e);
                        throw e;
                    }
                }
            };

            task.setOnSucceeded(event -> Platform.runLater(() -> {
                try {
                    Nl2SqlPlan result = task.getValue();
                    log.info("Task succeeded, displaying result");

                    if (result == null || isEmptyOrErrorResult(result)) {
                        log.info("Result is empty or contains error, using mock data fallback");
                        Nl2SqlPlan mockPlan = generateMockPlan(question);
                        displayResult(mockPlan);
                        showOfflineHint();
                    } else {
                        displayResult(result);
                    }
                } catch (Exception e) {
                    log.error("Failed to display result", e);
                    Nl2SqlPlan mockPlan = generateMockPlan(question);
                    displayResult(mockPlan);
                    showOfflineHint();
                } finally {
                    generateButton.setDisable(false);
                    GlobalLoading.hide();
                }
            }));

            task.setOnFailed(event -> Platform.runLater(() -> {
                try {
                    Throwable exception = task.getException();
                      log.error("Task failed with exception: {}", exception != null ? exception.getMessage() : "null");
                    log.info("Using mock data fallback for offline mode");
                    Nl2SqlPlan mockPlan = generateMockPlan(question);
                    displayResult(mockPlan);
                    showOfflineHint();
                } catch (Exception e) {
                    log.error("Failed to handle task failure", e);
                    showOfflineHint();
                } finally {
                    generateButton.setDisable(false);
                    GlobalLoading.forceHide();
                }
            }));

            new Thread(task).start();
        } catch (Exception e) {
            log.error("Unexpected error in onGenerateSql", e);
            generateButton.setDisable(false);
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
                log.info("Copying SQL to practice page: {}", sql);
                checkRiskAndCopy(sql);
            } else {
                log.warn("No SQL to copy, sqlPreviewArea is empty");
                showAlert(Alert.AlertType.WARNING, "提示", "没有可复制的 SQL");
            }
        } catch (Exception e) {
            log.error("Failed to copy SQL to practice page", e);
            showAlert(Alert.AlertType.ERROR, "错误", "复制失败，请重试");
        }
    }

    private void checkRiskAndCopy(String sql) {
        try {
            SqlRiskAnalysis analysis = sqlRiskAnalysisService.analyze(sql);
            if (analysis != null && analysis.confirmationRequired()) {
                log.info("High risk SQL detected, showing confirm dialog");
                SqlRiskConfirmDialogUtil.showRiskConfirmDialog(sql, () -> {
                    fillSqlCallback.accept(sql);
                    switchPageCallback.run();
                });
            } else {
                log.info("Normal SQL, direct copy");
                fillSqlCallback.accept(sql);
                switchPageCallback.run();
            }
        } catch (Exception e) {
            log.error("Failed to check SQL risk, proceeding with direct copy", e);
            fillSqlCallback.accept(sql);
            switchPageCallback.run();
        }
    }

    private void displayResult(Nl2SqlPlan plan) {
        log.debug("displayResult() called, plan={}", plan);

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
            copyToPracticeButton.setDisable(false);
            applyRiskHighlight(plan.sqlDraft());
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

    private void applyRiskHighlight(String sql) {
        if (sql == null || sql.isBlank()) {
            clearRiskHighlight();
            return;
        }

        try {
            SqlRiskAnalysis analysis = sqlRiskAnalysisService.analyze(sql);
            if (analysis != null && analysis.level() != null && 
                (analysis.level() == SqlRiskLevel.HIGH || analysis.level() == SqlRiskLevel.FORBIDDEN)) {
                log.info("High risk SQL detected, applying highlight");
                sqlPreviewArea.getStyleClass().add("risk-highlight");
            } else {
                clearRiskHighlight();
            }
        } catch (Exception e) {
            log.error("Failed to analyze SQL risk", e);
            clearRiskHighlight();
        }
    }

    private void clearRiskHighlight() {
        sqlPreviewArea.getStyleClass().remove("risk-highlight");
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

    private static void setPlaceholderVisible(Label label, boolean visible) {
        if (label != null) {
            label.setVisible(visible);
            label.setManaged(visible);
        }
    }

    private static boolean isEmptyOrErrorResult(Nl2SqlPlan plan) {
        if (plan == null) {
            return true;
        }
        String sql = plan.sqlDraft();
        String explanation = plan.explanation();

        boolean hasValidSql = sql != null && !sql.isBlank();
        boolean hasValidExplanation = explanation != null && !explanation.isBlank();

        if (!hasValidSql && !hasValidExplanation) {
            return true;
        }

        if (explanation != null && (explanation.toLowerCase().contains("error")
                || explanation.toLowerCase().contains("fail")
                || explanation.toLowerCase().contains("unavailable")
                || explanation.toLowerCase().contains("offline")
                || explanation.contains("Ollama")
                || explanation.contains("ollama"))) {
            return true;
        }

        return false;
    }

    private static void showAlert(Alert.AlertType type, String title, String message) {
        try {
            Platform.runLater(() -> {
                Alert alert = new Alert(type);
                alert.setTitle(title);
                alert.setHeaderText(null);
                alert.setContentText(message);
                alert.showAndWait();
            });
        } catch (Exception e) {
            log.error("Failed to show alert", e);
        }
    }
}
