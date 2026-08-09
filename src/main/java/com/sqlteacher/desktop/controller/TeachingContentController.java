package com.sqlteacher.desktop.controller;
import com.sqlteacher.desktop.AppI18n;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.sqlteacher.application.collaboration.CloudApiClient;
import com.sqlteacher.application.collaboration.CloudNotification;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.ClassAssignment;
import com.sqlteacher.application.collaboration.ClassroomService;
import com.sqlteacher.application.collaboration.CourseCatalog;
import com.sqlteacher.application.collaboration.CourseSection;
import com.sqlteacher.application.collaboration.ContentStatus;
import com.sqlteacher.application.collaboration.DesktopAccessProfile;
import com.sqlteacher.application.collaboration.FeedbackStatus;
import com.sqlteacher.application.collaboration.FeedbackDraftEnhancer;
import com.sqlteacher.application.collaboration.FeedbackDraftStyle;
import com.sqlteacher.application.collaboration.KnowledgeMastery;
import com.sqlteacher.application.collaboration.KnowledgePoint;
import com.sqlteacher.application.collaboration.SharedExerciseVersion;
import com.sqlteacher.application.collaboration.SubmissionFeedback;
import com.sqlteacher.application.collaboration.TeachingContentCache;
import com.sqlteacher.application.collaboration.CachedCourseContent;
import com.sqlteacher.application.activity.ProjectPortfolioService;
import com.sqlteacher.application.planning.CourseObjective;
import com.sqlteacher.application.planning.ObjectiveResourceType;
import com.sqlteacher.application.planning.ObjectiveClassSummary;
import com.sqlteacher.application.planning.ObjectiveInterventionDraft;
import com.sqlteacher.desktop.DesktopExecutors;
import com.sqlteacher.desktop.DeadlineValueConverter;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** v1.4 desktop workflow for shared course content, feedback, mastery and notifications. */
public final class TeachingContentController {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final CloudApiClient api;
    private final CloudSessionService sessions;
    private final DesktopAccessProfile accessProfile;
    private final FeedbackDraftEnhancer feedbackDraftEnhancer;
    private final TeachingContentCache cache;
    private final ProjectPortfolioService portfolioService;
    private final AtomicBoolean running = new AtomicBoolean();

    @FXML private Label statusLabel;
    @FXML private TabPane workspaceTabs;
    @FXML private Tab teacherTab;
    @FXML private VBox teacherPane;
    @FXML private VBox feedbackEditorPane;
    @FXML private TextField courseNameField;
    @FXML private TextArea courseDescriptionField;
    @FXML private ListView<String> courseList;
    @FXML private TextField sectionNameField;
    @FXML private TextField sectionOrderField;
    @FXML private ListView<String> sectionList;
    @FXML private TextField knowledgePointNameField;
    @FXML private TextArea knowledgePointDescriptionField;
    @FXML private TextField knowledgePointOrderField;
    @FXML private ListView<String> knowledgePointList;
    @FXML private TextField localExerciseIdField;
    @FXML private TextField exerciseTitleField;
    @FXML private TextArea exercisePromptField;
    @FXML private TextField datasetVersionField;
    @FXML private TextField evaluationRuleField;
    @FXML private ListView<String> exerciseList;
    @FXML private TextField objectiveTitleField;
    @FXML private TextArea objectiveDescriptionField;
    @FXML private TextField objectiveCriteriaField;
    @FXML private TextField objectiveOrderField;
    @FXML private ListView<String> objectiveList;
    @FXML private ComboBox<SelectionOption> prerequisiteObjectiveCombo;
    @FXML private ComboBox<ObjectiveResourceType> objectiveResourceTypeCombo;
    @FXML private TextField objectiveResourceIdField;
    @FXML private ComboBox<SelectionOption> assignmentClassroomCombo;
    @FXML private ComboBox<SelectionOption> learningClassroomCombo;
    @FXML private TextField assignmentTitleField;
    @FXML private DatePicker assignmentDueDatePicker;
    @FXML private ComboBox<String> assignmentDueTimeCombo;
    @FXML private ComboBox<SelectionOption> learningAssignmentCombo;
    @FXML private TextField submissionIdField;
    @FXML private ComboBox<SelectionOption> learningStudentCombo;
    @FXML private ComboBox<FeedbackStatus> feedbackStatusCombo;
    @FXML private TextArea feedbackCommentField;
    @FXML private ComboBox<FeedbackDraftStyle> feedbackDraftStyleCombo;
    @FXML private ListView<String> feedbackList;
    @FXML private ListView<String> masteryList;
    @FXML private ListView<String> notificationList;
    @FXML private Button markReadButton;
    @FXML private ListView<String> objectiveSummaryList;
    @FXML private TextArea interventionActionField;
    @FXML private Button confirmInterventionButton;
    @FXML private ListView<String> portfolioList;

    private List<CourseCatalog> courses = List.of();
    private List<CourseSection> sections = List.of();
    private List<KnowledgePoint> knowledgePoints = List.of();
    private List<SharedExerciseVersion> exercises = List.of();
    private List<CourseObjective> objectives = List.of();
    private List<SubmissionFeedback> feedback = List.of();
    private List<CloudNotification> notifications = List.of();
    private List<ClassroomService.Classroom> learningClassrooms = List.of();
    private ObjectiveInterventionDraft pendingIntervention;

    public TeachingContentController(CloudApiClient api, CloudSessionService sessions,
                                     DesktopAccessProfile accessProfile, FeedbackDraftEnhancer feedbackDraftEnhancer,
                                     TeachingContentCache cache, ProjectPortfolioService portfolioService) {
        this.api = java.util.Objects.requireNonNull(api);
        this.sessions = java.util.Objects.requireNonNull(sessions);
        this.accessProfile = java.util.Objects.requireNonNull(accessProfile);
        this.feedbackDraftEnhancer = java.util.Objects.requireNonNull(feedbackDraftEnhancer);
        this.cache = java.util.Objects.requireNonNull(cache);
        this.portfolioService = java.util.Objects.requireNonNull(portfolioService);
    }

