package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.activity.ActivityLearningService;
import com.sqlteacher.application.activity.ActivityReviewItem;
import com.sqlteacher.application.activity.ActivityReviewService;
import com.sqlteacher.application.collaboration.DesktopAccessProfile;
import com.sqlteacher.application.course.CourseMapActivity;
import com.sqlteacher.application.course.CourseMapService;
import com.sqlteacher.application.course.CourseMapSnapshot;
import com.sqlteacher.application.runner.LocalCodeWorkspaceLauncher;
import com.sqlteacher.application.runner.LocalCodeRunner;
import com.sqlteacher.desktop.DesktopExecutors;
import com.sqlteacher.desktop.AppI18n;
import com.sqlteacher.desktop.component.ActivityInteractionPane;
import com.sqlteacher.desktop.component.StatePanel;
import com.sqlteacher.desktop.navigation.PendingActivitySelection;
import com.sqlteacher.domain.activity.ActivityType;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Generic four-region activity workspace. Alpha.2 mounts the deterministic SQL runner in its center. */
public final class ActivityWorkspaceController {
    private final CourseMapService courseMapService;
    private final ActivityLearningService activityLearningService;
    private final ActivityReviewService activityReviewService;
    private final DesktopAccessProfile accessProfile;
    private final Node runner;
    private final ActivityInteractionPane interactionPane;
    private String requestedActivityId = "";
    private final PendingActivitySelection pendingSelection = new PendingActivitySelection();
    private ActivityReviewItem currentReview;

    @FXML private ListView<CourseMapActivity> activityList;
    @FXML private BorderPane workspaceRoot;
    @FXML private VBox activityRail;
    @FXML private VBox activityInspector;
    @FXML private StackPane runnerHost;
    @FXML private Label activityTitle;
    @FXML private Label activityMetadata;
    @FXML private Label knowledgePoints;
    @FXML private Label workspaceStatus;
    @FXML private VBox teacherReviewPane;
    @FXML private Label teacherReviewEvidence;
    @FXML private TextArea teacherFeedbackInput;

    public ActivityWorkspaceController(CourseMapService courseMapService,
                                       ActivityLearningService activityLearningService,
                                       ActivityReviewService activityReviewService,
                                       LocalCodeRunner localCodeRunner,
                                       LocalCodeWorkspaceLauncher localWorkspaceLauncher,
                                       DesktopAccessProfile accessProfile,
                                       Node runner) {
        this.courseMapService = Objects.requireNonNull(courseMapService);
        this.activityLearningService = Objects.requireNonNull(activityLearningService);
        this.activityReviewService = Objects.requireNonNull(activityReviewService);
        this.accessProfile = Objects.requireNonNull(accessProfile);
        this.runner = Objects.requireNonNull(runner);
        this.interactionPane = new ActivityInteractionPane(activityLearningService, localCodeRunner,
            localWorkspaceLauncher,
            submission -> workspaceStatus.setText(submission.evaluation().summary()));
    }

    @FXML
    private void initialize() {
        runnerHost.getChildren().setAll(runner);
        workspaceRoot.widthProperty().addListener((ignored, oldWidth, width) -> applyResponsiveLayout(width.doubleValue()));
        activityList.getSelectionModel().selectedItemProperty().addListener((ignored, oldValue, activity) -> {
            if (activity != null) showActivity(activity);
        });
        loadActivities();
        boolean reviewer = accessProfile.kind() == DesktopAccessProfile.Kind.TEACHER
            || accessProfile.kind() == DesktopAccessProfile.Kind.ADMIN;
        setVisibleAndManaged(teacherReviewPane, reviewer);
    }

    private void applyResponsiveLayout(double width) {
        setVisibleAndManaged(activityInspector, width >= 1180);
        setVisibleAndManaged(activityRail, width >= 1000);
    }

