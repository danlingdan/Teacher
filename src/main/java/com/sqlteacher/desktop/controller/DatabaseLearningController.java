package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.course.CourseMapActivity;
import com.sqlteacher.application.databaselearning.DatabaseModelingService;
import com.sqlteacher.application.databaselearning.LearningGoalCatalogService;
import com.sqlteacher.application.databaselearning.WebDataLabService;
import com.sqlteacher.desktop.DesktopExecutors;
import com.sqlteacher.desktop.component.WorkflowSteps;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.Node;

import java.net.URI;
import java.util.Objects;
import java.util.function.Consumer;

public final class DatabaseLearningController {
    private final LearningGoalCatalogService goals;
    private final DatabaseModelingService modeling;
    private final WebDataLabService webData;
    private final Consumer<CourseMapActivity> openActivity;
    private final Consumer<String> openSqlDraft;
    private final Runnable openAssistant;
    private WebDataLabService.DataPreview currentPreview;

    @FXML private ComboBox<LearningGoalCatalogService.LearningGoal> goalSelector;
    @FXML private Label goalOutcomeLabel;
    @FXML private ListView<CourseMapActivity> activityList;
    @FXML private Button openActivityButton;
    @FXML private TextArea requirementInput;
    @FXML private TextArea modelOutput;
    @FXML private Button copyDdlButton;
    @FXML private TextField sourceUrlField;
    @FXML private TextField targetTableField;
    @FXML private TextArea dataPreviewArea;
    @FXML private Label dataStatusLabel;
    @FXML private Button fetchButton;
    @FXML private Button buildInsertButton;
    @FXML private WorkflowSteps goalWorkflow;
    @FXML private WorkflowSteps modelWorkflow;
    @FXML private WorkflowSteps dataWorkflow;
    @FXML private Node modelReviewPane;
    @FXML private Node dataReviewPane;

    public DatabaseLearningController(LearningGoalCatalogService goals,
                                      DatabaseModelingService modeling,
                                      WebDataLabService webData,
                                      Consumer<CourseMapActivity> openActivity,
                                      Consumer<String> openSqlDraft,
                                      Runnable openAssistant) {
        this.goals = Objects.requireNonNull(goals);
        this.modeling = Objects.requireNonNull(modeling);
        this.webData = Objects.requireNonNull(webData);
        this.openActivity = Objects.requireNonNull(openActivity);
        this.openSqlDraft = Objects.requireNonNull(openSqlDraft);
        this.openAssistant = Objects.requireNonNull(openAssistant);
    }

