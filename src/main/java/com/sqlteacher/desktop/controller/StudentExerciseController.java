package com.sqlteacher.desktop.controller;
import com.sqlteacher.desktop.AppI18n;

import com.sqlteacher.application.error.ApplicationExceptionMapper;
import com.sqlteacher.application.execution.SqlExecutionResult;
import com.sqlteacher.application.exercise.ExerciseAttemptResult;
import com.sqlteacher.application.exercise.ExerciseCatalogService;
import com.sqlteacher.application.exercise.ExerciseHint;
import com.sqlteacher.application.exercise.ExercisePracticeService;
import com.sqlteacher.application.exercise.ExerciseSession;
import com.sqlteacher.application.exercise.ExerciseSummary;
import com.sqlteacher.application.collaboration.AssignmentDeliveryResult;
import com.sqlteacher.application.collaboration.AssignmentDeliveryService;
import com.sqlteacher.application.collaboration.AssignmentTaskContext;
import com.sqlteacher.desktop.DesktopExecutors;
import com.sqlteacher.desktop.GlobalLoading;
import com.sqlteacher.desktop.component.PageHeader;
import com.sqlteacher.desktop.component.WorkflowSteps;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

public final class StudentExerciseController {
    private final ExerciseCatalogService catalogService;
    private final ExercisePracticeService practiceService;
    private final ApplicationExceptionMapper exceptionMapper;
    private final AssignmentDeliveryService assignmentDeliveryService;

    @FXML private ListView<ExerciseSummary> exerciseList;
    @FXML private PageHeader workflowHeader;
    @FXML private WorkflowSteps workflowSteps;
    @FXML private Pane selectionStepPane;
    @FXML private Pane answerStepPane;
    @FXML private Label titleLabel;
    @FXML private Label metaLabel;
    @FXML private Label descriptionLabel;
    @FXML private Label schemaLabel;
    @FXML private Label answerTitleLabel;
    @FXML private Label answerMetaLabel;
    @FXML private Label answerDescriptionLabel;
    @FXML private Label answerSchemaLabel;
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
    private AssignmentTaskContext assignmentTask;
    private String requestedExerciseId;
    private final AtomicBoolean running = new AtomicBoolean();

    public StudentExerciseController(
        ExerciseCatalogService catalogService,
        ExercisePracticeService practiceService,
        ApplicationExceptionMapper exceptionMapper,
        AssignmentDeliveryService assignmentDeliveryService
    ) {
        this.catalogService = Objects.requireNonNull(catalogService);
        this.practiceService = Objects.requireNonNull(practiceService);
        this.exceptionMapper = Objects.requireNonNull(exceptionMapper);
        this.assignmentDeliveryService = Objects.requireNonNull(assignmentDeliveryService);
    }

    public void openAssignment(AssignmentTaskContext task) {
        assignmentTask = Objects.requireNonNull(task);
        showSelectionStep();
        if (!exerciseList.getItems().isEmpty()) activateAssignment();
    }