    private static void setVisibleAndManaged(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    public void selectActivity(CourseMapActivity activity) {
        if (activity == null) return;
        pendingSelection.request(activity.id());
        int index = find(activity.id());
        if (index >= 0) {
            activityList.getSelectionModel().select(index);
            pendingSelection.clear();
        }
        else showActivity(activity);
    }

    private void loadActivities() {
        workspaceStatus.setText(AppI18n.get("alpha2.workspace.loading"));
        DesktopExecutors.background().execute(() -> {
            try {
                CourseMapSnapshot snapshot = courseMapService.load();
                List<CourseMapActivity> activities = new ArrayList<>();
                snapshot.courses().forEach(course -> course.sections().forEach(section ->
                    section.activities().stream().filter(CourseMapActivity::enabled).forEach(activities::add)));
                Platform.runLater(() -> {
                    activityList.getItems().setAll(activities);
                    workspaceStatus.setText(AppI18n.format("alpha2.workspace.summary", activities.size()));
                    int requestedIndex = pendingSelection.resolve(activities);
                    if (requestedIndex >= 0) {
                        activityList.getSelectionModel().select(requestedIndex);
                    } else if (!activities.isEmpty() && activityList.getSelectionModel().isEmpty()) {
                        activityList.getSelectionModel().selectFirst();
                    }
                });
            } catch (RuntimeException error) {
                Platform.runLater(() -> workspaceStatus.setText(AppI18n.format("alpha2.workspace.failed", safeMessage(error))));
            }
        });
    }

    private int find(String activityId) {
        for (int index = 0; index < activityList.getItems().size(); index++) {
            if (activityList.getItems().get(index).id().equals(activityId)) return index;
        }
        return -1;
    }

    private void showActivity(CourseMapActivity activity) {
        requestedActivityId = activity.id();
        activityTitle.setText(activity.title());
        activityMetadata.setText(AppI18n.format("alpha2.workspace.metadata", activity.type(),
            difficultyLabel(activity), activity.estimatedMinutes()));
        knowledgePoints.setText(activity.knowledgePoints().isEmpty()
            ? AppI18n.get("alpha2.workspace.noKnowledge") : String.join("\n", activity.knowledgePoints()));
        if (activity.type() == ActivityType.SQL) {
            runnerHost.getChildren().setAll(runner);
            currentReview = null;
            teacherReviewEvidence.setText(AppI18n.get("alpha3.review.sql"));
            return;
        }
        loadTeacherReview(activity);
        StatePanel loading = new StatePanel();
        loading.setTitle(AppI18n.get("alpha3.loading.title"));
        loading.setMessage(AppI18n.get("alpha3.loading.message"));
        runnerHost.getChildren().setAll(loading);
        DesktopExecutors.background().execute(() -> {
            try {
                var definition = activityLearningService.loadDefinition(activity.id());
                Platform.runLater(() -> {
                    if (!requestedActivityId.equals(activity.id())) return;
                    interactionPane.show(definition);
                    runnerHost.getChildren().setAll(interactionPane);
                });
            } catch (RuntimeException error) {
                Platform.runLater(() -> {
                    if (!requestedActivityId.equals(activity.id())) return;
                    StatePanel failed = new StatePanel();
                    failed.setTitle(AppI18n.get("alpha3.loadFailed.title"));
                    failed.setMessage(safeMessage(error));
                    runnerHost.getChildren().setAll(failed);
                });
            }
        });
    }

    private void loadTeacherReview(CourseMapActivity activity) {
        if (accessProfile.kind() != DesktopAccessProfile.Kind.TEACHER
                && accessProfile.kind() != DesktopAccessProfile.Kind.ADMIN) return;
        currentReview = null;
        teacherReviewEvidence.setText(AppI18n.get("alpha3.review.loading"));
        DesktopExecutors.background().execute(() -> {
            try {
                var review = activityReviewService.latest(accessProfile, activity.id());
                Platform.runLater(() -> {
                    if (!requestedActivityId.equals(activity.id())) return;
                    currentReview = review.orElse(null);
                    teacherReviewEvidence.setText(review.map(item -> AppI18n.format(
                        "alpha3.review.evidence", item.ownerId(), item.status(), item.reasonCode(), item.summary()
                    )).orElseGet(() -> AppI18n.get("alpha3.review.empty")));
                });
            } catch (RuntimeException error) {
                Platform.runLater(() -> teacherReviewEvidence.setText(safeMessage(error)));
            }
        });
    }

    @FXML
    private void publishTeacherFeedback() {
        ActivityReviewItem review = currentReview;
        String comment = teacherFeedbackInput.getText() == null ? "" : teacherFeedbackInput.getText().trim();
        if (review == null || comment.isEmpty()) {
            teacherReviewEvidence.setText(AppI18n.get("alpha3.review.required"));
            return;
        }
        teacherFeedbackInput.setDisable(true);
        DesktopExecutors.background().execute(() -> {
            try {
                activityReviewService.publish(accessProfile, review.evaluationId(), comment);
                Platform.runLater(() -> {
                    teacherFeedbackInput.clear();
                    teacherFeedbackInput.setDisable(false);
                    teacherReviewEvidence.setText(AppI18n.get("alpha3.review.published"));
                });
            } catch (RuntimeException error) {
                Platform.runLater(() -> {
                    teacherFeedbackInput.setDisable(false);
                    teacherReviewEvidence.setText(safeMessage(error));
                });
            }
        });
    }

    private static String difficultyLabel(CourseMapActivity activity) {
        return switch (activity.difficulty()) {
            case BEGINNER -> AppI18n.get("alpha2.difficulty.beginner");
            case INTERMEDIATE -> AppI18n.get("alpha2.difficulty.intermediate");
            case ADVANCED -> AppI18n.get("alpha2.difficulty.advanced");
        };
    }

    private static String safeMessage(RuntimeException error) {
        return error.getMessage() == null || error.getMessage().isBlank()
            ? AppI18n.get("alpha2.unknownError") : error.getMessage();
    }
}