    @FXML
    private void initialize() {
        goalSelector.getItems().setAll(goals.load());
        goalSelector.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(LearningGoalCatalogService.LearningGoal goal) { return goal == null ? "" : goal.title(); }
            @Override public LearningGoalCatalogService.LearningGoal fromString(String value) { return null; }
        });
        goalSelector.valueProperty().addListener((observable, oldValue, newValue) -> showGoal(newValue));
        activityList.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            openActivityButton.setDisable(newValue == null);
            if (newValue != null) goalWorkflow.setActiveStep(2);
        });
        if (!goalSelector.getItems().isEmpty()) goalSelector.setValue(goalSelector.getItems().getFirst());
        copyDdlButton.setDisable(true);
        buildInsertButton.setDisable(true);
    }

    private void showGoal(LearningGoalCatalogService.LearningGoal goal) {
        if (goal == null) return;
        goalOutcomeLabel.setText(goal.outcome() + "\n" + goal.stages().stream()
            .map(stage -> "• " + stage.title() + "：" + stage.guidance()).reduce((a, b) -> a + "\n" + b).orElse(""));
        activityList.getItems().setAll(goal.stages().stream().flatMap(stage -> stage.activities().stream()).distinct().toList());
    }

    @FXML private void onOpenActivity() { CourseMapActivity selected = activityList.getSelectionModel().getSelectedItem(); if (selected != null) { goalWorkflow.setActiveStep(3); openActivity.accept(selected); } }
    @FXML private void onOpenAssistant() { openAssistant.run(); }

    @FXML
    private void onCreateModel() {
        setVisibleManaged(modelReviewPane, false);
        try {
            var draft = modeling.draft(requirementInput.getText());
            String tables = draft.tables().stream().map(table -> table.name() + " — " + table.purpose()).reduce((a, b) -> a + "\n" + b).orElse("");
            modelOutput.setText(draft.title() + "\n" + draft.explanation() + (tables.isBlank() ? "" : "\n\n表与职责\n" + tables)
                + (draft.ddl().isBlank() ? "" : "\n\nDDL 草稿\n" + draft.ddl()));
            copyDdlButton.setDisable(draft.ddl().isBlank());
            copyDdlButton.setUserData(draft.ddl());
            modelWorkflow.setActiveStep(2);
            setVisibleManaged(modelReviewPane, true);
        } catch (RuntimeException error) {
            modelOutput.setText(error.getMessage());
            copyDdlButton.setDisable(true);
            setVisibleManaged(modelReviewPane, true);
        }
    }

    @FXML private void onUseEnrollmentExample() { requirementInput.setText("设计学生、课程和选课记录，学生不能重复选择同一课程。"); }
    @FXML private void onUseOrderExample() { requirementInput.setText("设计顾客、商品、订单和订单明细，记录商品数量与价格。"); }
    @FXML private void onUseLibraryExample() { requirementInput.setText("设计图书借阅系统，记录读者、图书、借出时间和归还时间。"); }
    @FXML private void onCopyDdl() { Object ddl = copyDdlButton.getUserData(); if (ddl instanceof String sql && !sql.isBlank()) { modelWorkflow.setActiveStep(3); openSqlDraft.accept(sql); } }

    @FXML
    private void onFetchPreview() {
        String input = sourceUrlField.getText();
        if (input == null || input.isBlank()) { dataStatusLabel.setText("请先输入公开 HTTP(S) 地址。"); return; }
        fetchButton.setDisable(true);
        buildInsertButton.setDisable(true);
        setVisibleManaged(dataReviewPane, false);
        dataStatusLabel.setText("正在安全读取并限制预览范围…");
        Task<WebDataLabService.DataPreview> task = new Task<>() {
            @Override protected WebDataLabService.DataPreview call() { return webData.preview(URI.create(input.strip())); }
        };
        task.setOnSucceeded(event -> {
            currentPreview = task.getValue();
            dataPreviewArea.setText(String.join(" | ", currentPreview.columns()) + "\n" + currentPreview.rows().stream()
                .map(row -> String.join(" | ", row)).reduce((a, b) -> a + "\n" + b).orElse(""));
            dataStatusLabel.setText("已预览 " + currentPreview.rows().size() + " 行；内容只用于草稿，不会自动写入数据库。");
            fetchButton.setDisable(false);
            buildInsertButton.setDisable(false);
            dataWorkflow.setActiveStep(2);
            setVisibleManaged(dataReviewPane, true);
        });
        task.setOnFailed(event -> {
            currentPreview = null;
            dataPreviewArea.clear();
            setVisibleManaged(dataReviewPane, false);
            dataStatusLabel.setText("预览失败：" + safeMessage(task.getException()));
            fetchButton.setDisable(false);
        });
        DesktopExecutors.background().execute(task);
    }

    @FXML
    private void onBuildInsert() {
        try {
            String sql = webData.buildInsertDraft(targetTableField.getText(), currentPreview);
            dataWorkflow.setActiveStep(3);
            openSqlDraft.accept(sql);
        } catch (RuntimeException error) {
            dataStatusLabel.setText("无法生成草稿：" + safeMessage(error));
        }
    }

    private static String safeMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) return "无法完成操作";
        return error.getMessage();
    }

    private static void setVisibleManaged(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
