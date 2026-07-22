package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.ai.NetworkAiSettingsService;
import com.sqlteacher.application.collaboration.ClassAssignment;
import com.sqlteacher.application.collaboration.AssignmentStatus;
import com.sqlteacher.application.collaboration.ClassroomService;
import com.sqlteacher.application.collaboration.CloudApiClient;
import com.sqlteacher.application.collaboration.CloudAuthenticationService;
import com.sqlteacher.application.collaboration.CloudLearningSyncService;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.UserRole;
import com.sqlteacher.application.collaboration.DesktopAccessProfile;
import com.sqlteacher.desktop.DesktopExecutors;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.FlowPane;
import javafx.util.StringConverter;

import java.net.URI;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Cloud login and classroom management view; all API work stays off the FX thread. */
public final class CloudCenterController {
    private static final List<String> STATUS_STYLES = List.of("status-info", "status-success", "status-error");

    private final CloudApiClient api;
    private final CloudSessionService session;
    private final CloudLearningSyncService sync;
    private final NetworkAiSettingsService networkAiSettings;
    private final AtomicInteger activeOperations = new AtomicInteger();

    @FXML private TextField classNameField;
    @FXML private Label statusLabel;
    @FXML private Label accountLabel;
    @FXML private Label classSummaryLabel;
    @FXML private Label selectedClassLabel;
    @FXML private Label classAnalyticsLabel;
    @FXML private HBox statusBanner;
    @FXML private VBox authenticatedContent;
    @FXML private FlowPane classCreationPane;
    @FXML private VBox memberManagementPane;
    @FXML private FlowPane assignmentCreationPane;
    @FXML private HBox assignmentLifecyclePane;
    @FXML private ProgressIndicator busyIndicator;
    @FXML private Button logoutButton;
    @FXML private Button exportClassRecordsButton;
    @FXML private ListView<String> classList;
    @FXML private TextField aiEndpointField;
    @FXML private TextField aiModelField;
    @FXML private PasswordField aiKeyField;
    @FXML private TextField memberEmailField;
    @FXML private ComboBox<UserRole> memberRoleCombo;
    @FXML private TextField assignmentExerciseField;
    @FXML private TextField assignmentTitleField;
    @FXML private TextField assignmentDueAtField;
    @FXML private ListView<String> assignmentList;

    private List<ClassroomService.Classroom> classrooms = List.of();
    private List<ClassAssignment> assignments = List.of();
    private boolean applyingClassSelection;
    private final Runnable switchIdentityAction;
    private final DesktopAccessProfile accessProfile;

    public CloudCenterController(
        CloudApiClient api,
        CloudSessionService session,
        CloudLearningSyncService sync,
        NetworkAiSettingsService networkAiSettings,
        DesktopAccessProfile accessProfile,
        Runnable switchIdentityAction
    ) {
        this.api = api;
        this.session = session;
        this.sync = sync;
        this.networkAiSettings = networkAiSettings;
        this.accessProfile = java.util.Objects.requireNonNull(accessProfile, "accessProfile must not be null");
        this.switchIdentityAction = java.util.Objects.requireNonNull(
            switchIdentityAction,
            "switchIdentityAction must not be null"
        );
    }

