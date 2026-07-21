package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.analytics.AnalyticsCsvExport;
import com.sqlteacher.application.analytics.AnalyticsFilter;
import com.sqlteacher.application.analytics.ErrorAnalytics;
import com.sqlteacher.application.analytics.ExerciseAnalyticsRow;
import com.sqlteacher.application.analytics.KnowledgePointAnalytics;
import com.sqlteacher.application.analytics.LearningAnalyticsReport;
import com.sqlteacher.application.analytics.LearningAnalyticsService;
import com.sqlteacher.application.error.ApplicationExceptionMapper;
import com.sqlteacher.application.exercise.ExerciseCatalogService;
import com.sqlteacher.application.exercise.ExerciseSummary;
import com.sqlteacher.application.maintenance.DataMaintenanceService;
import com.sqlteacher.application.maintenance.LearningDataResetResult;
import com.sqlteacher.desktop.DesktopExecutors;
import com.sqlteacher.desktop.GlobalLoading;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ExerciseProgressController {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(ZoneId.systemDefault());

    private final LearningAnalyticsService analyticsService;
    private final ExerciseCatalogService catalogService;
    private final DataMaintenanceService maintenanceService;
    private final ApplicationExceptionMapper exceptionMapper;

    @FXML private DatePicker startDatePicker;
    @FXML private DatePicker endDatePicker;
    @FXML private ComboBox<ExerciseChoice> exerciseFilter;
    @FXML private ComboBox<String> knowledgeFilter;
    @FXML private ComboBox<String> errorFilter;
    @FXML private Button exportButton;
    @FXML private Label sessionsLabel;
    @FXML private Label attemptsLabel;
    @FXML private Label passRateLabel;
    @FXML private Label completionRateLabel;
    @FXML private Label averageAttemptsLabel;
    @FXML private Label durationLabel;
    @FXML private Label statusLabel;
    @FXML private TableView<ExerciseAnalyticsRow> exerciseTable;
    @FXML private TableColumn<ExerciseAnalyticsRow, String> titleColumn;
    @FXML private TableColumn<ExerciseAnalyticsRow, String> knowledgeColumn;
    @FXML private TableColumn<ExerciseAnalyticsRow, String> attemptsColumn;
    @FXML private TableColumn<ExerciseAnalyticsRow, String> submissionsColumn;
    @FXML private TableColumn<ExerciseAnalyticsRow, String> passColumn;
    @FXML private TableColumn<ExerciseAnalyticsRow, String> completedColumn;
    @FXML private TableColumn<ExerciseAnalyticsRow, String> lastAttemptColumn;
    @FXML private TableView<ErrorAnalytics> errorTable;
    @FXML private TableColumn<ErrorAnalytics, String> errorCodeColumn;
    @FXML private TableColumn<ErrorAnalytics, String> errorCountColumn;
    @FXML private TableView<KnowledgePointAnalytics> knowledgeTable;
    @FXML private TableColumn<KnowledgePointAnalytics, String> pointColumn;
    @FXML private TableColumn<KnowledgePointAnalytics, String> pointAttemptsColumn;
    @FXML private TableColumn<KnowledgePointAnalytics, String> pointFailuresColumn;
    @FXML private TableColumn<KnowledgePointAnalytics, String> pointCompletionColumn;
    @FXML private TableColumn<KnowledgePointAnalytics, String> weaknessColumn;

    public ExerciseProgressController(
        LearningAnalyticsService analyticsService,
        ExerciseCatalogService catalogService,
        DataMaintenanceService maintenanceService,
        ApplicationExceptionMapper exceptionMapper
    ) {
        this.analyticsService = Objects.requireNonNull(analyticsService);
        this.catalogService = Objects.requireNonNull(catalogService);
        this.maintenanceService = Objects.requireNonNull(maintenanceService);
        this.exceptionMapper = Objects.requireNonNull(exceptionMapper);
    }

    @FXML
    private void initialize() {
        titleColumn.setCellValueFactory(cell -> text(cell.getValue().title()));
        knowledgeColumn.setCellValueFactory(cell -> text(cell.getValue().knowledgePoint()));
        attemptsColumn.setCellValueFactory(cell -> text(cell.getValue().attempts()));
        submissionsColumn.setCellValueFactory(cell -> text(cell.getValue().submissions()));
        passColumn.setCellValueFactory(cell -> text(percent(cell.getValue().passRate())));
        completedColumn.setCellValueFactory(cell -> text(cell.getValue().completed() ? "已完成" : "未完成"));
        lastAttemptColumn.setCellValueFactory(cell -> text(cell.getValue().lastAttempt().map(TIME_FORMAT::format).orElse("-")));
        errorCodeColumn.setCellValueFactory(cell -> text(cell.getValue().errorCode()));
        errorCountColumn.setCellValueFactory(cell -> text(cell.getValue().count()));
        pointColumn.setCellValueFactory(cell -> text(cell.getValue().knowledgePoint()));
        pointAttemptsColumn.setCellValueFactory(cell -> text(cell.getValue().attempts()));
        pointFailuresColumn.setCellValueFactory(cell -> text(cell.getValue().failedSubmissions()));
        pointCompletionColumn.setCellValueFactory(cell -> text(
            cell.getValue().completedExercises() + "/" + cell.getValue().totalExercises()
        ));
        weaknessColumn.setCellValueFactory(cell -> text(percent(cell.getValue().weaknessRate())));
        loadInitialData();
    }

    @FXML
    private void onRefresh() {
        refresh(captureFilter());
    }

    @FXML
    private void onClearFilters() {
        startDatePicker.setValue(null);
        endDatePicker.setValue(null);
        exerciseFilter.setValue(null);
        knowledgeFilter.setValue(null);
        errorFilter.setValue(null);
        refresh(AnalyticsFilter.all());
    }

    @FXML
    private void onExport() {
        AnalyticsFilter filter;
        try {
            filter = captureFilter();
        } catch (RuntimeException error) {
            showStatus(exceptionMapper.map(error).userMessage(), true);
            return;
        }
        GlobalLoading.show("正在生成学情 CSV…");
        DesktopExecutors.background().execute(() -> {
            try {
                AnalyticsCsvExport export = analyticsService.exportCsv(filter);
                Platform.runLater(() -> chooseExportFile(export));
            } catch (Throwable error) {
                Platform.runLater(() -> fail(error));
            }
        });
    }

    @FXML
    private void onResetLearningData() {
        Alert confirm = new Alert(
            Alert.AlertType.CONFIRMATION,
            "将删除全部练习会话、尝试和学习事件，但保留题库、连接配置与知识文档。此操作不可撤销。",
            ButtonType.CANCEL,
            ButtonType.OK
        );
        confirm.setTitle("清理学习数据");
        confirm.setHeaderText("确认清理本机学习数据？");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        AnalyticsFilter filter = captureFilterSnapshot();
        GlobalLoading.show("正在清理学习数据…");
        DesktopExecutors.background().execute(() -> {
            try {
                LearningDataResetResult result = maintenanceService.resetLearningData();
                LearningAnalyticsReport report = analyticsService.analyze(filter);
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    showReport(report);
                    showStatus("已删除 " + result.sessionsDeleted() + " 个会话、"
                        + result.attemptsDeleted() + " 次尝试和 " + result.eventsDeleted() + " 条事件。", false);
                });
            } catch (Throwable error) {
                Platform.runLater(() -> fail(error));
            }
        });
    }

    private void loadInitialData() {
        GlobalLoading.show("正在加载教师学情看板…");
        DesktopExecutors.background().execute(() -> {
            try {
                List<ExerciseSummary> catalog = catalogService.listAvailableExercises();
                LearningAnalyticsReport report = analyticsService.analyze(AnalyticsFilter.all());
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    exerciseFilter.getItems().setAll(catalog.stream().map(ExerciseChoice::new).toList());
                    knowledgeFilter.getItems().setAll(catalog.stream().map(ExerciseSummary::knowledgePoint).distinct().sorted().toList());
                    showReport(report);
                });
            } catch (Throwable error) {
                Platform.runLater(() -> fail(error));
            }
        });
    }

    private void refresh(AnalyticsFilter filter) {
        GlobalLoading.show("正在计算学情统计…");
        DesktopExecutors.background().execute(() -> {
            try {
                LearningAnalyticsReport report = analyticsService.analyze(filter);
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    showReport(report);
                });
            } catch (Throwable error) {
                Platform.runLater(() -> fail(error));
            }
        });
    }

    private AnalyticsFilter captureFilter() {
        LocalDate start = startDatePicker.getValue();
        LocalDate end = endDatePicker.getValue();
        if (start != null && end != null && end.isBefore(start)) {
            throw new IllegalArgumentException("结束日期不能早于开始日期");
        }
        ZoneId zone = ZoneId.systemDefault();
        Instant startInstant = start == null ? null : start.atStartOfDay(zone).toInstant();
        Instant endInstant = end == null ? null : end.plusDays(1).atStartOfDay(zone).toInstant();
        ExerciseChoice exercise = exerciseFilter.getValue();
        return new AnalyticsFilter(
            startInstant,
            endInstant,
            exercise == null ? null : exercise.summary().id(),
            knowledgeFilter.getValue(),
            errorFilter.getValue()
        );
    }

    private AnalyticsFilter captureFilterSnapshot() {
        try {
            return captureFilter();
        } catch (RuntimeException ignored) {
            return AnalyticsFilter.all();
        }
    }

    private void showReport(LearningAnalyticsReport report) {
        var overview = report.overview();
        sessionsLabel.setText(Integer.toString(overview.sessions()));
        attemptsLabel.setText(Integer.toString(overview.attempts()));
        passRateLabel.setText(percent(overview.passRate()));
        completionRateLabel.setText(percent(overview.completionRate()));
        averageAttemptsLabel.setText(String.format(Locale.ROOT, "%.2f", overview.averageAttemptsPerCompletedExercise()));
        durationLabel.setText(overview.averageSubmissionDuration().toMillis() + " ms");
        exerciseTable.getItems().setAll(report.exercises());
        errorTable.getItems().setAll(report.commonErrors());
        knowledgeTable.getItems().setAll(report.knowledgePoints());
        List<String> errorOptions = report.commonErrors().stream().map(ErrorAnalytics::errorCode).distinct().toList();
        String selectedError = errorFilter.getValue();
        errorFilter.getItems().setAll(errorOptions);
        if (selectedError != null && !errorOptions.contains(selectedError)) {
            errorFilter.getItems().add(selectedError);
        }
        errorFilter.setValue(selectedError);
        exportButton.setDisable(false);
        showStatus("统计已更新。通过率按通过提交/全部提交，日期按本机时区筛选。", false);
    }

    private void chooseExportFile(AnalyticsCsvExport export) {
        GlobalLoading.hide();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出学情 CSV");
        chooser.setInitialFileName(export.fileName());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV 文件", "*.csv"));
        File target = chooser.showSaveDialog(exportButton.getScene().getWindow());
        if (target == null) {
            showStatus("已取消导出。", false);
            return;
        }
        GlobalLoading.show("正在写入 CSV…");
        DesktopExecutors.background().execute(() -> {
            try {
                Files.writeString(target.toPath(), export.utf8Content(), StandardCharsets.UTF_8);
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    showStatus("CSV 已导出到 " + target.getAbsolutePath(), false);
                });
            } catch (Throwable error) {
                Platform.runLater(() -> fail(error));
            }
        });
    }

    private void fail(Throwable error) {
        GlobalLoading.hide();
        showStatus(exceptionMapper.map(error).userMessage(), true);
    }

    private void showStatus(String message, boolean error) {
        statusLabel.setText(message);
        statusLabel.getStyleClass().removeAll("sql-result-hint", "sql-error-hint");
        statusLabel.getStyleClass().add(error ? "sql-error-hint" : "sql-result-hint");
        statusLabel.setVisible(true);
        statusLabel.setManaged(true);
    }

    private static SimpleStringProperty text(Object value) {
        return new SimpleStringProperty(String.valueOf(value));
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    public record ExerciseChoice(ExerciseSummary summary) {
        @Override
        public String toString() {
            return summary.title();
        }
    }
}
