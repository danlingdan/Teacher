package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.error.ApplicationExceptionMapper;
import com.sqlteacher.application.collaboration.AssignmentTaskContext;
import com.sqlteacher.application.learning.StudentLearningQueueItem;
import com.sqlteacher.application.learning.StudentLearningQueueService;
import com.sqlteacher.desktop.DesktopExecutors;
import com.sqlteacher.desktop.GlobalLoading;
import com.sqlteacher.desktop.appearance.UiIcon;
import com.sqlteacher.desktop.appearance.UiIcons;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;

import java.util.Objects;
import java.util.function.Consumer;

public final class HomeController {

    private final StudentLearningQueueService queueService;
    private final ApplicationExceptionMapper exceptionMapper;

    @FXML private Label sqlIcon;
    @FXML private Label aiIcon;
    @FXML private Label schemaIcon;
    @FXML private ListView<StudentLearningQueueItem> learningQueue;
    @FXML private Label diagnosisSummaryLabel;
    @FXML private Label queueStatusLabel;
    @FXML private Button continueLearningButton;
    @FXML private Button dismissActionButton;

    private Runnable onNavigateAiAssistant;
    private Runnable onNavigateSqlPractice;
    private Runnable onNavigateTableSchema;
    private Consumer<String> onOpenExercise;
    private Consumer<String> onOpenKnowledge;
    private Consumer<AssignmentTaskContext> onOpenAssignment;
    private Runnable onReviewFeedback;

    public HomeController(StudentLearningQueueService queueService, ApplicationExceptionMapper exceptionMapper) {
        this.queueService = Objects.requireNonNull(queueService);
        this.exceptionMapper = Objects.requireNonNull(exceptionMapper);
    }

    @FXML
    private void initialize() {
        sqlIcon.setGraphic(UiIcons.create(UiIcon.CODE, 30));
        aiIcon.setGraphic(UiIcons.create(UiIcon.SPARK, 30));
        schemaIcon.setGraphic(UiIcons.create(UiIcon.TABLE, 30));
        learningQueue.setCellFactory(ignored -> new ListCell<>() {
            @Override protected void updateItem(StudentLearningQueueItem item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.action().title() + "\n" + item.action().description());
            }
        });
        learningQueue.getSelectionModel().selectedItemProperty().addListener((ignored, oldItem, newItem) -> {
            boolean unavailable = newItem == null;
            continueLearningButton.setDisable(unavailable || (newItem.action().exerciseId().isBlank()
                && newItem.action().knowledgePoint().isBlank()
                && newItem.assignmentTask() == null && newItem.notificationId().isBlank()));
            dismissActionButton.setDisable(unavailable);
        });
        refreshDiagnosis();
    }

    public void setOnNavigateAiAssistant(Runnable onNavigateAiAssistant) {
        this.onNavigateAiAssistant = onNavigateAiAssistant;
    }

    public void setOnNavigateSqlPractice(Runnable onNavigateSqlPractice) {
        this.onNavigateSqlPractice = onNavigateSqlPractice;
    }

    public void setOnNavigateTableSchema(Runnable onNavigateTableSchema) {
        this.onNavigateTableSchema = onNavigateTableSchema;
    }

    public void setOnOpenExercise(Consumer<String> onOpenExercise) {
        this.onOpenExercise = onOpenExercise;
    }

    public void setOnOpenKnowledge(Consumer<String> onOpenKnowledge) {
        this.onOpenKnowledge = onOpenKnowledge;
    }

    public void setOnOpenAssignment(Consumer<AssignmentTaskContext> onOpenAssignment) {
        this.onOpenAssignment = onOpenAssignment;
    }

    public void setOnReviewFeedback(Runnable onReviewFeedback) {
        this.onReviewFeedback = onReviewFeedback;
    }

    @FXML
    private void onRefreshDiagnosis() {
        refreshDiagnosis();
    }

    @FXML
    private void onContinueLearning() {
        StudentLearningQueueItem selected = learningQueue.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        if (selected.studyPlanAction() != null) {
            DesktopExecutors.background().execute(() -> queueService.start(selected));
        }
        if (selected.assignmentTask() != null && onOpenAssignment != null) {
            onOpenAssignment.accept(selected.assignmentTask());
        } else if (!selected.notificationId().isBlank() && onReviewFeedback != null) {
            onReviewFeedback.run();
        } else if (!selected.action().exerciseId().isBlank() && onOpenExercise != null) {
            onOpenExercise.accept(selected.action().exerciseId());
        } else if (!selected.action().knowledgePoint().isBlank() && onOpenKnowledge != null) {
            onOpenKnowledge.accept(selected.action().knowledgePoint());
        }
    }

    @FXML
    private void onDismissAction() {
        StudentLearningQueueItem selected = learningQueue.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        GlobalLoading.show("正在更新学习队列…");
        DesktopExecutors.background().execute(() -> {
            try {
                queueService.dismiss(selected);
                Platform.runLater(this::refreshDiagnosis);
            } catch (Throwable error) {
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    queueStatusLabel.setText(exceptionMapper.map(error).userMessage());
                });
            }
        });
    }

    private void refreshDiagnosis() {
        GlobalLoading.show("正在生成确定性学习诊断…");
        DesktopExecutors.background().execute(() -> {
            try {
                var queue = queueService.refresh();
                var dashboard = queue.dashboard();
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    learningQueue.getItems().setAll(queue.items());
                    long weak = dashboard.mastery().stream().filter(item ->
                        item.level() == com.sqlteacher.application.learning.MasteryLevel.NEEDS_PRACTICE).count();
                    diagnosisSummaryLabel.setText("已分析 " + dashboard.mastery().size() + " 个知识点 · "
                        + weak + " 个需要练习 · 规则 " + dashboard.policyVersion());
                    queueStatusLabel.setText(queue.items().isEmpty()
                        ? "当前没有待办建议；继续练习后会自动刷新。"
                        : queue.cloudAvailable() ? "已合并本机诊断、班级任务和未读反馈。"
                        : "云端不可用，当前仅展示本机确定性诊断。");
                    if (!queue.items().isEmpty()) learningQueue.getSelectionModel().selectFirst();
                });
            } catch (Throwable error) {
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    queueStatusLabel.setText(exceptionMapper.map(error).userMessage());
                });
            }
        });
    }

    @FXML
    private void onAiAssistantCardClick() {
        if (onNavigateAiAssistant != null) {
            onNavigateAiAssistant.run();
        }
    }

    @FXML
    private void onSqlPracticeCardClick() {
        if (onNavigateSqlPractice != null) {
            onNavigateSqlPractice.run();
        }
    }

    @FXML
    private void onTableSchemaCardClick() {
        if (onNavigateTableSchema != null) {
            onNavigateTableSchema.run();
        }
    }
}
