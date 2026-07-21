package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.error.ApplicationExceptionMapper;
import com.sqlteacher.application.exercise.ExerciseDraft;
import com.sqlteacher.application.exercise.ExerciseManagementService;
import com.sqlteacher.application.exercise.ExerciseSummary;
import com.sqlteacher.desktop.DesktopExecutors;
import com.sqlteacher.desktop.GlobalLoading;
import com.sqlteacher.domain.exercise.ExerciseDefinition;
import com.sqlteacher.domain.exercise.ExerciseDifficulty;
import com.sqlteacher.domain.exercise.ExerciseEvaluationRule;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ExerciseManagementController {
    private final ExerciseManagementService managementService;
    private final ApplicationExceptionMapper exceptionMapper;

    @FXML private ListView<ExerciseSummary> exerciseList;
    @FXML private TextField idField;
    @FXML private TextField titleField;
    @FXML private TextArea descriptionArea;
    @FXML private TextField knowledgePointField;
    @FXML private ComboBox<ExerciseDifficulty> difficultyBox;
    @FXML private ComboBox<String> datasetBox;
    @FXML private TextArea referenceSqlArea;
    @FXML private TextArea hintsArea;
    @FXML private CheckBox compareColumnsCheck;
    @FXML private CheckBox compareRowsCheck;
    @FXML private CheckBox rowOrderCheck;
    @FXML private TextField rowCountField;
    @FXML private TextField keywordsField;
    @FXML private CheckBox enabledCheck;
    @FXML private Label versionLabel;
    @FXML private Label statusLabel;
    @FXML private Button toggleButton;

    private Integer currentVersion;

    public ExerciseManagementController(
        ExerciseManagementService managementService,
        ApplicationExceptionMapper exceptionMapper
    ) {
        this.managementService = Objects.requireNonNull(managementService);
        this.exceptionMapper = Objects.requireNonNull(exceptionMapper);
    }

    @FXML
    private void initialize() {
        difficultyBox.getItems().setAll(ExerciseDifficulty.values());
        difficultyBox.setValue(ExerciseDifficulty.BEGINNER);
        exerciseList.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected void updateItem(ExerciseSummary item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                    : item.title() + "  ·  " + item.difficulty() + (item.enabled() ? "" : "  ·  已停用"));
            }
        });
        exerciseList.getSelectionModel().selectedItemProperty().addListener(
            (ignored, oldValue, selected) -> loadDefinition(selected)
        );
        compareRowsCheck.selectedProperty().addListener(
            (ignored, oldValue, selected) -> rowOrderCheck.setDisable(!selected)
        );
        refresh(null);
    }

    @FXML
    private void onNew() {
        exerciseList.getSelectionModel().clearSelection();
        clearForm();
        idField.requestFocus();
    }

    @FXML
    private void onSave() {
        ExerciseDraft draft;
        try {
            draft = buildDraft();
        } catch (RuntimeException error) {
            showStatus(error.getMessage(), true);
            return;
        }
        runAsync("正在保存题目…", () -> managementService.save(draft), saved -> {
            showStatus("题目已保存，当前版本 " + saved.version() + "。", false);
            refresh(saved.id());
        });
    }

    @FXML
    private void onCopy() {
        ExerciseSummary selected = exerciseList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showStatus("请先选择要复制的题目。", true);
            return;
        }
        TextInputDialog dialog = new TextInputDialog(selected.title() + "（副本）");
        dialog.setTitle("复制题目");
        dialog.setHeaderText("副本默认停用，请检查后再启用。");
        dialog.setContentText("新标题：");
        dialog.showAndWait().ifPresent(title -> runAsync(
            "正在复制题目…",
            () -> managementService.copy(selected.id(), title),
            copied -> {
                showStatus("题目副本已创建并保持停用。", false);
                refresh(copied.id());
            }
        ));
    }

    @FXML
    private void onToggleEnabled() {
        ExerciseSummary selected = exerciseList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showStatus("请先选择题目。", true);
            return;
        }
        runAsync(
            selected.enabled() ? "正在停用题目…" : "正在启用题目…",
            () -> managementService.setEnabled(selected.id(), !selected.enabled(), selected.version()),
            changed -> {
                showStatus(changed.enabled() ? "题目已启用。" : "题目已停用。", false);
                refresh(changed.id());
            }
        );
    }

    @FXML
    private void onImport() {
        FileChooser chooser = jsonChooser("导入题包");
        File file = chooser.showOpenDialog(exerciseList.getScene().getWindow());
        if (file == null) {
            return;
        }
        runAsync("正在导入题包…", () -> {
            try {
                return managementService.importPackage(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            } catch (IOException error) {
                throw new IllegalStateException("无法读取题包文件。", error);
            }
        }, imported -> {
            showStatus("导入完成：" + imported.exercisesImported() + " 道题。", false);
            refresh(imported.importedExerciseIds().isEmpty() ? null : imported.importedExerciseIds().getFirst());
        });
    }

    @FXML
    private void onExport() {
        ExerciseSummary selected = exerciseList.getSelectionModel().getSelectedItem();
        FileChooser chooser = jsonChooser("导出题包");
        chooser.setInitialFileName(selected == null ? "sqlteacher-exercises.json" : selected.id() + ".json");
        File file = chooser.showSaveDialog(exerciseList.getScene().getWindow());
        if (file == null) {
            return;
        }
        List<String> ids = selected == null ? List.of() : List.of(selected.id());
        runAsync("正在导出题包…", () -> {
            try {
                Files.writeString(file.toPath(), managementService.exportPackage(ids), StandardCharsets.UTF_8);
                return file;
            } catch (IOException error) {
                throw new IllegalStateException("无法写入题包文件。", error);
            }
        }, exported -> showStatus("题包已导出到：" + exported.getName(), false));
    }

    @FXML
    private void onRefresh() {
        refresh(selectedId());
    }

    private void refresh(String selectedId) {
        runAsync("正在加载题库…", () -> new CatalogSnapshot(
            managementService.listExercises(true),
            managementService.listDatasets().stream().map(dataset -> dataset.id()).toList()
        ), snapshot -> {
            exerciseList.getItems().setAll(snapshot.exercises());
            datasetBox.getItems().setAll(snapshot.datasetIds());
            if (datasetBox.getValue() == null && !snapshot.datasetIds().isEmpty()) {
                datasetBox.setValue(snapshot.datasetIds().getFirst());
            }
            if (selectedId != null) {
                snapshot.exercises().stream().filter(item -> item.id().equals(selectedId)).findFirst()
                    .ifPresent(exerciseList.getSelectionModel()::select);
            }
        });
    }

    private void loadDefinition(ExerciseSummary summary) {
        if (summary == null) {
            updateToggle(null);
            return;
        }
        runAsync("正在读取题目…", () -> managementService.findDefinition(summary.id()).orElseThrow(), this::showDefinition);
    }

    private void showDefinition(ExerciseDefinition exercise) {
        idField.setText(exercise.id());
        idField.setDisable(true);
        titleField.setText(exercise.title());
        descriptionArea.setText(exercise.description());
        knowledgePointField.setText(exercise.knowledgePoint());
        difficultyBox.setValue(exercise.difficulty());
        datasetBox.setValue(exercise.datasetId());
        referenceSqlArea.setText(exercise.referenceSql());
        hintsArea.setText(String.join("\n", exercise.hints()));
        compareColumnsCheck.setSelected(exercise.evaluationRule().compareColumns());
        compareRowsCheck.setSelected(exercise.evaluationRule().compareRows());
        rowOrderCheck.setSelected(exercise.evaluationRule().rowOrderMatters());
        rowCountField.setText(exercise.evaluationRule().expectedRowCount() == null
            ? "" : exercise.evaluationRule().expectedRowCount().toString());
        keywordsField.setText(String.join(", ", exercise.evaluationRule().requiredSqlKeywords()));
        enabledCheck.setSelected(exercise.enabled());
        currentVersion = exercise.version();
        versionLabel.setText("版本：" + exercise.version());
        updateToggle(new ExerciseSummary(
            exercise.id(), exercise.title(), exercise.knowledgePoint(), exercise.difficulty(),
            exercise.version(), exercise.enabled()
        ));
    }

    private ExerciseDraft buildDraft() {
        Integer rowCount = rowCountField.getText().isBlank()
            ? null : Integer.valueOf(rowCountField.getText().trim());
        List<String> keywords = Arrays.stream(keywordsField.getText().split(","))
            .map(String::trim).filter(value -> !value.isEmpty()).toList();
        List<String> hints = hintsArea.getText().lines().map(String::trim).filter(value -> !value.isEmpty()).toList();
        return new ExerciseDraft(
            idField.getText(), titleField.getText(), descriptionArea.getText(), knowledgePointField.getText(),
            difficultyBox.getValue(), datasetBox.getValue(), referenceSqlArea.getText(),
            new ExerciseEvaluationRule(
                compareColumnsCheck.isSelected(), compareRowsCheck.isSelected(), rowOrderCheck.isSelected(),
                rowCount, keywords
            ),
            hints,
            currentVersion,
            enabledCheck.isSelected()
        );
    }

    private void clearForm() {
        idField.clear();
        idField.setDisable(false);
        titleField.clear();
        descriptionArea.clear();
        knowledgePointField.clear();
        difficultyBox.setValue(ExerciseDifficulty.BEGINNER);
        if (!datasetBox.getItems().isEmpty()) datasetBox.setValue(datasetBox.getItems().getFirst());
        referenceSqlArea.clear();
        hintsArea.clear();
        compareColumnsCheck.setSelected(true);
        compareRowsCheck.setSelected(true);
        rowOrderCheck.setSelected(false);
        rowCountField.clear();
        keywordsField.clear();
        enabledCheck.setSelected(false);
        currentVersion = null;
        versionLabel.setText("新题目");
        updateToggle(null);
    }

    private void updateToggle(ExerciseSummary summary) {
        toggleButton.setDisable(summary == null);
        toggleButton.setText(summary != null && summary.enabled() ? "停用" : "启用");
    }

    private String selectedId() {
        ExerciseSummary selected = exerciseList.getSelectionModel().getSelectedItem();
        return selected == null ? null : selected.id();
    }

    private static FileChooser jsonChooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON 题包", "*.json"));
        return chooser;
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
        statusLabel.setText(message == null || message.isBlank() ? "操作失败，请检查输入。" : message);
        statusLabel.getStyleClass().removeAll("sql-result-hint", "sql-error-hint");
        statusLabel.getStyleClass().add(error ? "sql-error-hint" : "sql-result-hint");
        statusLabel.setVisible(true);
        statusLabel.setManaged(true);
    }

    private record CatalogSnapshot(List<ExerciseSummary> exercises, List<String> datasetIds) {
    }
}