    @FXML
    private void initialize() {
        memberRoleCombo.getItems().setAll(UserRole.TEACHER, UserRole.STUDENT);
        memberRoleCombo.setConverter(new StringConverter<>() {
            @Override public String toString(UserRole role) {
                if (role == null) return "";
                return role == UserRole.TEACHER ? "教师" : "学生";
            }

            @Override public UserRole fromString(String value) {
                return "教师".equals(value) ? UserRole.TEACHER : UserRole.STUDENT;
            }
        });
        memberRoleCombo.setValue(UserRole.STUDENT);
        classList.setPlaceholder(new Label("暂无班级，先创建一个教学班级"));
        assignmentList.setPlaceholder(new Label("选择班级后查看已发布任务"));
        classList.getSelectionModel().selectedIndexProperty().addListener((observable, oldValue, newValue) -> {
            updateSelectedClassLabel();
            if (!applyingClassSelection) refreshAssignments();
        });
        assignmentList.getSelectionModel().selectedIndexProperty().addListener((observable, oldValue, newValue) -> {
            int index = newValue.intValue();
            if (index >= 0 && index < assignments.size()) {
                ClassAssignment selected = assignments.get(index);
                assignmentTitleField.setText(selected.title());
                assignmentDueAtField.setText(selected.dueAt() == null ? "" : selected.dueAt().toString());
            }
        });
        boolean canManageClass = canManageClass();
        classCreationPane.setVisible(canManageClass);
        classCreationPane.setManaged(canManageClass);
        memberManagementPane.setVisible(canManageClass);
        memberManagementPane.setManaged(canManageClass);
        assignmentCreationPane.setVisible(canManageClass);
        assignmentCreationPane.setManaged(canManageClass);
        assignmentLifecyclePane.setVisible(canManageClass);
        assignmentLifecyclePane.setManaged(canManageClass);
        exportClassRecordsButton.setVisible(canManageClass);
        exportClassRecordsButton.setManaged(canManageClass);
        updateSessionState();
    }

    @FXML
    private void onCreateClass() {
        requireClassManager();
        String name = required(classNameField, "请输入班级名称");
        if (name == null) return;
        run("正在创建班级…", () -> {
            var current = currentSession();
            var created = api.createClass(current.accessToken(), name);
            loadClasses(current.accessToken(), created.id());
            Platform.runLater(() -> {
                classNameField.clear();
                showStatus("班级“" + created.name() + "”已创建", Status.SUCCESS);
            });
        });
    }

    @FXML
    private void onRefresh() {
        String selectedId = selectedClassId();
        run("正在刷新班级与任务…", () -> {
            var current = currentSession();
            loadClasses(current.accessToken(), selectedId);
            Platform.runLater(() -> showStatus("班级与任务已刷新", Status.SUCCESS));
        });
    }

    @FXML
    private void onLogout() {
        switchIdentityAction.run();
    }

    @FXML
    private void onSync() {
        run("正在同步学习记录…", () -> {
            var result = sync.synchronize();
            Platform.runLater(() -> showStatus(
                "同步完成：上传 " + result.uploaded() + " 条，下载 " + result.downloaded() + " 条",
                Status.SUCCESS
            ));
        });
    }

    @FXML
    private void onExportClassRecords() {
        requireClassManager();
        var selected = selectedClass();
        if (selected == null) {
            showStatus("请先选择班级", Status.ERROR);
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出班级教学记录");
        chooser.setInitialFileName("SQLTeacher-" + selected.name().replaceAll("[\\/:*?\"<>|]", "_") + "-教学记录.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV 文件", "*.csv"));
        var selectedFile = chooser.showSaveDialog(classAnalyticsLabel.getScene().getWindow());
        if (selectedFile == null) return;
        Path target = selectedFile.toPath();
        run("正在导出班级教学记录…", () -> {
            String csv = api.exportClassLearningCsv(currentSession().accessToken(), selected.id());
            try {
                Files.writeString(target, csv, StandardCharsets.UTF_8);
            } catch (java.io.IOException error) {
                throw new IllegalStateException("无法写入教学记录 CSV", error);
            }
            Platform.runLater(() -> showStatus("教学记录已导出到 " + target, Status.SUCCESS));
        });
    }

    @FXML
    private void onConfigureNetworkAi() {
        String endpoint = required(aiEndpointField, "请输入 HTTPS 接口地址");
        String model = required(aiModelField, "请输入模型名称");
        String keyText = aiKeyField.getText();
        if (endpoint == null || model == null) return;
        if (keyText == null || keyText.isBlank()) {
            showStatus("请输入 API Key", Status.ERROR);
            aiKeyField.requestFocus();
            return;
        }
        char[] key = keyText.toCharArray();
        try {
            networkAiSettings.configure(URI.create(endpoint), model, key);
            showStatus("网络 AI 已启用，API Key 仅保存在当前进程内存", Status.SUCCESS);
        } catch (RuntimeException error) {
            showStatus(message(error), Status.ERROR);
        } finally {
            Arrays.fill(key, '\0');
            aiKeyField.clear();
        }
    }

