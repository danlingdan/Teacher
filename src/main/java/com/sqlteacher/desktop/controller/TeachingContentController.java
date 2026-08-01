package com.sqlteacher.desktop.controller;

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
import com.sqlteacher.application.planning.CourseObjective;
import com.sqlteacher.application.planning.ObjectiveResourceType;
import com.sqlteacher.application.planning.ObjectiveClassSummary;
import com.sqlteacher.application.planning.ObjectiveInterventionDraft;
import com.sqlteacher.desktop.DesktopExecutors;
import com.sqlteacher.desktop.DeadlineValueConverter;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** v1.4 desktop workflow for shared course content, feedback, mastery and notifications. */
public final class TeachingContentController {
    private final CloudApiClient api;
    private final CloudSessionService sessions;
    private final DesktopAccessProfile accessProfile;
    private final FeedbackDraftEnhancer feedbackDraftEnhancer;
    private final TeachingContentCache cache;
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
                                     TeachingContentCache cache) {
        this.api = java.util.Objects.requireNonNull(api);
        this.sessions = java.util.Objects.requireNonNull(sessions);
        this.accessProfile = java.util.Objects.requireNonNull(accessProfile);
        this.feedbackDraftEnhancer = java.util.Objects.requireNonNull(feedbackDraftEnhancer);
        this.cache = java.util.Objects.requireNonNull(cache);
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
        assignmentClassroomCombo.setPromptText("选择发布班级");
        learningClassroomCombo.setPromptText("选择班级");
        learningAssignmentCombo.setPromptText("选择任务");
        learningStudentCombo.setPromptText("全部学生");
        learningStudentCombo.setVisible(teacher);
        learningStudentCombo.setManaged(teacher);
        courseList.setPlaceholder(new Label("暂无云端课程"));
        sectionList.setPlaceholder(new Label("暂无章节"));
        knowledgePointList.setPlaceholder(new Label("暂无知识点"));
        exerciseList.setPlaceholder(new Label("暂无已发布题目版本"));
        objectiveList.setPlaceholder(new Label("暂无课程目标"));
        objectiveResourceTypeCombo.getItems().setAll(ObjectiveResourceType.values());
        objectiveResourceTypeCombo.setValue(ObjectiveResourceType.KNOWLEDGE_POINT);
        feedbackList.setPlaceholder(new Label("输入班级和任务后刷新反馈"));
        masteryList.setPlaceholder(new Label("输入班级后查看薄弱点建议"));
        notificationList.setPlaceholder(new Label("暂无站内通知"));
        objectiveSummaryList.setPlaceholder(new Label("选择课程和班级后刷新目标分布"));
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
        run("正在刷新课程协作数据…", () -> {
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
                showStatus(cached ? "云端不可用，已加载当前账号的本地缓存" : "课程与通知已刷新", false);
            });
        });
    }

    @FXML
    private void onCreateCourse() {
        requireTeacher();
        String name = required(courseNameField.getText(), "请输入课程名称");
        if (name == null) return;
        run("正在创建课程…", () -> {
            CourseCatalog created = api.createCourse(token(), name, courseDescriptionField.getText());
            List<CourseCatalog> loaded = api.listCourses(token());
            cache.saveCourses(accountId(), loaded);
            Platform.runLater(() -> {
                courses = loaded;
                courseList.getItems().setAll(loaded.stream().map(this::courseLabel).toList());
                selectCourse(created.id());
                courseNameField.clear();
                courseDescriptionField.clear();
                showStatus("课程已创建", false);
            });
        });
    }

    @FXML
    private void onCreateSection() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        String name = required(sectionNameField.getText(), "请先输入章节名称");
        if (course == null || name == null) return;
        run("正在创建章节…", () -> {
            api.createCourseSection(token(), course.id(), name, nonNegativeOrder(sectionOrderField.getText(), sections.size()));
            loadCourseDetails(course.id(), "章节已创建");
            Platform.runLater(sectionNameField::clear);
        });
    }

    @FXML
    private void onSaveCourse() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        String name = required(courseNameField.getText(), "请输入课程名称");
        if (course == null || name == null) return;
        run("正在更新课程…", () -> {
            CourseCatalog updated = api.updateCourse(token(), course.id(), name, courseDescriptionField.getText(),
                course.status(), course.version());
            List<CourseCatalog> loaded = api.listCourses(token());
            cache.saveCourses(accountId(), loaded);
            Platform.runLater(() -> {
                courses = loaded;
                courseList.getItems().setAll(loaded.stream().map(this::courseLabel).toList());
                selectCourse(updated.id());
                showStatus("课程已更新", false);
            });
        });
    }

    @FXML
    private void onToggleCourseStatus() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        if (course == null) return;
        ContentStatus next = course.status() == ContentStatus.ACTIVE ? ContentStatus.INACTIVE : ContentStatus.ACTIVE;
        run("正在更新课程状态…", () -> {
            api.updateCourse(token(), course.id(), course.name(), course.description(), next, course.version());
            List<CourseCatalog> loaded = api.listCourses(token());
            cache.saveCourses(accountId(), loaded);
            Platform.runLater(() -> {
                courses = loaded;
                courseList.getItems().setAll(loaded.stream().map(this::courseLabel).toList());
                selectCourse(course.id());
                showStatus(next == ContentStatus.ACTIVE ? "课程已恢复" : "课程已停用，历史任务仍保留", false);
            });
        });
    }

    @FXML
    private void onUpdateSection() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        CourseSection section = selectedSection();
        String name = required(sectionNameField.getText(), "请输入章节名称");
        if (course == null || section == null || name == null) return;
        run("正在更新章节…", () -> {
            api.updateCourseSection(token(), course.id(), section.id(), name,
                nonNegativeOrder(sectionOrderField.getText(), section.sortOrder()), section.status(), section.version());
            loadCourseDetails(course.id(), "章节已更新");
        });
    }

    @FXML
    private void onToggleSectionStatus() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        CourseSection section = selectedSection();
        if (course == null || section == null) return;
        ContentStatus next = section.status() == ContentStatus.ACTIVE ? ContentStatus.INACTIVE : ContentStatus.ACTIVE;
        run("正在更新章节状态…", () -> {
            api.updateCourseSection(token(), course.id(), section.id(), section.name(), section.sortOrder(), next,
                section.version());
            loadCourseDetails(course.id(), next == ContentStatus.ACTIVE ? "章节已恢复" : "章节已停用");
        });
    }

    @FXML
    private void onCreateKnowledgePoint() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        String name = required(knowledgePointNameField.getText(), "请先输入知识点名称");
        if (course == null || name == null) return;
        CourseSection section = selectedSection();
        run("正在创建知识点…", () -> {
            api.createKnowledgePoint(token(), course.id(), section == null ? null : section.id(), name,
                knowledgePointDescriptionField.getText(),
                nonNegativeOrder(knowledgePointOrderField.getText(), knowledgePoints.size()));
            loadCourseDetails(course.id(), "知识点已创建");
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
        String name = required(knowledgePointNameField.getText(), "请输入知识点名称");
        if (course == null || point == null || name == null) return;
        run("正在更新知识点…", () -> {
            api.updateKnowledgePoint(token(), course.id(), point.id(), section == null ? point.sectionId() : section.id(),
                name, knowledgePointDescriptionField.getText(),
                nonNegativeOrder(knowledgePointOrderField.getText(), point.sortOrder()), point.status(), point.version());
            loadCourseDetails(course.id(), "知识点已更新");
        });
    }

    @FXML
    private void onToggleKnowledgePointStatus() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        KnowledgePoint point = selectedKnowledgePoint();
        if (course == null || point == null) return;
        ContentStatus next = point.status() == ContentStatus.ACTIVE ? ContentStatus.INACTIVE : ContentStatus.ACTIVE;
        run("正在更新知识点状态…", () -> {
            api.updateKnowledgePoint(token(), course.id(), point.id(), point.sectionId(), point.name(),
                point.description(), point.sortOrder(), next, point.version());
            loadCourseDetails(course.id(), next == ContentStatus.ACTIVE ? "知识点已恢复" : "知识点已停用");
        });
    }

    @FXML
    private void onPublishExercise() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        String title = required(exerciseTitleField.getText(), "请输入题目标题");
        String prompt = required(exercisePromptField.getText(), "请输入题目说明");
        String dataset = required(datasetVersionField.getText(), "请输入数据集版本");
        String rule = required(evaluationRuleField.getText(), "请输入确定性评测规则");
        if (course == null || title == null || prompt == null || dataset == null || rule == null) return;
        KnowledgePoint point = selectedKnowledgePoint();
        List<String> pointIds = point == null ? List.of() : List.of(point.id());
        run("正在发布不可变题目版本…", () -> {
            api.publishSharedExercise(token(), course.id(), blankToNull(localExerciseIdField.getText()), title,
                prompt, dataset, rule, pointIds, UUID.randomUUID().toString());
            loadCourseDetails(course.id(), "题目版本已发布");
        });
    }

    @FXML
    private void onCreateAssignmentSnapshot() {
        requireTeacher();
        SharedExerciseVersion exercise = selectedExercise();
        String classroomId = selectedId(assignmentClassroomCombo, "请选择发布班级");
        if (exercise == null || classroomId == null) return;
        Instant dueAt = DeadlineValueConverter.toInstant(
            assignmentDueDatePicker.getValue(), assignmentDueTimeCombo.getValue(), ZoneId.systemDefault()
        );
        run("正在创建带快照的任务…", () -> {
            var assignment = api.createAssignmentFromVersion(token(), classroomId, exercise.id(),
                assignmentTitleField.getText(), "来自云端共享题库 v" + exercise.version(), dueAt,
                UUID.randomUUID().toString());
            List<ClassAssignment> loaded = api.listAssignments(token(), classroomId);
            Platform.runLater(() -> {
                selectOption(learningClassroomCombo, classroomId);
                applyLearningAssignments(loaded);
                selectOption(learningAssignmentCombo, assignment.id());
                showStatus("任务已发布，内容快照已冻结", false);
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
        run("正在更新题目状态…", () -> {
            api.setSharedExerciseStatus(token(), course.id(), exercise.exerciseId(), next);
            loadCourseDetails(course.id(), next == ContentStatus.ACTIVE ? "题目已恢复" : "题目已停用，历史快照不受影响");
        });
    }

    @FXML
    private void onRefreshLearning() {
        String classroomId = selectedId(learningClassroomCombo, "请选择班级");
        if (classroomId == null) return;
        String assignmentId = selectedId(learningAssignmentCombo, "请选择任务");
        if (assignmentId == null) return;
        run("正在刷新反馈与薄弱点…", () -> {
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
                showStatus(cached ? "云端不可用，已加载本地反馈与建议缓存" : "反馈与薄弱点已刷新", false);
            });
        });
    }

    @FXML
    private void onDraftFeedback() {
        requireTeacher();
        String classroomId = selectedId(learningClassroomCombo, "请选择班级");
        String assignmentId = selectedId(learningAssignmentCombo, "请选择任务");
        String submissionId = required(submissionIdField.getText(), "请输入提交 ID");
        if (classroomId == null || assignmentId == null || submissionId == null) return;
        run("正在准备最小必要反馈上下文…", () -> {
            var deterministic = api.draftSubmissionFeedback(token(), classroomId, assignmentId, submissionId);
            var preview = feedbackDraftEnhancer.preview(deterministic);
            Platform.runLater(() -> {
                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION);
                alert.setTitle("确认生成 AI 反馈草稿");
                alert.setHeaderText("将发送 " + preview.characterCount() + " 个脱敏后的确定性证据字符");
                alert.setContentText("来源：" + String.join("、", preview.sources()) + "\nAI 不得改变评测结论，草稿发布前仍需人工确认。");
                if (alert.showAndWait().filter(javafx.scene.control.ButtonType.OK::equals).isEmpty()) {
                    feedbackCommentField.setText(deterministic.text());
                    showStatus("已取消网络增强，保留确定性模板草稿", false);
                    return;
                }
                run("正在生成安全反馈草稿…", () -> {
                    var draft = feedbackDraftEnhancer.enhance(deterministic, feedbackDraftStyleCombo.getValue());
                    Platform.runLater(() -> {
                        feedbackCommentField.setText(draft.text());
                        showStatus(draft.aiGenerated() ? "已生成 AI 反馈草稿，请人工确认" : "已生成确定性模板草稿", false);
                    });
                });
            });
        });
    }

    @FXML
    private void onRefreshObjectiveSummary() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        String classroomId = selectedId(learningClassroomCombo, "请选择班级");
        if (course == null || classroomId == null) return;
        run("正在按课程目标汇总班级事实…", () -> {
            List<ObjectiveClassSummary> loaded = api.getObjectiveClassSummary(token(), course.id(), classroomId);
            Platform.runLater(() -> {
                objectiveSummaryList.getItems().setAll(loaded.stream().map(item -> item.objectiveTitle()
                    + " · 未知 " + item.unknown() + " · 需支持 " + item.needsSupport() + " · 发展中 "
                    + item.developing() + " · 已掌握 " + item.mastered()).toList());
                showStatus("目标分布只描述现有证据，不生成教师评分", false);
            });
        });
    }

    @FXML
    private void onPrepareObjectiveIntervention() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        CourseObjective objective = selectedObjective();
        String classroomId = selectedId(learningClassroomCombo, "请选择班级");
        String action = required(interventionActionField.getText(), "请输入复习或反馈动作草稿");
        if (course == null || objective == null || classroomId == null || action == null) return;
        run("正在生成干预影响预览…", () -> {
            ObjectiveInterventionDraft draft = api.createObjectiveInterventionDraft(token(), course.id(), classroomId,
                objective.id(), "OBJECTIVE_EVIDENCE_GAP", action);
            Platform.runLater(() -> {
                pendingIntervention = draft;
                confirmInterventionButton.setDisable(false);
                showStatus("草稿已生成，影响 " + draft.impactCount() + " 名学生；请核对后明确确认", false);
            });
        });
    }

    @FXML
    private void onConfirmObjectiveIntervention() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        ObjectiveInterventionDraft draft = pendingIntervention;
        if (course == null || draft == null) {
            showStatus("请先生成干预影响预览", true); return;
        }
        run("正在复核目标版本并确认干预…", () -> {
            api.confirmObjectiveInterventionDraft(token(), course.id(), draft.id(), draft.confirmationToken());
            Platform.runLater(() -> {
                pendingIntervention = null;
                confirmInterventionButton.setDisable(true);
                showStatus("干预已人工确认并写入审计；后续仅展示事实变化", false);
            });
        });
    }

    @FXML
    private void onCreateObjective() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        String title = required(objectiveTitleField.getText(), "请输入课程目标标题");
        String criteria = required(objectiveCriteriaField.getText(), "请输入可验证完成条件");
        if (course == null || title == null || criteria == null) return;
        run("正在创建课程目标…", () -> {
            api.createCourseObjective(token(), course.id(), title, objectiveDescriptionField.getText(), criteria,
                nonNegativeOrder(objectiveOrderField.getText(), objectives.size()));
            loadCourseDetails(course.id(), "课程目标已创建");
        });
    }

    @FXML
    private void onSaveObjective() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        CourseObjective objective = selectedObjective();
        String title = required(objectiveTitleField.getText(), "请输入课程目标标题");
        String criteria = required(objectiveCriteriaField.getText(), "请输入可验证完成条件");
        if (course == null || objective == null || title == null || criteria == null) return;
        run("正在保存课程目标…", () -> {
            api.updateCourseObjective(token(), course.id(), objective.id(), title, objectiveDescriptionField.getText(),
                criteria, nonNegativeOrder(objectiveOrderField.getText(), objective.sortOrder()), objective.status(),
                objective.version());
            loadCourseDetails(course.id(), "课程目标已保存");
        });
    }

    @FXML
    private void onToggleObjectiveStatus() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        CourseObjective objective = selectedObjective();
        if (course == null || objective == null) return;
        ContentStatus next = objective.status() == ContentStatus.ACTIVE ? ContentStatus.INACTIVE : ContentStatus.ACTIVE;
        run("正在更新课程目标状态…", () -> {
            api.updateCourseObjective(token(), course.id(), objective.id(), objective.title(), objective.description(),
                objective.completionCriteria(), objective.sortOrder(), next, objective.version());
            loadCourseDetails(course.id(), next == ContentStatus.ACTIVE ? "课程目标已恢复" : "课程目标已停用");
        });
    }

    @FXML
    private void onAddObjectivePrerequisite() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        CourseObjective objective = selectedObjective();
        SelectionOption prerequisite = prerequisiteObjectiveCombo.getValue();
        if (course == null || objective == null || prerequisite == null) {
            showStatus("请选择当前目标和先修目标", true); return;
        }
        run("正在校验先修关系…", () -> {
            api.addObjectivePrerequisite(token(), course.id(), objective.id(), prerequisite.id());
            Platform.runLater(() -> showStatus("先修关系已添加；环路与跨课程关系会由服务端拒绝", false));
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
            showStatus("请选择目标并提供同课程资源 ID", true); return;
        }
        String selectedResourceId = resourceId;
        run("正在关联课程资源…", () -> {
            api.addObjectiveResource(token(), course.id(), objective.id(), type, selectedResourceId);
            Platform.runLater(() -> showStatus("资源已关联到课程目标", false));
        });
    }

    @FXML
    private void onSaveFeedback() {
        requireTeacher();
        String classroomId = selectedId(learningClassroomCombo, "请选择班级");
        String assignmentId = selectedId(learningAssignmentCombo, "请选择任务");
        String submissionId = required(submissionIdField.getText(), "请输入提交 ID");
        if (classroomId == null || assignmentId == null || submissionId == null) return;
        long expectedVersion = feedback.stream().filter(item -> item.submissionId().equals(submissionId))
            .mapToLong(SubmissionFeedback::version).findFirst().orElse(0);
        KnowledgePoint point = selectedKnowledgePoint();
        List<String> pointIds = point == null ? List.of() : List.of(point.id());
        run("正在保存教师反馈…", () -> {
            api.saveSubmissionFeedback(token(), classroomId, assignmentId, submissionId,
                feedbackStatusCombo.getValue(), feedbackCommentField.getText(), pointIds, expectedVersion,
                UUID.randomUUID().toString());
            List<SubmissionFeedback> loaded = api.listSubmissionFeedback(token(), classroomId, assignmentId);
            cache.saveFeedback(accountId(), assignmentId, loaded);
            Platform.runLater(() -> {
                feedback = loaded;
                feedbackList.getItems().setAll(loaded.stream().map(this::feedbackLabel).toList());
                showStatus("教师反馈已保存并通知学生", false);
            });
        });
    }

    @FXML
    private void onRefreshNotifications() {
        run("正在刷新通知…", () -> {
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
                showStatus(cached ? "云端不可用，已加载本地通知缓存" : "通知已刷新", false);
            });
        });
    }

    @FXML
    private void onMarkNotificationRead() {
        int index = notificationList.getSelectionModel().getSelectedIndex();
        if (index < 0 || index >= notifications.size()) return;
        String id = notifications.get(index).id();
        run("正在更新通知…", () -> {
            api.markNotificationRead(token(), id);
            List<CloudNotification> loaded = api.listNotifications(token(), 0, 50);
            cache.saveNotifications(accountId(), loaded);
            Platform.runLater(() -> {
                notifications = loaded;
                renderNotifications();
                showStatus("通知已标记为已读", false);
            });
        });
    }

    @FXML
    private void onExportCourse() {
        requireTeacher();
        CourseCatalog course = selectedCourse();
        if (course == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出课程题库包");
        chooser.setInitialFileName("sqlteacher-course-" + course.id() + ".json");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        var file = chooser.showSaveDialog(courseList.getScene().getWindow());
        if (file == null) return;
        run("正在导出课程题库包…", () -> {
            Files.writeString(file.toPath(), api.exportCourseBundle(token(), course.id()), StandardCharsets.UTF_8);
            Platform.runLater(() -> showStatus("课程题库包已导出", false));
        });
    }

    @FXML
    private void onImportCourse() {
        requireTeacher();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导入课程题库包");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        var file = chooser.showOpenDialog(courseList.getScene().getWindow());
        if (file == null) return;
        run("正在预检并导入课程题库包…", () -> {
            String bundle = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            var result = api.importCourseBundle(token(), bundle, UUID.randomUUID().toString());
            List<CourseCatalog> loaded = api.listCourses(token());
            cache.saveCourses(accountId(), loaded);
            Platform.runLater(() -> {
                courses = loaded;
                courseList.getItems().setAll(loaded.stream().map(this::courseLabel).toList());
                selectCourse(result.courseId());
                showStatus("导入完成：" + result.exercises() + " 个题目", false);
            });
        });
    }

    private void refreshSelectedCourse() {
        CourseCatalog selected = selectedCourse();
        if (selected == null || running.get()) return;
        run("正在加载课程内容…", () -> loadCourseDetails(selected.id(), "课程内容已加载"));
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
            showStatus(cached ? "云端不可用，已加载该课程的本地缓存" : message, false);
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
        run("正在加载班级任务…", () -> {
            List<ClassAssignment> loaded = api.listAssignments(token(), classroomId);
            Platform.runLater(() -> {
                applyLearningAssignments(loaded);
                showStatus("班级任务已加载", false);
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
            .map(member -> new SelectionOption(member.userId(), "学生 · " + shortId(member.userId())))
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
            showStatus("请先选择课程", true);
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
            showStatus("请先选择题目版本", true);
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
        return item.status() + " · 提交 " + item.submissionId() + " · v" + item.version();
    }

    private String masteryLabel(KnowledgeMastery item) {
        return item.knowledgePointName() + " · " + item.masteryPercent() + "% · 建议 "
            + item.recommendations().size() + " 题";
    }

    private String token() {
        return sessions.current().orElseThrow(() -> new IllegalStateException("请先登录云端账号")).accessToken();
    }

    private String accountId() {
        return sessions.current().orElseThrow(() -> new IllegalStateException("请先登录云端账号")).user().id();
    }

    private boolean isTeacher() {
        return accessProfile.kind() == DesktopAccessProfile.Kind.TEACHER
            || accessProfile.kind() == DesktopAccessProfile.Kind.ADMIN;
    }

    private void requireTeacher() {
        if (!isTeacher()) throw new SecurityException("当前身份不能管理课程内容");
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
            throw new IllegalArgumentException("排序必须是非负整数");
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
        return message == null || message.isBlank() ? "操作失败，请稍后重试" : message;
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private CourseObjective selectedObjective() {
        int index = objectiveList.getSelectionModel().getSelectedIndex();
        if (index < 0 || index >= objectives.size()) {
            showStatus("请先选择课程目标", true);
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