    public void openExercise(String exerciseId) {
        if (exerciseId == null || exerciseId.isBlank()) throw new IllegalArgumentException("exerciseId must not be blank");
        assignmentTask = null;
        requestedExerciseId = exerciseId.trim();
        showSelectionStep();
        activateRequestedExercise();
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
        showSelectionStep();
        setSessionActions(false);
        sqlArea.addEventFilter(KeyEvent.KEY_PRESSED, this::handleEditorShortcut);
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
            showStatus(AppI18n.get("StudentExerciseController.1"), true);
            return;
        }
        ExerciseSession previous = session;
        runAsync(AppI18n.get("StudentExerciseController.2"), () -> {
            if (previous != null && !previous.completed()) {
                practiceService.close(previous.id());
            }
            return practiceService.start(selected.id());
        }, started -> {
            session = started;
            titleLabel.setText(started.exercise().title());
            metaLabel.setText(started.exercise().knowledgePoint() + " · " + started.exercise().difficulty());
            descriptionLabel.setText(started.exercise().description());
            schemaLabel.setText(AppI18n.get("StudentExerciseController.3") + started.exercise().schemaSummary());
            answerTitleLabel.setText(started.exercise().title());
            answerMetaLabel.setText(started.exercise().knowledgePoint() + " · " + started.exercise().difficulty());
            answerDescriptionLabel.setText(started.exercise().description());
            answerSchemaLabel.setText(AppI18n.get("StudentExerciseController.3") + started.exercise().schemaSummary());
            sqlArea.clear();
            feedbackArea.clear();
            resultTable.getColumns().clear();
            resultTable.getItems().clear();
            setSessionActions(true);
            showAnswerStep();
            showStatus(AppI18n.get("StudentExerciseController.4"), false);
        });
    }

    @FXML
    private void onChooseAnother() {
        ExerciseSession active = session;
        if (active == null) {
            showSelectionStep();
            return;
        }
        runAsync(AppI18n.get("StudentExerciseController.34"), () -> {
            practiceService.close(active.id());
            return active.id();
        }, ignored -> {
            session = null;
            setSessionActions(false);
            showSelectionStep();
            showSelection(exerciseList.getSelectionModel().getSelectedItem());
            showStatus(AppI18n.get("StudentExerciseController.35"), false);
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
        runAsync(AppI18n.get("StudentExerciseController.5"), () -> practiceService.requestHint(session.id()), this::showHint);
    }

    @FXML
    private void onReset() {
        if (!requireSession()) return;
        runAsync(AppI18n.get("StudentExerciseController.6"), () -> practiceService.reset(session.id()), reset -> {
            session = reset;
            resultTable.getColumns().clear();
            resultTable.getItems().clear();
            feedbackArea.clear();
            showStatus(AppI18n.get("StudentExerciseController.7"), false);
        });
    }

    private void execute(boolean submit) {
        if (!requireSession()) return;
        String sql = sqlArea.getText();
        runAsync(
            submit ? AppI18n.get("StudentExerciseController.8") : AppI18n.get("StudentExerciseController.9"),
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
            passed ? AppI18n.get("StudentExerciseController.10") : attempt.execution().success()
                ? (submitted ? AppI18n.get("StudentExerciseController.11") : AppI18n.get("StudentExerciseController.12"))
                : attempt.execution().message(),
            !attempt.execution().success() || (submitted && !passed)
        );
        if (submitted && assignmentTask != null && attempt.evaluation() != null) {
            String errorCode = attempt.evaluation().errorCode();
            runAsync(AppI18n.get("StudentExerciseController.13"), () -> assignmentDeliveryService.deliver(
                assignmentTask.classroomId(), assignmentTask.assignment().id(), passed,
                errorCode == null || errorCode.isBlank() ? null : errorCode, attempt.occurredAt()),
                this::showDeliveryResult);
        }
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
            + AppI18n.get("StudentExerciseController.14") + hint.level() + AppI18n.get("StudentExerciseController.15") + hint.text());
        showStatus(hint.exhausted() ? AppI18n.get("StudentExerciseController.16") : AppI18n.get("StudentExerciseController.17"), false);
    }

    private void showSelection(ExerciseSummary selected) {
        startButton.setDisable(selected == null);
        if (selected == null || !selectionStepPane.isVisible()) {
            return;
        }
        runAsync(AppI18n.get("StudentExerciseController.18"), () -> catalogService.findAvailableExercise(selected.id()).orElseThrow(), view -> {
            titleLabel.setText(view.title());
            metaLabel.setText(view.knowledgePoint() + " · " + view.difficulty());
            descriptionLabel.setText(view.description());
            schemaLabel.setText(AppI18n.get("StudentExerciseController.19") + view.schemaSummary());
        });
    }

    private void refreshCatalog() {
        runAsync(AppI18n.get("StudentExerciseController.20"), catalogService::listAvailableExercises, exercises -> {
            exerciseList.getItems().setAll(exercises);
            if (assignmentTask != null) {
                activateAssignment();
            } else if (requestedExerciseId != null) {
                activateRequestedExercise();
            } else if (!exercises.isEmpty() && exerciseList.getSelectionModel().isEmpty()) {
                exerciseList.getSelectionModel().selectFirst();
            }
        });
    }

    private void activateRequestedExercise() {
        if (requestedExerciseId == null || exerciseList == null || exerciseList.getItems().isEmpty()) return;
        ExerciseSummary target = exerciseList.getItems().stream()
            .filter(item -> item.id().equals(requestedExerciseId)).findFirst().orElse(null);
        if (target == null) {
            showStatus(AppI18n.get("StudentExerciseController.21") + requestedExerciseId, true);
            requestedExerciseId = null;
            return;
        }
        exerciseList.getSelectionModel().select(target);
        requestedExerciseId = null;
        showStatus(AppI18n.get("StudentExerciseController.22") + target.title() + AppI18n.get("StudentExerciseController.23"), false);
    }

    private void activateAssignment() {
        ExerciseSummary exercise = exerciseList.getItems().stream()
            .filter(item -> item.id().equals(assignmentTask.assignment().exerciseId()))
            .findFirst()
            .orElse(null);
        if (exercise == null) {
            showStatus(AppI18n.get("StudentExerciseController.24") + assignmentTask.assignment().exerciseId(), true);
            return;
        }
        exerciseList.getSelectionModel().select(exercise);
        showStatus(AppI18n.get("StudentExerciseController.25") + assignmentTask.assignment().title() + AppI18n.get("StudentExerciseController.26"), false);
    }

    private void showSelectionStep() {
        setVisibleManaged(selectionStepPane, true);
        setVisibleManaged(answerStepPane, false);
        workflowHeader.setTitle(AppI18n.get("student-exercise.chooseTitle"));
        workflowSteps.setActiveStep(1);
    }

    private void showAnswerStep() {
        setVisibleManaged(selectionStepPane, false);
        setVisibleManaged(answerStepPane, true);
        workflowHeader.setTitle(AppI18n.get("student-exercise.answerTitle"));
        workflowSteps.setActiveStep(2);
        Platform.runLater(sqlArea::requestFocus);
    }

    private static void setVisibleManaged(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private void showDeliveryResult(AssignmentDeliveryResult result) {
        switch (result.status()) {
            case PASSED -> showStatus(AppI18n.get("StudentExerciseController.27") + result.attemptNumber(), false);
            case SUBMITTED -> showStatus(AppI18n.get("StudentExerciseController.28"), false);
            case QUEUED -> showStatus(AppI18n.get("StudentExerciseController.29"), false);
            case REJECTED -> showStatus(AppI18n.get("StudentExerciseController.30"), true);
        }
    }

    private boolean requireSession() {
        if (session == null || session.completed()) {
            showStatus(AppI18n.get("StudentExerciseController.31"), true);
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
        if (!running.compareAndSet(false, true)) {
            showStatus(AppI18n.get("StudentExerciseController.32"), false);
            return;
        }
        GlobalLoading.show(message);
        DesktopExecutors.background().execute(() -> {
            try {
                T result = task.get();
                Platform.runLater(() -> {
                    running.set(false);
                    GlobalLoading.hide();
                    success.accept(result);
                });
            } catch (Throwable error) {
                Platform.runLater(() -> {
                    running.set(false);
                    GlobalLoading.hide();
                    showStatus(exceptionMapper.map(error).userMessage(), true);
                });
            }
        });
    }

    private void handleEditorShortcut(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER && event.isControlDown()) {
            if (event.isShiftDown()) onSubmit();
            else onRun();
            event.consume();
        } else if (event.getCode() == KeyCode.F1) {
            onHint();
            event.consume();
        }
    }

    private void showStatus(String message, boolean error) {
        statusLabel.setText(message == null || message.isBlank() ? AppI18n.get("StudentExerciseController.33") : message);
        statusLabel.getStyleClass().removeAll("sql-result-hint", "sql-error-hint");
        statusLabel.getStyleClass().add(error ? "sql-error-hint" : "sql-result-hint");
        statusLabel.setVisible(true);
        statusLabel.setManaged(true);
    }
}