    @FXML
    private void initialize() {
        boolean teacher = isTeacher();
        if (!teacher) workspaceTabs.getTabs().remove(teacherTab);
        feedbackEditorPane.setVisible(teacher);
        feedbackEditorPane.setManaged(teacher);
        feedbackStatusCombo.getItems().setAll(FeedbackStatus.values());
        feedbackStatusCombo.setValue(FeedbackStatus.NEEDS_WORK);
        feedbackDraftStyleCombo.getItems().setAll(FeedbackDraftStyle.values());
        feedbackDraftStyleCombo.setValue(FeedbackDraftStyle.CONCISE);
        assignmentDueTimeCombo.getItems().setAll(DeadlineValueConverter.timeOptions());
        assignmentDueTimeCombo.setValue(DeadlineValueConverter.DEFAULT_TIME);
        assignmentClassroomCombo.setPromptText(AppI18n.get("TeachingContentController.1"));
        learningClassroomCombo.setPromptText(AppI18n.get("TeachingContentController.2"));
        learningAssignmentCombo.setPromptText(AppI18n.get("TeachingContentController.3"));
        learningStudentCombo.setPromptText(AppI18n.get("TeachingContentController.4"));
        learningStudentCombo.setVisible(teacher);
        learningStudentCombo.setManaged(teacher);
        courseList.setPlaceholder(new Label(AppI18n.get("TeachingContentController.5")));
        sectionList.setPlaceholder(new Label(AppI18n.get("TeachingContentController.6")));
        knowledgePointList.setPlaceholder(new Label(AppI18n.get("TeachingContentController.7")));
        exerciseList.setPlaceholder(new Label(AppI18n.get("TeachingContentController.8")));
        objectiveList.setPlaceholder(new Label(AppI18n.get("TeachingContentController.9")));
        objectiveResourceTypeCombo.getItems().setAll(ObjectiveResourceType.values());
        objectiveResourceTypeCombo.setValue(ObjectiveResourceType.KNOWLEDGE_POINT);
        feedbackList.setPlaceholder(new Label(AppI18n.get("TeachingContentController.10")));
        masteryList.setPlaceholder(new Label(AppI18n.get("TeachingContentController.11")));
        notificationList.setPlaceholder(new Label(AppI18n.get("TeachingContentController.12")));
        objectiveSummaryList.setPlaceholder(new Label(AppI18n.get("TeachingContentController.13")));
        confirmInterventionButton.setDisable(true);
        courseList.getSelectionModel().selectedIndexProperty().addListener((ignored, oldValue, newValue) -> {
            int index = newValue.intValue();
            if (index >= 0 && index < courses.size()) {
                CourseCatalog selected = courses.get(index);
                courseNameField.setText(selected.name());
                courseDescriptionField.setText(selected.description());
                refreshSelectedCourse();
            }
        });
        sectionList.getSelectionModel().selectedIndexProperty().addListener((ignored, oldValue, newValue) -> {
            int index = newValue.intValue();
            if (index >= 0 && index < sections.size()) {
                CourseSection selected = sections.get(index);
                sectionNameField.setText(selected.name());
                sectionOrderField.setText(Integer.toString(selected.sortOrder()));
            }
        });
        knowledgePointList.getSelectionModel().selectedIndexProperty().addListener((ignored, oldValue, newValue) -> {
            int index = newValue.intValue();
            if (index >= 0 && index < knowledgePoints.size()) {
                KnowledgePoint selected = knowledgePoints.get(index);
                knowledgePointNameField.setText(selected.name());
                knowledgePointDescriptionField.setText(selected.description());
                knowledgePointOrderField.setText(Integer.toString(selected.sortOrder()));
            }
        });
        exerciseList.getSelectionModel().selectedIndexProperty().addListener((ignored, oldValue, newValue) -> {
            int index = newValue.intValue();
            if (index >= 0 && index < exercises.size()) assignmentTitleField.setText(exercises.get(index).title());
        });
        feedbackList.getSelectionModel().selectedIndexProperty().addListener((ignored, oldValue, newValue) -> {
            int index = newValue.intValue();
            if (index >= 0 && index < feedback.size()) {
                SubmissionFeedback selected = feedback.get(index);
                submissionIdField.setText(selected.submissionId());
                selectOption(learningStudentCombo, selected.studentUserId());
                feedbackStatusCombo.setValue(selected.status());
                feedbackCommentField.setText(selected.comment());
            }
        });
        notificationList.getSelectionModel().selectedIndexProperty().addListener((ignored, oldValue, newValue) ->
            markReadButton.setDisable(newValue.intValue() < 0));
        learningClassroomCombo.valueProperty().addListener((ignored, oldValue, selected) -> {
            applyLearningStudents(selected == null ? null : selected.id());
            if (selected != null && !running.get()) loadLearningAssignments(selected.id());
        });
        objectiveList.getSelectionModel().selectedIndexProperty().addListener((ignored, oldValue, newValue) -> {
            int index = newValue.intValue();
            if (index >= 0 && index < objectives.size()) {
                CourseObjective selected = objectives.get(index);
                objectiveTitleField.setText(selected.title());
                objectiveDescriptionField.setText(selected.description());
                objectiveCriteriaField.setText(selected.completionCriteria());
                objectiveOrderField.setText(Integer.toString(selected.sortOrder()));
            }
        });
        onRefresh();
    }

    @FXML
    private void onRefresh() {
        run(AppI18n.get("TeachingContentController.14"), () -> {
            String token = token();
            String account = accountId();
            List<CourseCatalog> loadedCourses;
            List<CloudNotification> loadedNotifications;
            List<ClassroomService.Classroom> loadedClassrooms;
            List<ClassAssignment> loadedAssignments = List.of();
            boolean offline = false;
            try {
                loadedCourses = isTeacher() ? api.listCourses(token) : List.of();
                loadedNotifications = api.listNotifications(token, 0, 50);
                loadedClassrooms = api.listClasses(token);
                if (!loadedClassrooms.isEmpty()) {
                    loadedAssignments = api.listAssignments(token, loadedClassrooms.getFirst().id());
                }
                if (isTeacher()) cache.saveCourses(account, loadedCourses);
                cache.saveNotifications(account, loadedNotifications);
            } catch (RuntimeException error) {
                loadedCourses = isTeacher() ? cache.loadCourses(account) : List.of();
                loadedNotifications = cache.loadNotifications(account);
                loadedClassrooms = List.of();
                offline = true;
            }
            boolean cached = offline;
            List<CourseCatalog> uiCourses = List.copyOf(loadedCourses);
            List<CloudNotification> uiNotifications = List.copyOf(loadedNotifications);
            List<ClassroomService.Classroom> uiClassrooms = List.copyOf(loadedClassrooms);
            List<ClassAssignment> uiAssignments = List.copyOf(loadedAssignments);
            Platform.runLater(() -> {
                courses = uiCourses;
                courseList.getItems().setAll(uiCourses.stream().map(this::courseLabel).toList());
                notifications = uiNotifications;
                renderNotifications();
                applyLearningFilters(uiClassrooms, uiAssignments);
                showStatus(cached ? AppI18n.get("TeachingContentController.15") : AppI18n.get("TeachingContentController.16"), false);
            });
        });
    }

