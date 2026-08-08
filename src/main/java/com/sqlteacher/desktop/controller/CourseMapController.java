package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.course.CourseMapActivity;
import com.sqlteacher.application.course.CourseMapCourse;
import com.sqlteacher.application.course.CourseMapService;
import com.sqlteacher.application.course.CourseMapSnapshot;
import com.sqlteacher.desktop.DesktopExecutors;
import com.sqlteacher.desktop.AppI18n;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.function.Consumer;

/** Read-only local course map backed by the published Alpha activity catalog. */
public final class CourseMapController {
    private final CourseMapService courseMapService;
    private final Consumer<CourseMapActivity> openActivity;

    @FXML private Label summaryLabel;
    @FXML private Label stateLabel;
    @FXML private VBox courseList;

    public CourseMapController(CourseMapService courseMapService, Consumer<CourseMapActivity> openActivity) {
        this.courseMapService = Objects.requireNonNull(courseMapService);
        this.openActivity = Objects.requireNonNull(openActivity);
    }

    @FXML
    private void initialize() {
        refresh();
    }

    @FXML
    private void refresh() {
        stateLabel.setText(AppI18n.get("alpha2.courseMap.loading"));
        courseList.getChildren().clear();
        DesktopExecutors.background().execute(() -> {
            try {
                CourseMapSnapshot snapshot = courseMapService.load();
                Platform.runLater(() -> render(snapshot));
            } catch (RuntimeException error) {
                Platform.runLater(() -> stateLabel.setText(AppI18n.format("alpha2.courseMap.failed", safeMessage(error))));
            }
        });
    }

    private void render(CourseMapSnapshot snapshot) {
        summaryLabel.setText(AppI18n.format("alpha2.courseMap.summary", snapshot.courses().size(), snapshot.activityCount()));
        courseList.getChildren().clear();
        for (CourseMapCourse course : snapshot.courses()) {
            VBox courseCard = new VBox(12);
            courseCard.getStyleClass().addAll("course-map-card", "panel-card");
            Label title = new Label(AppI18n.format("alpha2.courseMap.version", course.title(), course.version()));
            title.getStyleClass().add("card-title");
            courseCard.getChildren().add(title);
            course.sections().forEach(section -> {
                Label sectionTitle = new Label(section.title());
                sectionTitle.getStyleClass().add("section-title");
                TilePane activities = new TilePane(10, 10);
                activities.getStyleClass().add("course-activity-grid");
                activities.setPrefColumns(4);
                activities.setPrefTileWidth(180);
                activities.setMaxWidth(760);
                section.activities().forEach(activity -> activities.getChildren().add(activityButton(activity)));
                courseCard.getChildren().addAll(sectionTitle, activities);
            });
            courseList.getChildren().add(courseCard);
        }
        stateLabel.setText(AppI18n.get(snapshot.courses().isEmpty()
            ? "alpha2.courseMap.empty" : "alpha2.courseMap.local"));
    }

    private Button activityButton(CourseMapActivity activity) {
        Button button = new Button(activity.title() + "\n" + AppI18n.format(
            "alpha2.courseMap.activityMeta", difficultyLabel(activity), activity.estimatedMinutes()));
        button.setWrapText(true);
        button.setMinWidth(180);
        button.setPrefWidth(180);
        button.setMaxWidth(180);
        button.setDisable(!activity.enabled());
        button.setOnAction(ignored -> openActivity.accept(activity));
        button.getStyleClass().add("course-activity-button");
        return button;
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
