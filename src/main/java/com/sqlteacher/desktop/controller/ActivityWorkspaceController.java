package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.activity.ActivityLearningService;
import com.sqlteacher.application.activity.ActivityReviewItem;
import com.sqlteacher.application.activity.ActivityReviewService;
import com.sqlteacher.application.collaboration.DesktopAccessProfile;
import com.sqlteacher.application.course.CourseMapActivity;
import com.sqlteacher.application.course.CourseMapCourse;
import com.sqlteacher.application.course.CourseMapService;
import com.sqlteacher.application.course.CourseMapSnapshot;
import com.sqlteacher.application.runner.LocalCodeWorkspaceLauncher;
import com.sqlteacher.application.runner.LocalCodeRunner;
import com.sqlteacher.desktop.DesktopExecutors;
import com.sqlteacher.desktop.AppI18n;
import com.sqlteacher.desktop.component.ActivityInteractionPane;
import com.sqlteacher.desktop.component.StatePanel;
import com.sqlteacher.desktop.component.WorkflowSteps;
import com.sqlteacher.desktop.navigation.PendingActivitySelection;
import com.sqlteacher.domain.activity.ActivityType;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Generic four-region activity workspace for deterministic learning and IDE runners. */
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
    private CourseMapActivity selectedActivity;

    @FXML private ListView<CourseMapActivity> activityList;
    @FXML private ComboBox<CourseMapCourse> courseSelector;
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
    @FXML private Button startActivityButton;
    @FXML private WorkflowSteps workflowSteps;

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
            submission -> {
                workspaceStatus.setText(submission.evaluation().summary());
                workflowSteps.setActiveStep(3);
            });
    }

    @FXML
    private void initialize() {
        showActivityPrompt();
        courseSelector.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(CourseMapCourse course) { return course == null ? "" : course.title(); }
            @Override public CourseMapCourse fromString(String value) { return null; }
        });
        courseSelector.valueProperty().addListener((ignored, oldCourse, course) -> showCourse(course));
        workspaceRoot.widthProperty().addListener((ignored, oldWidth, width) -> applyResponsiveLayout(width.doubleValue()));
        activityList.getSelectionModel().selectedItemProperty().addListener((ignored, oldValue, activity) -> {
            if (activity != null) previewActivity(activity);
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
            return;
        }
        for (CourseMapCourse course : courseSelector.getItems()) {
            int courseIndex = find(activity.id(), enabledActivities(course));
            if (courseIndex >= 0) {
                courseSelector.setValue(course);
                activityList.getSelectionModel().select(courseIndex);
                pendingSelection.clear();
                return;
            }
        }
        previewActivity(activity);
    }

    private void loadActivities() {
        workspaceStatus.setText(AppI18n.get("alpha2.workspace.loading"));
        DesktopExecutors.background().execute(() -> {
            try {
                CourseMapSnapshot snapshot = courseMapService.load();
                List<CourseMapCourse> courses = coursesWithEnabledActivities(snapshot);
                int totalActivities = courses.stream().mapToInt(course -> enabledActivities(course).size()).sum();
                Platform.runLater(() -> {
                    courseSelector.getItems().setAll(courses);
                    workspaceStatus.setText(AppI18n.format("alpha2.workspace.summary", courses.size(), totalActivities));
                    for (CourseMapCourse course : courses) {
                        List<CourseMapActivity> activities = enabledActivities(course);
                        int requestedIndex = pendingSelection.resolve(activities);
                        if (requestedIndex >= 0) {
                            courseSelector.setValue(course);
                            activityList.getSelectionModel().select(requestedIndex);
                            return;
                        }
                    }
                    if (!courses.isEmpty()) courseSelector.setValue(courses.getFirst());
                });
            } catch (RuntimeException error) {
                Platform.runLater(() -> workspaceStatus.setText(AppI18n.format("alpha2.workspace.failed", safeMessage(error))));
            }
        });
    }

    private int find(String activityId) {
        return find(activityId, activityList.getItems());
    }

    private static int find(String activityId, List<CourseMapActivity> activities) {
        for (int index = 0; index < activities.size(); index++) {
            if (activities.get(index).id().equals(activityId)) return index;
        }
        return -1;
    }

    static List<CourseMapCourse> coursesWithEnabledActivities(CourseMapSnapshot snapshot) {
        return snapshot.courses().stream().filter(course -> !enabledActivities(course).isEmpty()).toList();
    }

    static List<CourseMapActivity> enabledActivities(CourseMapCourse course) {
        List<CourseMapActivity> activities = new ArrayList<>();
        course.sections().forEach(section -> section.activities().stream()
            .filter(CourseMapActivity::enabled).forEach(activities::add));
        return List.copyOf(activities);
    }

    private void showCourse(CourseMapCourse course) {
        activityList.getSelectionModel().clearSelection();
        selectedActivity = null;
        startActivityButton.setDisable(true);
        workflowSteps.setActiveStep(1);
        activityTitle.setText(AppI18n.get("alpha2.workspace.select"));
        activityMetadata.setText("—");
        knowledgePoints.setText("—");
        showActivityPrompt();
        if (course == null) {
            activityList.getItems().clear();
            return;
        }
        List<CourseMapActivity> activities = enabledActivities(course);
        activityList.getItems().setAll(activities);
        workspaceStatus.setText(AppI18n.format("alpha2.workspace.courseSummary", course.title(), activities.size()));
    }

    private void previewActivity(CourseMapActivity activity) {
        selectedActivity = activity;
        requestedActivityId = activity.id();
        activityTitle.setText(activity.title());
        activityMetadata.setText(AppI18n.format("alpha2.workspace.metadata", activity.type(),
            difficultyLabel(activity), activity.estimatedMinutes()));
        knowledgePoints.setText(activity.knowledgePoints().isEmpty()
            ? AppI18n.get("alpha2.workspace.noKnowledge") : String.join("\n", activity.knowledgePoints()));
        startActivityButton.setDisable(false);
        workflowSteps.setActiveStep(1);
        showActivityPrompt();
    }

    @FXML
    private void onStartActivity() {
        CourseMapActivity activity = selectedActivity;
        if (activity == null) return;
        startActivityButton.setDisable(true);
        workflowSteps.setActiveStep(2);
        openActivity(activity);
    }

    private void openActivity(CourseMapActivity activity) {
        requestedActivityId = activity.id();
        if (activity.type() == ActivityType.SQL) {
            runnerHost.getChildren().setAll(runner);
            currentReview = null;
            teacherReviewEvidence.setText(AppI18n.get("alpha3.review.sql"));
            startActivityButton.setDisable(false);
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
                    startActivityButton.setDisable(false);
                });
            } catch (RuntimeException error) {
                Platform.runLater(() -> {
                    if (!requestedActivityId.equals(activity.id())) return;
                    StatePanel failed = new StatePanel();
                    failed.setTitle(AppI18n.get("alpha3.loadFailed.title"));
                    failed.setMessage(safeMessage(error));
                    runnerHost.getChildren().setAll(failed);
                    startActivityButton.setDisable(false);
                });
            }
        });
    }

    private void showActivityPrompt() {
        StatePanel prompt = new StatePanel();
        prompt.setTitle(AppI18n.get("alpha2.workspace.select"));
        prompt.setMessage(AppI18n.get("alpha2.workspace.preview"));
        runnerHost.getChildren().setAll(prompt);
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
