package com.sqlteacher.desktop.controller;
import com.sqlteacher.desktop.AppI18n;

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
import com.sqlteacher.application.learning.InterventionCandidate;
import com.sqlteacher.application.learning.InterventionService;
import com.sqlteacher.application.learning.InterventionStatus;
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
import com.sqlteacher.desktop.component.MetricCard;
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
    private final InterventionService interventionService;
    private final Runnable openTeachingWorkspace;
    private final ApplicationExceptionMapper exceptionMapper;

    @FXML private DatePicker startDatePicker;
    @FXML private DatePicker endDatePicker;
    @FXML private ComboBox<ExerciseChoice> exerciseFilter;
    @FXML private ComboBox<String> knowledgeFilter;
    @FXML private ComboBox<String> errorFilter;
    @FXML private Button exportButton;
    @FXML private MetricCard sessionsLabel;
    @FXML private MetricCard attemptsLabel;
    @FXML private MetricCard passRateLabel;
    @FXML private MetricCard completionRateLabel;
    @FXML private MetricCard averageAttemptsLabel;
    @FXML private MetricCard durationLabel;
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
    @FXML private TableView<InterventionCandidate> interventionTable;
    @FXML private TableColumn<InterventionCandidate, String> interventionClassColumn;
    @FXML private TableColumn<InterventionCandidate, String> interventionAssignmentColumn;
    @FXML private TableColumn<InterventionCandidate, String> interventionStudentColumn;
    @FXML private TableColumn<InterventionCandidate, String> interventionReasonColumn;
    @FXML private TableColumn<InterventionCandidate, String> interventionEvidenceColumn;
    @FXML private TableColumn<InterventionCandidate, String> interventionStatusColumn;

    public ExerciseProgressController(
        LearningAnalyticsService analyticsService,
        ExerciseCatalogService catalogService,
        DataMaintenanceService maintenanceService,
        InterventionService interventionService,
        Runnable openTeachingWorkspace,
        ApplicationExceptionMapper exceptionMapper
    ) {
        this.analyticsService = Objects.requireNonNull(analyticsService);
        this.catalogService = Objects.requireNonNull(catalogService);
        this.maintenanceService = Objects.requireNonNull(maintenanceService);
        this.interventionService = Objects.requireNonNull(interventionService);
        this.openTeachingWorkspace = Objects.requireNonNull(openTeachingWorkspace);
        this.exceptionMapper = Objects.requireNonNull(exceptionMapper);
    }

    @FXML
    private void initialize() {
        titleColumn.setCellValueFactory(cell -> text(cell.getValue().title()));
        knowledgeColumn.setCellValueFactory(cell -> text(cell.getValue().knowledgePoint()));
        attemptsColumn.setCellValueFactory(cell -> text(cell.getValue().attempts()));
        submissionsColumn.setCellValueFactory(cell -> text(cell.getValue().submissions()));
        passColumn.setCellValueFactory(cell -> text(percent(cell.getValue().passRate())));
        completedColumn.setCellValueFactory(cell -> text(cell.getValue().completed() ? AppI18n.get("ExerciseProgressController.1") : AppI18n.get("ExerciseProgressController.2")));
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
        interventionClassColumn.setCellValueFactory(cell -> text(cell.getValue().classroomName()));
        interventionAssignmentColumn.setCellValueFactory(cell -> text(cell.getValue().assignmentTitle()));
        interventionStudentColumn.setCellValueFactory(cell -> text(cell.getValue().studentDisplayName()));
        interventionReasonColumn.setCellValueFactory(cell -> text(reasonLabel(cell.getValue())));
        interventionEvidenceColumn.setCellValueFactory(cell -> text(cell.getValue().evidenceSummary()));
        interventionStatusColumn.setCellValueFactory(cell -> text(cell.getValue().status().name()));
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
        GlobalLoading.show(AppI18n.get("ExerciseProgressController.3"));
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
            AppI18n.get("ExerciseProgressController.4"),
            ButtonType.CANCEL,
            ButtonType.OK
        );
        confirm.setTitle(AppI18n.get("ExerciseProgressController.5"));
        confirm.setHeaderText(AppI18n.get("ExerciseProgressController.6"));
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        AnalyticsFilter filter = captureFilterSnapshot();
        GlobalLoading.show(AppI18n.get("ExerciseProgressController.7"));
        DesktopExecutors.background().execute(() -> {
            try {
                LearningDataResetResult result = maintenanceService.resetLearningData();
                LearningAnalyticsReport report = analyticsService.analyze(filter);
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    showReport(report);
                    showStatus(AppI18n.get("ExerciseProgressController.8") + result.sessionsDeleted() + AppI18n.get("ExerciseProgressController.9")
                        + result.attemptsDeleted() + AppI18n.get("ExerciseProgressController.10") + result.eventsDeleted() + AppI18n.get("ExerciseProgressController.11"), false);
                });
            } catch (Throwable error) {
                Platform.runLater(() -> fail(error));
            }
        });
    }

    private void loadInitialData() {
        GlobalLoading.show(AppI18n.get("ExerciseProgressController.12"));
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
        refreshInterventions();
    }

    @FXML
    private void onRefreshInterventions() {
        refreshInterventions();
    }

    @FXML
    private void onAcknowledgeIntervention() {
        updateSelectedIntervention(InterventionStatus.ACKNOWLEDGED);
    }

    @FXML
    private void onResolveIntervention() {
        updateSelectedIntervention(InterventionStatus.RESOLVED);
    }

    @FXML
    private void onDismissIntervention() {
        updateSelectedIntervention(InterventionStatus.DISMISSED);
    }

    @FXML
    private void onOpenTeachingWorkspace() {
        if (interventionTable.getSelectionModel().getSelectedItem() == null) {
            showStatus(AppI18n.get("ExerciseProgressController.13"), true);
            return;
        }
        openTeachingWorkspace.run();
    }

    @FXML
    private void onExportInterventions() {
        String csv = interventionService.exportCsv(List.copyOf(interventionTable.getItems()));
        FileChooser chooser = new FileChooser();
        chooser.setTitle(AppI18n.get("ExerciseProgressController.14"));
        chooser.setInitialFileName("sqlteacher-interventions.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(AppI18n.get("ExerciseProgressController.15"), "*.csv"));
        File target = chooser.showSaveDialog(interventionTable.getScene().getWindow());
        if (target == null) return;
        GlobalLoading.show(AppI18n.get("ExerciseProgressController.16"));
        DesktopExecutors.background().execute(() -> {
            try {
                Files.writeString(target.toPath(), csv, StandardCharsets.UTF_8);
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    showStatus(AppI18n.get("ExerciseProgressController.17") + target.getAbsolutePath(), false);
                });
            } catch (Throwable error) {
                Platform.runLater(() -> fail(error));
            }
        });
    }

    private void refreshInterventions() {
        DesktopExecutors.background().execute(() -> {
            try {
                List<InterventionCandidate> items = interventionService.refreshAuthorized();
                Platform.runLater(() -> interventionTable.getItems().setAll(items));
            } catch (Throwable error) {
                Platform.runLater(() -> showStatus(AppI18n.get("ExerciseProgressController.18")
                    + exceptionMapper.map(error).userMessage(), true));
            }
        });
    }

    private void updateSelectedIntervention(InterventionStatus status) {
        InterventionCandidate selected = interventionTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showStatus(AppI18n.get("ExerciseProgressController.19"), true);
            return;
        }
        GlobalLoading.show(AppI18n.get("ExerciseProgressController.20"));
        DesktopExecutors.background().execute(() -> {
            try {
                interventionService.updateStatus(selected.id(), status);
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    refreshInterventions();
                });
            } catch (Throwable error) {
                Platform.runLater(() -> fail(error));
            }
        });
    }

    private static String reasonLabel(InterventionCandidate item) {
        return switch (item.reason()) {
            case OVERDUE_TASK -> AppI18n.get("ExerciseProgressController.21");
            case REPEATED_FAILURE -> AppI18n.get("ExerciseProgressController.22");
            case STALE_PROGRESS -> AppI18n.get("ExerciseProgressController.23");
            default -> item.reason().name();
        };
    }

    private void refresh(AnalyticsFilter filter) {
        GlobalLoading.show(AppI18n.get("ExerciseProgressController.24"));
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
            throw new IllegalArgumentException(AppI18n.get("ExerciseProgressController.25"));
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
        sessionsLabel.setValue(Integer.toString(overview.sessions()));
        attemptsLabel.setValue(Integer.toString(overview.attempts()));
        passRateLabel.setValue(percent(overview.passRate()));
        completionRateLabel.setValue(percent(overview.completionRate()));
        averageAttemptsLabel.setValue(String.format(Locale.ROOT, "%.2f", overview.averageAttemptsPerCompletedExercise()));
        durationLabel.setValue(overview.averageSubmissionDuration().toMillis() + " ms");
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
        showStatus(AppI18n.get("ExerciseProgressController.26"), false);
    }

    private void chooseExportFile(AnalyticsCsvExport export) {
        GlobalLoading.hide();
        FileChooser chooser = new FileChooser();
        chooser.setTitle(AppI18n.get("ExerciseProgressController.27"));
        chooser.setInitialFileName(export.fileName());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(AppI18n.get("ExerciseProgressController.28"), "*.csv"));
        File target = chooser.showSaveDialog(exportButton.getScene().getWindow());
        if (target == null) {
            showStatus(AppI18n.get("ExerciseProgressController.29"), false);
            return;
        }
        GlobalLoading.show(AppI18n.get("ExerciseProgressController.30"));
        DesktopExecutors.background().execute(() -> {
            try {
                Files.writeString(target.toPath(), export.utf8Content(), StandardCharsets.UTF_8);
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    showStatus(AppI18n.get("ExerciseProgressController.31") + target.getAbsolutePath(), false);
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