    @FXML
    private void onClearNetworkAi() {
        networkAiSettings.clear();
        aiKeyField.clear();
        showStatus("已切换回本地 Ollama", Status.SUCCESS);
    }

    @FXML
    private void onAddMember() {
        requireClassManager();
        var selected = selectedClass();
        if (selected == null) {
            showStatus("请先选择班级", Status.ERROR);
            return;
        }
        String email = required(memberEmailField, "请输入成员邮箱");
        if (email == null) return;
        UserRole role = memberRoleCombo.getValue();
        run("正在添加班级成员…", () -> {
            var current = currentSession();
            api.addClassMember(current.accessToken(), selected.id(), email, role);
            loadClasses(current.accessToken(), selected.id());
            Platform.runLater(() -> {
                memberEmailField.clear();
                showStatus("成员已加入“" + selected.name() + "”", Status.SUCCESS);
            });
        });
    }

    @FXML
    private void onCreateAssignment() {
        requireClassManager();
        var selected = selectedClass();
        if (selected == null) {
            showStatus("请先选择班级", Status.ERROR);
            return;
        }
        String exerciseId = required(assignmentExerciseField, "请输入本地题目 ID");
        String title = required(assignmentTitleField, "请输入任务标题");
        if (exerciseId == null || title == null) return;
        Instant dueAt;
        try {
            String dueAtText = assignmentDueAtField.getText() == null ? "" : assignmentDueAtField.getText().trim();
            dueAt = dueAtText.isEmpty() ? null : Instant.parse(dueAtText);
        } catch (RuntimeException error) {
            showStatus("截止时间请使用 ISO-8601 格式，例如 2026-12-31T15:00:00Z", Status.ERROR);
            assignmentDueAtField.requestFocus();
            return;
        }
        run("正在发布班级任务…", () -> {
            var current = currentSession();
            api.createAssignment(current.accessToken(), selected.id(), exerciseId, title, dueAt);
            List<ClassAssignment> assignments = api.listAssignments(current.accessToken(), selected.id());
            Platform.runLater(() -> {
                applyAssignments(selected.id(), assignments);
                assignmentExerciseField.clear();
                assignmentTitleField.clear();
                assignmentDueAtField.clear();
                showStatus("任务“" + title + "”已发布", Status.SUCCESS);
            });
        });
    }

    @FXML
    private void onCloseAssignment() {
        changeSelectedAssignmentStatus(AssignmentStatus.CLOSED, "任务已截止，学生可查看但不能继续提交");
    }

    @FXML
    private void onWithdrawAssignment() {
        changeSelectedAssignmentStatus(AssignmentStatus.WITHDRAWN, "任务已撤回，学生将不再看到该任务");
    }

    @FXML
    private void onArchiveAssignment() {
        changeSelectedAssignmentStatus(AssignmentStatus.ARCHIVED, "任务已归档");
    }

    @FXML
    private void onUpdateAssignment() {
        requireClassManager();
        var selectedClass = selectedClass();
        int index = assignmentList.getSelectionModel().getSelectedIndex();
        if (selectedClass == null || index < 0 || index >= assignments.size()) {
            showStatus("请先选择一个班级任务", Status.ERROR);
            return;
        }
        String title = required(assignmentTitleField, "请输入更新后的任务标题");
        if (title == null) return;
        Instant dueAt;
        try {
            String value = assignmentDueAtField.getText() == null ? "" : assignmentDueAtField.getText().trim();
            dueAt = value.isEmpty() ? null : Instant.parse(value);
        } catch (RuntimeException error) {
            showStatus("截止时间请使用 ISO-8601 格式", Status.ERROR);
            return;
        }
        ClassAssignment assignment = assignments.get(index);
        run("正在更新任务…", () -> {
            var current = currentSession();
            api.updateAssignment(current.accessToken(), selectedClass.id(), assignment.id(), title, dueAt);
            List<ClassAssignment> refreshed = api.listAssignments(current.accessToken(), selectedClass.id());
            Platform.runLater(() -> {
                applyAssignments(selectedClass.id(), refreshed);
                showStatus("任务已更新", Status.SUCCESS);
            });
        });
    }

