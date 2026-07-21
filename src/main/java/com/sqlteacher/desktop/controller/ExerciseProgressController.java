package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.error.ApplicationExceptionMapper;
import com.sqlteacher.application.exercise.ExerciseProgressItem;
import com.sqlteacher.application.exercise.ExerciseProgressOverview;
import com.sqlteacher.application.exercise.ExerciseProgressService;
import com.sqlteacher.desktop.DesktopExecutors;
import com.sqlteacher.desktop.GlobalLoading;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ExerciseProgressController {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(ZoneId.systemDefault());

    private final ExerciseProgressService progressService;
    private final ApplicationExceptionMapper exceptionMapper;

    @FXML private Label sessionsLabel;
    @FXML private Label attemptsLabel;
    @FXML private Label passRateLabel;
    @FXML private Label durationLabel;
    @FXML private Label hintsLabel;
    @FXML private Label completedLabel;
    @FXML private Label statusLabel;
    @FXML private TableView<ExerciseProgressItem> progressTable;
    @FXML private TableColumn<ExerciseProgressItem, String> titleColumn;
    @FXML private TableColumn<ExerciseProgressItem, String> knowledgeColumn;
    @FXML private TableColumn<ExerciseProgressItem, String> attemptsColumn;
    @FXML private TableColumn<ExerciseProgressItem, String> failuresColumn;
    @FXML private TableColumn<ExerciseProgressItem, String> passedColumn;
    @FXML private TableColumn<ExerciseProgressItem, String> lastAttemptColumn;

    public ExerciseProgressController(
        ExerciseProgressService progressService,
        ApplicationExceptionMapper exceptionMapper
    ) {
        this.progressService = Objects.requireNonNull(progressService);
        this.exceptionMapper = Objects.requireNonNull(exceptionMapper);
    }

    @FXML
    private void initialize() {
        titleColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().title()));
        knowledgeColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().knowledgePoint()));
        attemptsColumn.setCellValueFactory(cell -> new SimpleStringProperty(Integer.toString(cell.getValue().attempts())));
        failuresColumn.setCellValueFactory(cell -> new SimpleStringProperty(Integer.toString(cell.getValue().failedSubmissions())));
        passedColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().passed() ? "已通过" : "未通过"));
        lastAttemptColumn.setCellValueFactory(cell -> new SimpleStringProperty(
            cell.getValue().lastAttempt().map(TIME_FORMAT::format).orElse("-")
        ));
        refresh();
    }

    @FXML
    private void onRefresh() {
        refresh();
    }

    private void refresh() {
        GlobalLoading.show("正在汇总练习进度…");
        DesktopExecutors.background().execute(() -> {
            try {
                ProgressSnapshot snapshot = new ProgressSnapshot(
                    progressService.overview(), progressService.listExerciseProgress()
                );
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    showSnapshot(snapshot);
                });
            } catch (Throwable error) {
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    showStatus(exceptionMapper.map(error).userMessage(), true);
                });
            }
        });
    }

    private void showSnapshot(ProgressSnapshot snapshot) {
        ExerciseProgressOverview overview = snapshot.overview();
        sessionsLabel.setText(Integer.toString(overview.sessions()));
        attemptsLabel.setText(Integer.toString(overview.attempts()));
        passRateLabel.setText(String.format(Locale.ROOT, "%.1f%%", overview.submissionPassRate() * 100));
        durationLabel.setText(overview.averageSubmissionDuration().toMillis() + " ms");
        hintsLabel.setText(Integer.toString(overview.hintsUsed()));
        completedLabel.setText(Integer.toString(overview.completedExercises()));
        progressTable.getItems().setAll(snapshot.items());
        showStatus("统计已更新。通过率按通过提交数 / 全部提交数计算。", false);
    }

    private void showStatus(String message, boolean error) {
        statusLabel.setText(message);
        statusLabel.getStyleClass().removeAll("sql-result-hint", "sql-error-hint");
        statusLabel.getStyleClass().add(error ? "sql-error-hint" : "sql-result-hint");
        statusLabel.setVisible(true);
        statusLabel.setManaged(true);
    }

    private record ProgressSnapshot(
        ExerciseProgressOverview overview,
        List<ExerciseProgressItem> items
    ) {
    }
}