    @FXML
    private void onCreateCourse() {
        requireTeacher();
        String name = required(courseNameField.getText(), AppI18n.get("TeachingContentController.17"));
        if (name == null) return;
        run(AppI18n.get("TeachingContentController.18"), () -> {
            CourseCatalog created = api.createCourse(token(), name, courseDescriptionField.getText());
            List<CourseCatalog> loaded = api.listCourses(token());
            cache.saveCourses(accountId(), loaded);
            Platform.runLater(() -> {
                courses = loaded;
                courseList.getItems().setAll(loaded.stream().map(this::courseLabel).toList());
                selectCourse(created.id());
                courseNameField.clear();
                courseDescriptionField.clear();
                showStatus(AppI18n.get("TeachingContentController.19"), false);
            });
        });
    }

    @FXML
    private void onCreateSection() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        String name = required(sectionNameField.getText(), AppI18n.get("TeachingContentController.20"));
        if (course == null || name == null) return;
        run(AppI18n.get("TeachingContentController.21"), () -> {
            api.createCourseSection(token(), course.id(), name, nonNegativeOrder(sectionOrderField.getText(), sections.size()));
            loadCourseDetails(course.id(), AppI18n.get("TeachingContentController.22"));
            Platform.runLater(sectionNameField::clear);
        });
    }

    @FXML
    private void onSaveCourse() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        String name = required(courseNameField.getText(), AppI18n.get("TeachingContentController.23"));
        if (course == null || name == null) return;
        run(AppI18n.get("TeachingContentController.24"), () -> {
            CourseCatalog updated = api.updateCourse(token(), course.id(), name, courseDescriptionField.getText(),
                course.status(), course.version());
            List<CourseCatalog> loaded = api.listCourses(token());
            cache.saveCourses(accountId(), loaded);
            Platform.runLater(() -> {
                courses = loaded;
                courseList.getItems().setAll(loaded.stream().map(this::courseLabel).toList());
                selectCourse(updated.id());
                showStatus(AppI18n.get("TeachingContentController.25"), false);
            });
        });
    }

    @FXML
    private void onToggleCourseStatus() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        if (course == null) return;
        ContentStatus next = course.status() == ContentStatus.ACTIVE ? ContentStatus.INACTIVE : ContentStatus.ACTIVE;
        run(AppI18n.get("TeachingContentController.26"), () -> {
            api.updateCourse(token(), course.id(), course.name(), course.description(), next, course.version());
            List<CourseCatalog> loaded = api.listCourses(token());
            cache.saveCourses(accountId(), loaded);
            Platform.runLater(() -> {
                courses = loaded;
                courseList.getItems().setAll(loaded.stream().map(this::courseLabel).toList());
                selectCourse(course.id());
                showStatus(next == ContentStatus.ACTIVE ? AppI18n.get("TeachingContentController.27") : AppI18n.get("TeachingContentController.28"), false);
            });
        });
    }

    @FXML
    private void onUpdateSection() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        CourseSection section = selectedSection();
        String name = required(sectionNameField.getText(), AppI18n.get("TeachingContentController.29"));
        if (course == null || section == null || name == null) return;
        run(AppI18n.get("TeachingContentController.30"), () -> {
            api.updateCourseSection(token(), course.id(), section.id(), name,
                nonNegativeOrder(sectionOrderField.getText(), section.sortOrder()), section.status(), section.version());
            loadCourseDetails(course.id(), AppI18n.get("TeachingContentController.31"));
        });
    }

    @FXML
    private void onToggleSectionStatus() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        CourseSection section = selectedSection();
        if (course == null || section == null) return;
        ContentStatus next = section.status() == ContentStatus.ACTIVE ? ContentStatus.INACTIVE : ContentStatus.ACTIVE;
        run(AppI18n.get("TeachingContentController.32"), () -> {
            api.updateCourseSection(token(), course.id(), section.id(), section.name(), section.sortOrder(), next,
                section.version());
            loadCourseDetails(course.id(), next == ContentStatus.ACTIVE ? AppI18n.get("TeachingContentController.33") : AppI18n.get("TeachingContentController.34"));
        });
    }

    @FXML
    private void onCreateKnowledgePoint() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        String name = required(knowledgePointNameField.getText(), AppI18n.get("TeachingContentController.35"));
        if (course == null || name == null) return;
        CourseSection section = selectedSection();
        run(AppI18n.get("TeachingContentController.36"), () -> {
            api.createKnowledgePoint(token(), course.id(), section == null ? null : section.id(), name,
                knowledgePointDescriptionField.getText(),
                nonNegativeOrder(knowledgePointOrderField.getText(), knowledgePoints.size()));
            loadCourseDetails(course.id(), AppI18n.get("TeachingContentController.37"));
            Platform.runLater(() -> {
                knowledgePointNameField.clear();
                knowledgePointDescriptionField.clear();
            });
        });
    }

    @FXML
    private void onUpdateKnowledgePoint() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        KnowledgePoint point = selectedKnowledgePoint();
        CourseSection section = selectedSection();
        String name = required(knowledgePointNameField.getText(), AppI18n.get("TeachingContentController.38"));
        if (course == null || point == null || name == null) return;
        run(AppI18n.get("TeachingContentController.39"), () -> {
            api.updateKnowledgePoint(token(), course.id(), point.id(), section == null ? point.sectionId() : section.id(),
                name, knowledgePointDescriptionField.getText(),
                nonNegativeOrder(knowledgePointOrderField.getText(), point.sortOrder()), point.status(), point.version());
            loadCourseDetails(course.id(), AppI18n.get("TeachingContentController.40"));
        });
    }

    @FXML
    private void onToggleKnowledgePointStatus() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        KnowledgePoint point = selectedKnowledgePoint();
        if (course == null || point == null) return;
        ContentStatus next = point.status() == ContentStatus.ACTIVE ? ContentStatus.INACTIVE : ContentStatus.ACTIVE;
        run(AppI18n.get("TeachingContentController.41"), () -> {
            api.updateKnowledgePoint(token(), course.id(), point.id(), point.sectionId(), point.name(),
                point.description(), point.sortOrder(), next, point.version());
            loadCourseDetails(course.id(), next == ContentStatus.ACTIVE ? AppI18n.get("TeachingContentController.42") : AppI18n.get("TeachingContentController.43"));
        });
    }

    @FXML
    private void onPublishExercise() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        String title = required(exerciseTitleField.getText(), AppI18n.get("TeachingContentController.44"));
        String prompt = required(exercisePromptField.getText(), AppI18n.get("TeachingContentController.45"));
        String dataset = required(datasetVersionField.getText(), AppI18n.get("TeachingContentController.46"));
        String rule = required(evaluationRuleField.getText(), AppI18n.get("TeachingContentController.47"));
        if (course == null || title == null || prompt == null || dataset == null || rule == null) return;
        KnowledgePoint point = selectedKnowledgePoint();
        List<String> pointIds = point == null ? List.of() : List.of(point.id());
        run(AppI18n.get("TeachingContentController.48"), () -> {
            api.publishSharedExercise(token(), course.id(), blankToNull(localExerciseIdField.getText()), title,
                prompt, dataset, rule, pointIds, UUID.randomUUID().toString());
            loadCourseDetails(course.id(), AppI18n.get("TeachingContentController.49"));
        });
    }

    @FXML
    private void onCreateAssignmentSnapshot() {
        requireTeacher();
        SharedExerciseVersion exercise = selectedExercise();
        String classroomId = selectedId(assignmentClassroomCombo, AppI18n.get("TeachingContentController.50"));
        if (exercise == null || classroomId == null) return;
        Instant dueAt = DeadlineValueConverter.toInstant(
            assignmentDueDatePicker.getValue(), assignmentDueTimeCombo.getValue(), ZoneId.systemDefault()
        );
        run(AppI18n.get("TeachingContentController.51"), () -> {
            var assignment = api.createAssignmentFromVersion(token(), classroomId, exercise.id(),
                assignmentTitleField.getText(), AppI18n.get("TeachingContentController.52") + exercise.version(), dueAt,
                UUID.randomUUID().toString());
            List<ClassAssignment> loaded = api.listAssignments(token(), classroomId);
            Platform.runLater(() -> {
                selectOption(learningClassroomCombo, classroomId);
                applyLearningAssignments(loaded);
                selectOption(learningAssignmentCombo, assignment.id());
                showStatus(AppI18n.get("TeachingContentController.53"), false);
            });
        });
    }

    @FXML
    private void onToggleExerciseStatus() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        SharedExerciseVersion exercise = selectedExercise();
        if (course == null || exercise == null) return;
        ContentStatus next = exercise.status() == ContentStatus.ACTIVE ? ContentStatus.INACTIVE : ContentStatus.ACTIVE;
        run(AppI18n.get("TeachingContentController.54"), () -> {
            api.setSharedExerciseStatus(token(), course.id(), exercise.exerciseId(), next);
            loadCourseDetails(course.id(), next == ContentStatus.ACTIVE ? AppI18n.get("TeachingContentController.55") : AppI18n.get("TeachingContentController.56"));
        });
    }

    @FXML
    private void onRefreshLearning() {
        String classroomId = selectedId(learningClassroomCombo, AppI18n.get("TeachingContentController.57"));
        if (classroomId == null) return;
        String assignmentId = selectedId(learningAssignmentCombo, AppI18n.get("TeachingContentController.58"));
        if (assignmentId == null) return;
        run(AppI18n.get("TeachingContentController.59"), () -> {
            String requestedStudent = isTeacher() ? optionalSelectedId(learningStudentCombo) : null;
            String account = accountId();
            List<SubmissionFeedback> loadedFeedback;
            List<KnowledgeMastery> mastery;
            boolean offline = false;
            try {
                loadedFeedback = assignmentId == null ? List.of()
                    : api.listSubmissionFeedback(token(), classroomId, assignmentId);
                mastery = requestedStudent == null && isTeacher()
                    ? List.of() : api.getKnowledgeMastery(token(), classroomId, requestedStudent);
                if (assignmentId != null) cache.saveFeedback(account, assignmentId, loadedFeedback);
                if (!(requestedStudent == null && isTeacher())) {
                    cache.saveMastery(account, classroomId, requestedStudent, mastery);
                }
            } catch (RuntimeException error) {
                loadedFeedback = assignmentId == null ? List.of() : cache.loadFeedback(account, assignmentId);
                mastery = requestedStudent == null && isTeacher() ? List.of()
                    : cache.loadMastery(account, classroomId, requestedStudent);
                offline = true;
            }
            boolean cached = offline;
            List<SubmissionFeedback> uiFeedback = List.copyOf(loadedFeedback);
            List<KnowledgeMastery> uiMastery = List.copyOf(mastery);
            Platform.runLater(() -> {
                feedback = uiFeedback;
                feedbackList.getItems().setAll(uiFeedback.stream().map(this::feedbackLabel).toList());
                masteryList.getItems().setAll(uiMastery.stream().map(this::masteryLabel).toList());
                showStatus(cached ? AppI18n.get("TeachingContentController.60") : AppI18n.get("TeachingContentController.61"), false);
            });
        });
    }

    @FXML
    private void onDraftFeedback() {
        requireTeacher();
        String classroomId = selectedId(learningClassroomCombo, AppI18n.get("TeachingContentController.62"));
        String assignmentId = selectedId(learningAssignmentCombo, AppI18n.get("TeachingContentController.63"));
        String submissionId = required(submissionIdField.getText(), AppI18n.get("TeachingContentController.64"));
        if (classroomId == null || assignmentId == null || submissionId == null) return;
        run(AppI18n.get("TeachingContentController.65"), () -> {
            var deterministic = api.draftSubmissionFeedback(token(), classroomId, assignmentId, submissionId);
            var preview = feedbackDraftEnhancer.preview(deterministic);
            Platform.runLater(() -> {
                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION);
                alert.setTitle(AppI18n.get("TeachingContentController.66"));
                alert.setHeaderText(AppI18n.get("TeachingContentController.67") + preview.characterCount() + AppI18n.get("TeachingContentController.68"));
                alert.setContentText(AppI18n.get("TeachingContentController.69") + String.join(AppI18n.get("TeachingContentController.70"), preview.sources()) + AppI18n.get("TeachingContentController.71"));
                if (alert.showAndWait().filter(javafx.scene.control.ButtonType.OK::equals).isEmpty()) {
                    feedbackCommentField.setText(deterministic.text());
                    showStatus(AppI18n.get("TeachingContentController.72"), false);
                    return;
                }
                run(AppI18n.get("TeachingContentController.73"), () -> {
                    var draft = feedbackDraftEnhancer.enhance(deterministic, feedbackDraftStyleCombo.getValue());
                    Platform.runLater(() -> {
                        feedbackCommentField.setText(draft.text());
                        showStatus(draft.aiGenerated() ? AppI18n.get("TeachingContentController.74") : AppI18n.get("TeachingContentController.75"), false);
                    });
                });
            });
        });
    }

    @FXML
    private void onRefreshObjectiveSummary() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        String classroomId = selectedId(learningClassroomCombo, AppI18n.get("TeachingContentController.76"));
        if (course == null || classroomId == null) return;
        run(AppI18n.get("TeachingContentController.77"), () -> {
            List<ObjectiveClassSummary> loaded = api.getObjectiveClassSummary(token(), course.id(), classroomId);
            Platform.runLater(() -> {
                objectiveSummaryList.getItems().setAll(loaded.stream().map(item -> item.objectiveTitle()
                    + AppI18n.get("TeachingContentController.78") + item.unknown() + AppI18n.get("TeachingContentController.79") + item.needsSupport() + AppI18n.get("TeachingContentController.80")
                    + item.developing() + AppI18n.get("TeachingContentController.81") + item.mastered()).toList());
                showStatus(AppI18n.get("TeachingContentController.82"), false);
            });
        });
    }

    @FXML
    private void onPrepareObjectiveIntervention() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        CourseObjective objective = selectedObjective();
        String classroomId = selectedId(learningClassroomCombo, AppI18n.get("TeachingContentController.83"));
        String action = required(interventionActionField.getText(), AppI18n.get("TeachingContentController.84"));
        if (course == null || objective == null || classroomId == null || action == null) return;
        run(AppI18n.get("TeachingContentController.85"), () -> {
            ObjectiveInterventionDraft draft = api.createObjectiveInterventionDraft(token(), course.id(), classroomId,
                objective.id(), "OBJECTIVE_EVIDENCE_GAP", action);
            Platform.runLater(() -> {
                pendingIntervention = draft;
                confirmInterventionButton.setDisable(false);
                showStatus(AppI18n.get("TeachingContentController.86") + draft.impactCount() + AppI18n.get("TeachingContentController.87"), false);
            });
        });
    }

    @FXML
    private void onConfirmObjectiveIntervention() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        ObjectiveInterventionDraft draft = pendingIntervention;
        if (course == null || draft == null) {
            showStatus(AppI18n.get("TeachingContentController.88"), true); return;
        }
        run(AppI18n.get("TeachingContentController.89"), () -> {
            api.confirmObjectiveInterventionDraft(token(), course.id(), draft.id(), draft.confirmationToken());
            Platform.runLater(() -> {
                pendingIntervention = null;
                confirmInterventionButton.setDisable(true);
                showStatus(AppI18n.get("TeachingContentController.90"), false);
            });
        });
    }

    @FXML
    private void onCreateObjective() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        String title = required(objectiveTitleField.getText(), AppI18n.get("TeachingContentController.91"));
        String criteria = required(objectiveCriteriaField.getText(), AppI18n.get("TeachingContentController.92"));
        if (course == null || title == null || criteria == null) return;
        run(AppI18n.get("TeachingContentController.93"), () -> {
            api.createCourseObjective(token(), course.id(), title, objectiveDescriptionField.getText(), criteria,
                nonNegativeOrder(objectiveOrderField.getText(), objectives.size()));
            loadCourseDetails(course.id(), AppI18n.get("TeachingContentController.94"));
        });
    }

    @FXML
    private void onSaveObjective() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        CourseObjective objective = selectedObjective();
        String title = required(objectiveTitleField.getText(), AppI18n.get("TeachingContentController.95"));
        String criteria = required(objectiveCriteriaField.getText(), AppI18n.get("TeachingContentController.96"));
        if (course == null || objective == null || title == null || criteria == null) return;
        run(AppI18n.get("TeachingContentController.97"), () -> {
            api.updateCourseObjective(token(), course.id(), objective.id(), title, objectiveDescriptionField.getText(),
                criteria, nonNegativeOrder(objectiveOrderField.getText(), objective.sortOrder()), objective.status(),
                objective.version());
            loadCourseDetails(course.id(), AppI18n.get("TeachingContentController.98"));
        });
    }

    @FXML
    private void onToggleObjectiveStatus() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        CourseObjective objective = selectedObjective();
        if (course == null || objective == null) return;
        ContentStatus next = objective.status() == ContentStatus.ACTIVE ? ContentStatus.INACTIVE : ContentStatus.ACTIVE;
        run(AppI18n.get("TeachingContentController.99"), () -> {
            api.updateCourseObjective(token(), course.id(), objective.id(), objective.title(), objective.description(),
                objective.completionCriteria(), objective.sortOrder(), next, objective.version());
            loadCourseDetails(course.id(), next == ContentStatus.ACTIVE ? AppI18n.get("TeachingContentController.100") : AppI18n.get("TeachingContentController.101"));
        });
    }

    @FXML
    private void onAddObjectivePrerequisite() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        CourseObjective objective = selectedObjective();
        SelectionOption prerequisite = prerequisiteObjectiveCombo.getValue();
        if (course == null || objective == null || prerequisite == null) {
            showStatus(AppI18n.get("TeachingContentController.102"), true); return;
        }
        run(AppI18n.get("TeachingContentController.103"), () -> {
            api.addObjectivePrerequisite(token(), course.id(), objective.id(), prerequisite.id());
            Platform.runLater(() -> showStatus(AppI18n.get("TeachingContentController.104"), false));
        });
    }

    @FXML
    private void onBindObjectiveResource() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        CourseObjective objective = selectedObjective();
        ObjectiveResourceType type = objectiveResourceTypeCombo.getValue();
        String resourceId = blankToNull(objectiveResourceIdField.getText());
        if (resourceId == null && type == ObjectiveResourceType.KNOWLEDGE_POINT && selectedKnowledgePoint() != null) {
            resourceId = selectedKnowledgePoint().id();
        }
        if (resourceId == null && type == ObjectiveResourceType.EXERCISE_VERSION && selectedExercise() != null) {
            resourceId = selectedExercise().id();
        }
        if (course == null || objective == null || type == null || resourceId == null) {
            showStatus(AppI18n.get("TeachingContentController.105"), true); return;
        }
        String selectedResourceId = resourceId;
        run(AppI18n.get("TeachingContentController.106"), () -> {
            api.addObjectiveResource(token(), course.id(), objective.id(), type, selectedResourceId);
            Platform.runLater(() -> showStatus(AppI18n.get("TeachingContentController.107"), false));
        });
    }

    @FXML
    private void onSaveFeedback() {
        requireTeacher();
        String classroomId = selectedId(learningClassroomCombo, AppI18n.get("TeachingContentController.108"));
        String assignmentId = selectedId(learningAssignmentCombo, AppI18n.get("TeachingContentController.109"));
        String submissionId = required(submissionIdField.getText(), AppI18n.get("TeachingContentController.110"));
        if (classroomId == null || assignmentId == null || submissionId == null) return;
        long expectedVersion = feedback.stream().filter(item -> item.submissionId().equals(submissionId))
            .mapToLong(SubmissionFeedback::version).findFirst().orElse(0);
        KnowledgePoint point = selectedKnowledgePoint();
        List<String> pointIds = point == null ? List.of() : List.of(point.id());
        run(AppI18n.get("TeachingContentController.111"), () -> {
            api.saveSubmissionFeedback(token(), classroomId, assignmentId, submissionId,
                feedbackStatusCombo.getValue(), feedbackCommentField.getText(), pointIds, expectedVersion,
                UUID.randomUUID().toString());
            List<SubmissionFeedback> loaded = api.listSubmissionFeedback(token(), classroomId, assignmentId);
            cache.saveFeedback(accountId(), assignmentId, loaded);
            Platform.runLater(() -> {
                feedback = loaded;
                feedbackList.getItems().setAll(loaded.stream().map(this::feedbackLabel).toList());
                showStatus(AppI18n.get("TeachingContentController.112"), false);
            });
        });
    }

    @FXML
    private void onRefreshNotifications() {
        run(AppI18n.get("TeachingContentController.113"), () -> {
            List<CloudNotification> loaded;
            boolean offline = false;
            try {
                loaded = api.listNotifications(token(), 0, 50);
                cache.saveNotifications(accountId(), loaded);
            } catch (RuntimeException error) {
                loaded = cache.loadNotifications(accountId());
                offline = true;
            }
            boolean cached = offline;
            List<CloudNotification> uiNotifications = List.copyOf(loaded);
            Platform.runLater(() -> {
                notifications = uiNotifications;
                renderNotifications();
                showStatus(cached ? AppI18n.get("TeachingContentController.114") : AppI18n.get("TeachingContentController.115"), false);
            });
        });
    }

    @FXML
    private void onMarkNotificationRead() {
        int index = notificationList.getSelectionModel().getSelectedIndex();
        if (index < 0 || index >= notifications.size()) return;
        String id = notifications.get(index).id();
        run(AppI18n.get("TeachingContentController.116"), () -> {
            api.markNotificationRead(token(), id);
            List<CloudNotification> loaded = api.listNotifications(token(), 0, 50);
            cache.saveNotifications(accountId(), loaded);
            Platform.runLater(() -> {
                notifications = loaded;
                renderNotifications();
                showStatus(AppI18n.get("TeachingContentController.117"), false);
            });
        });
    }

    @FXML
    private void onExportCourse() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        if (course == null) return;
        TextInputDialog licenseDialog = new TextInputDialog("CC-BY-4.0");
        licenseDialog.setTitle(AppI18n.get("alpha7.packageLicenseTitle"));
        licenseDialog.setHeaderText(AppI18n.get("alpha7.packageLicenseHeader"));
        licenseDialog.setContentText(AppI18n.get("alpha7.packageLicensePrompt"));
        String license = licenseDialog.showAndWait().map(String::trim).filter(value -> !value.isBlank()).orElse(null);
        if (license == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle(AppI18n.get("TeachingContentController.118"));
        chooser.setInitialFileName("sqlteacher-course-" + course.id() + ".json");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        var file = chooser.showSaveDialog(courseList.getScene().getWindow());
        if (file == null) return;
        run(AppI18n.get("TeachingContentController.119"), () -> {
            String payload = api.exportCourseBundle(token(), course.id());
            String packageJson = JSON.writeValueAsString(Map.of(
                "formatVersion", 2,
                "packageId", "course:" + course.id(),
                "courseVersion", Long.toString(course.version()),
                "license", license,
                "contentSha256", sha256(payload),
                "payloadJson", payload
            ));
            Files.writeString(file.toPath(), packageJson, StandardCharsets.UTF_8);
            Platform.runLater(() -> showStatus(AppI18n.get("TeachingContentController.120"), false));
        });
    }

    @FXML
    private void onImportCourse() {
        requireTeacher();
        FileChooser chooser = new FileChooser();
        chooser.setTitle(AppI18n.get("TeachingContentController.121"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        var file = chooser.showOpenDialog(courseList.getScene().getWindow());
        if (file == null) return;
        run(AppI18n.get("TeachingContentController.122"), () -> {
            String packageJson = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            var preview = api.previewCoursePackage(token(), packageJson);
            var decision = new java.util.concurrent.atomic.AtomicBoolean();
            var shown = new java.util.concurrent.CountDownLatch(1);
            Platform.runLater(() -> {
                Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                    AppI18n.format("alpha7.packagePreview", preview.courseTitle(), preview.courseVersion(),
                        preview.license(), preview.sections(), preview.knowledgePoints(), preview.exercises(),
                        preview.contentSha256(), preview.conflict()), ButtonType.CANCEL, ButtonType.OK);
                confirmation.setTitle(AppI18n.get("alpha7.packagePreviewTitle"));
                confirmation.setHeaderText(AppI18n.get("alpha7.packagePreviewHeader"));
                decision.set(confirmation.showAndWait().filter(ButtonType.OK::equals).isPresent());
                shown.countDown();
            });
            try {
                shown.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(AppI18n.get("alpha7.packageImportInterrupted"), error);
            }
            if (!decision.get()) {
                Platform.runLater(() -> showStatus(AppI18n.get("alpha7.packageImportCancelled"), false));
                return;
            }
            var result = api.importCoursePackage(token(), packageJson, UUID.randomUUID().toString(),
                preview.contentSha256(), true);
            List<CourseCatalog> loaded = api.listCourses(token());
            cache.saveCourses(accountId(), loaded);
            Platform.runLater(() -> {
                courses = loaded;
                courseList.getItems().setAll(loaded.stream().map(this::courseLabel).toList());
                selectCourse(result.courseId());
                showStatus(AppI18n.get("TeachingContentController.123") + result.exercises() + AppI18n.get("TeachingContentController.124"), false);
            });
        });
    }

    @FXML
    private void onRefreshPortfolio() {
        run(AppI18n.get("alpha7.portfolioLoading"), () -> {
            var entries = portfolioService.listOwnEntries();
            Platform.runLater(() -> {
                portfolioList.getItems().setAll(entries.stream().map(item -> AppI18n.format("alpha7.portfolioEntry",
                    item.title(), item.submissionVersion(), item.gateStatus(), item.reviewState(),
                    item.artifactSha256().substring(0, 12))).toList());
                showStatus(AppI18n.format("alpha7.portfolioLoaded", entries.size()), false);
            });
        });
    }

    @FXML
    private void onExportPortfolio() {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION, AppI18n.get("alpha7.portfolioExportConfirm"),
            ButtonType.CANCEL, ButtonType.OK);
        confirmation.setTitle(AppI18n.get("alpha7.portfolioExportTitle"));
        if (confirmation.showAndWait().filter(ButtonType.OK::equals).isEmpty()) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle(AppI18n.get("alpha7.portfolioExportTitle"));
        chooser.setInitialFileName("sqlteacher-private-portfolio.json");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        var file = chooser.showSaveDialog(portfolioList.getScene().getWindow());
        if (file == null) return;
        run(AppI18n.get("alpha7.portfolioExporting"), () -> {
            Files.writeString(file.toPath(), portfolioService.exportOwnPortfolio(true), StandardCharsets.UTF_8);
            Platform.runLater(() -> showStatus(AppI18n.get("alpha7.portfolioExported"), false));
        });
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private void refreshSelectedCourse() {
        CourseCatalog selected = selectedCourse();
        if (selected == null || running.get()) return;
        run(AppI18n.get("TeachingContentController.125"), () -> loadCourseDetails(selected.id(), AppI18n.get("TeachingContentController.126")));
    }

    private void loadCourseDetails(String courseId, String message) {
        List<CourseSection> loadedSections;
        List<KnowledgePoint> loadedPoints;
        List<SharedExerciseVersion> loadedExercises;
        List<CourseObjective> loadedObjectives;
        boolean offline = false;
        try {
            loadedSections = api.listCourseSections(token(), courseId);
            loadedPoints = api.listKnowledgePoints(token(), courseId);
            loadedExercises = api.listSharedExercises(token(), courseId, null);
            loadedObjectives = api.listCourseObjectives(token(), courseId);
            cache.saveCourseContent(accountId(), courseId,
                new CachedCourseContent(loadedSections, loadedPoints, loadedExercises));
        } catch (RuntimeException error) {
            CachedCourseContent cached = cache.loadCourseContent(accountId(), courseId);
            loadedSections = cached.sections();
            loadedPoints = cached.knowledgePoints();
            loadedExercises = cached.exercises();
            loadedObjectives = List.of();
            offline = true;
        }
        boolean cached = offline;
        List<CourseSection> uiSections = List.copyOf(loadedSections);
        List<KnowledgePoint> uiPoints = List.copyOf(loadedPoints);
        List<SharedExerciseVersion> uiExercises = List.copyOf(loadedExercises);
        List<CourseObjective> uiObjectives = List.copyOf(loadedObjectives);
        Platform.runLater(() -> {
            sections = uiSections;
            knowledgePoints = uiPoints;
            exercises = uiExercises;
            objectives = uiObjectives;
            sectionList.getItems().setAll(uiSections.stream().map(item -> item.sortOrder() + " · " + item.name()).toList());
            knowledgePointList.getItems().setAll(uiPoints.stream().map(item -> item.sortOrder() + " · " + item.name()).toList());
            exerciseList.getItems().setAll(uiExercises.stream()
                .map(item -> item.title() + " · v" + item.version() + " · " + item.contentHash().substring(0, 8)).toList());
            objectiveList.getItems().setAll(uiObjectives.stream().map(item -> item.sortOrder() + " · "
                + item.title() + " · " + item.status() + " · v" + item.version()).toList());
            prerequisiteObjectiveCombo.getItems().setAll(uiObjectives.stream()
                .map(item -> new SelectionOption(item.id(), item.title())).toList());
            showStatus(cached ? AppI18n.get("TeachingContentController.127") : message, false);
        });
    }

    private void renderNotifications() {
        notificationList.getItems().setAll(notifications.stream().map(item ->
            (item.unread() ? "● " : "○ ") + item.title() + " · " + item.createdAt()).toList());
        markReadButton.setDisable(notificationList.getSelectionModel().getSelectedIndex() < 0);
    }

    private void applyLearningFilters(List<ClassroomService.Classroom> classrooms,
                                      List<ClassAssignment> initialAssignments) {
        learningClassrooms = List.copyOf(classrooms);
        List<SelectionOption> classOptions = classrooms.stream()
            .map(item -> new SelectionOption(item.id(), item.name()))
            .toList();
        assignmentClassroomCombo.getItems().setAll(classOptions);
        learningClassroomCombo.getItems().setAll(classOptions);
        if (!classOptions.isEmpty()) {
            assignmentClassroomCombo.getSelectionModel().selectFirst();
            learningClassroomCombo.getSelectionModel().selectFirst();
            applyLearningStudents(classOptions.getFirst().id());
        }
        applyLearningAssignments(initialAssignments);
    }

    private void loadLearningAssignments(String classroomId) {
        run(AppI18n.get("TeachingContentController.128"), () -> {
            List<ClassAssignment> loaded = api.listAssignments(token(), classroomId);
            Platform.runLater(() -> {
                applyLearningAssignments(loaded);
                showStatus(AppI18n.get("TeachingContentController.129"), false);
            });
        });
    }

    private void applyLearningAssignments(List<ClassAssignment> assignments) {
        learningAssignmentCombo.getItems().setAll(assignments.stream()
            .map(item -> new SelectionOption(item.id(), item.title() + " · " + item.status()))
            .toList());
        if (!learningAssignmentCombo.getItems().isEmpty()) {
            learningAssignmentCombo.getSelectionModel().selectFirst();
        }
    }

    private void applyLearningStudents(String classroomId) {
        ClassroomService.Classroom classroom = learningClassrooms.stream()
            .filter(item -> item.id().equals(classroomId))
            .findFirst()
            .orElse(null);
        if (classroom == null) {
            learningStudentCombo.getItems().clear();
            return;
        }
        learningStudentCombo.getItems().setAll(classroom.members().stream()
            .filter(member -> member.role() == com.sqlteacher.application.collaboration.UserRole.STUDENT)
            .map(member -> new SelectionOption(member.userId(), AppI18n.get("TeachingContentController.130") + shortId(member.userId())))
            .toList());
        learningStudentCombo.getSelectionModel().clearSelection();
    }

    private String selectedId(ComboBox<SelectionOption> combo, String message) {
        SelectionOption selected = combo.getValue();
        if (selected == null) {
            showStatus(message, true);
            return null;
        }
        return selected.id();
    }

    private static String optionalSelectedId(ComboBox<SelectionOption> combo) {
        SelectionOption selected = combo.getValue();
        return selected == null ? null : selected.id();
    }

    private static void selectOption(ComboBox<SelectionOption> combo, String id) {
        combo.getItems().stream().filter(option -> option.id().equals(id)).findFirst()
            .ifPresent(combo::setValue);
    }

    private static String shortId(String id) {
        return id.length() <= 12 ? id : id.substring(0, 8) + "…";
    }

    private CourseCatalog selectedCourse() {
        int index = courseList.getSelectionModel().getSelectedIndex();
        if (index < 0 || index >= courses.size()) {
            showStatus(AppI18n.get("TeachingContentController.131"), true);
            return null;
        }
        return courses.get(index);
    }

    private CourseSection selectedSection() {
        int index = sectionList.getSelectionModel().getSelectedIndex();
        return index < 0 || index >= sections.size() ? null : sections.get(index);
    }

    private KnowledgePoint selectedKnowledgePoint() {
        int index = knowledgePointList.getSelectionModel().getSelectedIndex();
        return index < 0 || index >= knowledgePoints.size() ? null : knowledgePoints.get(index);
    }

    private SharedExerciseVersion selectedExercise() {
        int index = exerciseList.getSelectionModel().getSelectedIndex();
        if (index < 0 || index >= exercises.size()) {
            showStatus(AppI18n.get("TeachingContentController.132"), true);
            return null;
        }
        return exercises.get(index);
    }

    private void selectCourse(String id) {
        for (int index = 0; index < courses.size(); index++) {
            if (courses.get(index).id().equals(id)) {
                courseList.getSelectionModel().select(index);
                return;
            }
        }
    }

    private String courseLabel(CourseCatalog course) {
        return course.name() + " · " + course.status() + " · v" + course.version();
    }

    private String feedbackLabel(SubmissionFeedback item) {
        return item.status() + AppI18n.get("TeachingContentController.133") + item.submissionId() + " · v" + item.version();
    }

    private String masteryLabel(KnowledgeMastery item) {
        return item.knowledgePointName() + " · " + item.masteryPercent() + AppI18n.get("TeachingContentController.masterySuggestion")
            + item.recommendations().size() + AppI18n.get("TeachingContentController.134");
    }

    private String token() {
        return sessions.current().orElseThrow(() -> new IllegalStateException(AppI18n.get("TeachingContentController.135"))).accessToken();
    }

    private String accountId() {
        return sessions.current().orElseThrow(() -> new IllegalStateException(AppI18n.get("TeachingContentController.136"))).user().id();
    }

    private boolean isTeacher() {
        return accessProfile.kind() == DesktopAccessProfile.Kind.TEACHER
            || accessProfile.kind() == DesktopAccessProfile.Kind.ADMIN;
    }

    private void requireTeacher() {
        if (!isTeacher()) throw new SecurityException(AppI18n.get("TeachingContentController.137"));
    }

    private String required(String value, String message) {
        if (value == null || value.isBlank()) {
            showStatus(message, true);
            return null;
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private int nonNegativeOrder(String value, int defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(AppI18n.get("TeachingContentController.138"));
        }
    }

    private void run(String pending, CheckedRunnable action) {
        if (!running.compareAndSet(false, true)) return;
        showStatus(pending, false);
        DesktopExecutors.background().execute(() -> {
            try {
                action.run();
            } catch (Exception error) {
                Platform.runLater(() -> showStatus(safeMessage(error), true));
            } finally {
                running.set(false);
            }
        });
    }

    private void showStatus(String message, boolean error) {
        statusLabel.setText(message);
        statusLabel.getStyleClass().removeAll("status-info", "status-success", "status-error");
        statusLabel.getStyleClass().add(error ? "status-error" : "status-info");
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? AppI18n.get("TeachingContentController.139") : message;
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private CourseObjective selectedObjective() {
        int index = objectiveList.getSelectionModel().getSelectedIndex();
        if (index < 0 || index >= objectives.size()) {
            showStatus(AppI18n.get("TeachingContentController.140"), true);
            return null;
        }
        return objectives.get(index);
    }

    private record SelectionOption(String id, String label) {
        @Override public String toString() {
            return label;
        }
    }
}