    private void changeSelectedAssignmentStatus(AssignmentStatus status, String successMessage) {
        requireClassManager();
        var selectedClass = selectedClass();
        int index = assignmentList.getSelectionModel().getSelectedIndex();
        if (selectedClass == null || index < 0 || index >= assignments.size()) {
            showStatus("请先选择一个班级任务", Status.ERROR);
            return;
        }
        ClassAssignment assignment = assignments.get(index);
        run("正在更新任务状态…", () -> {
            var current = currentSession();
            api.changeAssignmentStatus(current.accessToken(), selectedClass.id(), assignment.id(), status);
            List<ClassAssignment> refreshed = api.listAssignments(current.accessToken(), selectedClass.id());
            Platform.runLater(() -> {
                applyAssignments(selectedClass.id(), refreshed);
                showStatus(successMessage, Status.SUCCESS);
            });
        });
    }

    private void refreshAssignments() {
        var selected = selectedClass();
        var current = session.current();
        if (selected == null || current.isEmpty()) {
            assignmentList.getItems().clear();
            return;
        }
        run("正在加载“" + selected.name() + "”的任务…", () -> {
            List<ClassAssignment> assignments = api.listAssignments(current.orElseThrow().accessToken(), selected.id());
            var summary = canManageClass() ? api.getClassLearningSummary(current.orElseThrow().accessToken(), selected.id()) : null;
            Platform.runLater(() -> {
                applyAssignments(selected.id(), assignments);
                applyClassAnalytics(summary);
                showStatus("已加载“" + selected.name() + "”", Status.INFO);
            });
        });
    }

    private void loadClasses(String token, String preferredClassId) {
        List<ClassroomService.Classroom> loadedClasses = api.listClasses(token);
        ClassroomService.Classroom selected = loadedClasses.stream()
            .filter(item -> item.id().equals(preferredClassId))
            .findFirst()
            .orElse(loadedClasses.isEmpty() ? null : loadedClasses.getFirst());
        List<ClassAssignment> assignments = selected == null
            ? List.of()
            : api.listAssignments(token, selected.id());
        Platform.runLater(() -> applyClasses(loadedClasses, selected, assignments));
    }

    private void applyClasses(
        List<ClassroomService.Classroom> loadedClasses,
        ClassroomService.Classroom selected,
        List<ClassAssignment> assignments
    ) {
        classrooms = List.copyOf(loadedClasses);
        applyingClassSelection = true;
        try {
            classList.getItems().setAll(loadedClasses.stream()
                .map(item -> item.name() + "  ·  " + item.members().size() + " 名成员")
                .toList());
            if (selected == null) classList.getSelectionModel().clearSelection();
            else classList.getSelectionModel().select(loadedClasses.indexOf(selected));
        } finally {
            applyingClassSelection = false;
        }
        classSummaryLabel.setText(loadedClasses.isEmpty()
            ? "还没有班级，可在下方立即创建"
            : "共 " + loadedClasses.size() + " 个班级，选择后可管理成员和任务");
        updateSelectedClassLabel();
        applyAssignments(selected == null ? null : selected.id(), assignments);
    }

    private void applyAssignments(String classroomId, List<ClassAssignment> assignments) {
        if (classroomId != null && !classroomId.equals(selectedClassId())) return;
        this.assignments = List.copyOf(assignments);
        assignmentList.getItems().setAll(assignments.stream()
            .map(item -> item.title() + "\n题目 ID：" + item.exerciseId() + " · " + assignmentStatusLabel(item.status())
                + (item.dueAt() == null ? "" : " · 截止：" + item.dueAt()))
            .toList());
        assignmentList.setPlaceholder(new Label(classroomId == null
            ? "选择班级后查看已发布任务"
            : "这个班级还没有发布任务"));
    }

