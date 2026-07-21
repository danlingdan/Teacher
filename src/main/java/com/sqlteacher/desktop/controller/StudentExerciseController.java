package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.error.ApplicationExceptionMapper;
import com.sqlteacher.application.execution.SqlExecutionResult;
import com.sqlteacher.application.exercise.ExerciseAttemptResult;
import com.sqlteacher.application.exercise.ExerciseCatalogService;
import com.sqlteacher.application.exercise.ExerciseHint;
import com.sqlteacher.application.exercise.ExercisePracticeService;
import com.sqlteacher.application.exercise.ExerciseSession;
import com.sqlteacher.application.exercise.ExerciseSummary;
import com.sqlteacher.desktop.DesktopExecutors;
import com.sqlteacher.desktop.GlobalLoading;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class StudentExerciseController {
    private final ExerciseCatalogService catalogService;
    private final ExercisePracticeService practiceService;
    private final ApplicationExceptionMapper exceptionMapper;

    @FXML private ListView<ExerciseSummary> exerciseList;
    @FXML private Label titleLabel;
    @FXML private Label metaLabel;
    @FXML private Label descriptionLabel;
    @FXML private TextArea sqlArea;
    @FXML private TableView<Map<String, Object>> resultTable;
    @FXML private TextArea feedbackArea;
    @FXML private Label statusLabel;
    @FXML private Button startButton;
    @FXML private Button runButton;
    @FXML private Button submitButton;
    @FXML private Button hintButton;
    @FXML private Button resetButton;

    private ExerciseSession session;

    public StudentExerciseController(
        ExerciseCatalogService catalogService,
        ExercisePracticeService practiceService,
        ApplicationExceptionMapper exceptionMapper
    ) {
        this.catalogService = Objects.requireNonNull(catalogService);
        this.practiceService = Objects.requireNonNull(practiceService);
        this.exceptionMapper = Objects.requireNonNull(exceptionMapper);
    }

    @FXML
    private void initialize() {
        exerciseList.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected void updateItem(ExerciseSummary item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                    : item.title() + "\n" + item.knowledgePoint() + " · " + item.difficulty());
            }
        });
        exerciseList.getSelectionModel().selectedItemProperty().addListener(
            (ignored, oldValue, selected) -> showSelection(selected)
        );
        setSessionActions(false);
        refreshCatalog();
    }

    @FXML
    private void onRefresh() {
        refreshCatalog();
    }

    @FXML
    private void onStart() {
        ExerciseSummary selected = exerciseList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showStatus("请先选择一道题目。", true);
            return;
        }
        ExerciseSession previous = session;
        runAsync("正在创建隔离练习数据…", () -> {
            if (previous != null && !previous.completed()) {
                practiceService.close(previous.id());
            }
            return practiceService.start(selected.id());
        }, started -> {
            session = started;
            titleLabel.setText(started.exercise().title());
            metaLabel.setText(started.exercise().knowledgePoint() + " · " + started.exercise().difficulty());
            descriptionLabel.setText(started.exercise().description());
            sqlArea.clear();
            feedbackArea.clear();
            resultTable.getColumns().clear();
            resultTable.getItems().clear();
            setSessionActions(true);
            showStatus("练习已开始，数据集与其他会话完全隔离。", false);
        });
    }

    @FXML
    private void onRun() {
        execute(false);
    }

    @FXML
    private void onSubmit() {
        execute(true);
    }

    @FXML
    private void onHint() {
        if (!requireSession()) return;
        runAsync("正在获取提示…", () -> practiceService.requestHint(session.id()), this::showHint);
    }

    @FXML
    private void onReset() {
        if (!requireSession()) return;
        runAsync("正在重置练习数据…", () -> practiceService.reset(session.id()), reset -> {
            session = reset;
            resultTable.getColumns().clear();
            resultTable.getItems().clear();
            feedbackArea.clear();
            showStatus("练习数据已恢复到初始状态。", false);
        });
    }

    private void execute(boolean submit) {
        if (!requireSession()) return;
        String sql = sqlArea.getText();
        runAsync(
            submit ? "正在提交并评测…" : "正在运行查询…",
            () -> submit ? practiceService.submit(session.id(), sql) : practiceService.run(session.id(), sql),
            result -> showAttempt(result, submit)
        );
    }

    private void showAttempt(ExerciseAttemptResult attempt, boolean submitted) {
        showResult(attempt.execution());
        if (attempt.evaluation() != null) {
            StringBuilder feedback = new StringBuilder(attempt.evaluation().feedback());
            attempt.evaluation().criteria().forEach(criterion -> feedback.append("\n")
                .append(criterion.passed() ? "✓ " : "• ").append(criterion.feedback()));
            feedbackArea.setText(feedback.toString());
        } else {
            feedbackArea.setText(attempt.execution().message());
        }
        boolean passed = submitted && attempt.evaluation() != null && attempt.evaluation().passed();
        if (passed) {
            session = new ExerciseSession(
                session.id(), session.exercise(), session.startedAt(), session.hintsUsed(), true
            );
            setSessionActions(false);
        }
        showStatus(
            passed ? "恭喜，提交通过。" : attempt.execution().success()
                ? (submitted ? "评测完成，请查看反馈。" : "查询运行完成。")
                : attempt.execution().message(),
            !attempt.execution().success() || (submitted && !passed)
        );
    }

    private void showResult(SqlExecutionResult result) {
        resultTable.getColumns().clear();
        for (String column : result.columns()) {
            TableColumn<Map<String, Object>, String> tableColumn = new TableColumn<>(column);
            tableColumn.setCellValueFactory(cell -> new SimpleStringProperty(
                Objects.toString(cell.getValue().get(column), "NULL")
            ));
            resultTable.getColumns().add(tableColumn);
        }
        resultTable.getItems().setAll(result.rows());
    }

    private void showHint(ExerciseHint hint) {
        feedbackArea.appendText((feedbackArea.getText().isBlank() ? "" : "\n")
            + "提示 " + hint.level() + "：" + hint.text());
        showStatus(hint.exhausted() ? "已显示最后一级提示。" : "已显示一级提示。", false);
    }

    private void showSelection(ExerciseSummary selected) {
        startButton.setDisable(selected == null);
        if (selected == null || session != null && !session.completed()) {
            return;
        }
        runAsync("正在读取题目…", () -> catalogService.findAvailableExercise(selected.id()).orElseThrow(), view -> {
            titleLabel.setText(view.title());
            metaLabel.setText(view.knowledgePoint() + " · " + view.difficulty());
            descriptionLabel.setText(view.description());
        });
    }

    private void refreshCatalog() {
        runAsync("正在加载练习题…", catalogService::listAvailableExercises, exercises -> {
            exerciseList.getItems().setAll(exercises);
            if (!exercises.isEmpty() && exerciseList.getSelectionModel().isEmpty()) {
                exerciseList.getSelectionModel().selectFirst();
            }
        });
    }

    private boolean requireSession() {
        if (session == null || session.completed()) {
            showStatus("请先开始一道练习。", true);
            return false;
        }
        return true;
    }

    private void setSessionActions(boolean enabled) {
        runButton.setDisable(!enabled);
        submitButton.setDisable(!enabled);
        hintButton.setDisable(!enabled);
        resetButton.setDisable(!enabled);
        sqlArea.setDisable(!enabled);
    }

    private <T> void runAsync(String message, Supplier<T> task, Consumer<T> success) {
        GlobalLoading.show(message);
        DesktopExecutors.background().execute(() -> {
            try {
                T result = task.get();
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    success.accept(result);
                });
            } catch (Throwable error) {
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    showStatus(exceptionMapper.map(error).userMessage(), true);
                });
            }
        });
    }

    private void showStatus(String message, boolean error) {
        statusLabel.setText(message == null || message.isBlank() ? "操作失败，请稍后重试。" : message);
        statusLabel.getStyleClass().removeAll("sql-result-hint", "sql-error-hint");
        statusLabel.getStyleClass().add(error ? "sql-error-hint" : "sql-result-hint");
        statusLabel.setVisible(true);
        statusLabel.setManaged(true);
    }
}