    private void applyClassAnalytics(com.sqlteacher.application.collaboration.ClassLearningSummary summary) {
        if (summary == null) {
            classAnalyticsLabel.setText("仅教师和管理员可查看本班教学记录");
            return;
        }
        classAnalyticsLabel.setText("学生 " + summary.studentCount() + " 人 · 已产生记录 "
            + summary.activeStudentCount() + " 人 · 同步事件 " + summary.syncedEvents() + " 条 · 成功 "
            + summary.successfulEvents() + " 条");
    }

    private String assignmentStatusLabel(AssignmentStatus status) {
        return switch (status) {
            case DRAFT -> "草稿";
            case PUBLISHED -> "已发布";
            case CLOSED -> "已截止";
            case WITHDRAWN -> "已撤回";
            case ARCHIVED -> "已归档";
        };
    }

    private ClassroomService.Classroom selectedClass() {
        int index = classList.getSelectionModel().getSelectedIndex();
        return index >= 0 && index < classrooms.size() ? classrooms.get(index) : null;
    }

    private String selectedClassId() {
        var selected = selectedClass();
        return selected == null ? null : selected.id();
    }

    private void updateSelectedClassLabel() {
        var selected = selectedClass();
        selectedClassLabel.setText(selected == null
            ? "尚未选择班级"
            : selected.name() + " · " + selected.members().size() + " 名成员");
    }

    private void updateSessionState() {
        var current = session.current();
        boolean signedIn = current.isPresent();
        accountLabel.setText(current
            .map(value -> value.user().displayName() + " · " + value.user().roles().stream().map(this::roleName).sorted().reduce((a, b) -> a + "/" + b).orElse("用户"))
            .orElse("未登录"));
        authenticatedContent.setDisable(!signedIn || activeOperations.get() > 0);
        logoutButton.setDisable(!signedIn || activeOperations.get() > 0);
    }

    private String roleName(UserRole role) {
        return switch (role) {
            case ADMIN -> "管理员";
            case TEACHER -> "教师";
            case STUDENT -> "学生";
        };
    }

    private boolean canManageClass() {
        return accessProfile.kind() == DesktopAccessProfile.Kind.TEACHER
            || accessProfile.kind() == DesktopAccessProfile.Kind.ADMIN;
    }

    private void requireClassManager() {
        if (!canManageClass()) throw new SecurityException("当前身份不能管理班级或发布任务");
    }

    private CloudAuthenticationService.Session currentSession() {
        return session.current().orElseThrow(() -> new IllegalStateException("请先登录云端账号"));
    }

    private String required(TextField field, String message) {
        String value = field.getText();
        if (value != null && !value.isBlank()) return value.trim();
        showStatus(message, Status.ERROR);
        field.requestFocus();
        return null;
    }

    private void run(String pendingMessage, Runnable action) {
        activeOperations.incrementAndGet();
        setBusy(true);
        showStatus(pendingMessage, Status.INFO);
        DesktopExecutors.background().execute(() -> {
            try {
                action.run();
            } catch (RuntimeException error) {
                Platform.runLater(() -> showStatus(message(error), Status.ERROR));
            } finally {
                Platform.runLater(() -> {
                    int remaining = activeOperations.updateAndGet(value -> Math.max(0, value - 1));
                    setBusy(remaining > 0);
                });
            }
        });
    }

    private void setBusy(boolean busy) {
        busyIndicator.setVisible(busy);
        busyIndicator.setManaged(busy);
        updateSessionState();
    }

    private void showStatus(String message, Status status) {
        statusLabel.setText(message == null || message.isBlank() ? "操作未完成，请稍后重试" : message);
        statusBanner.getStyleClass().removeAll(STATUS_STYLES);
        statusBanner.getStyleClass().add(status.styleClass);
    }

    private static String message(RuntimeException error) {
        return error.getMessage() == null || error.getMessage().isBlank()
            ? "操作未完成，请检查网络后重试"
            : error.getMessage();
    }

    private enum Status {
        INFO("status-info"),
        SUCCESS("status-success"),
        ERROR("status-error");

        private final String styleClass;

        Status(String styleClass) {
            this.styleClass = styleClass;
        }
    }
}
