package com.sqlteacher.desktop.controller;
import com.sqlteacher.desktop.AppI18n;

import com.sqlteacher.application.collaboration.ClassAssignment;
import com.sqlteacher.application.collaboration.AssignmentStatus;
import com.sqlteacher.application.collaboration.ClassroomService;
import com.sqlteacher.application.collaboration.CloudApiClient;
import com.sqlteacher.application.collaboration.CloudAuthenticationService;
import com.sqlteacher.application.collaboration.CloudLearningSyncService;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.UserRole;
import com.sqlteacher.application.collaboration.DesktopAccessProfile;
import com.sqlteacher.application.collaboration.AssignmentDeliveryService;
import com.sqlteacher.application.collaboration.AssignmentTaskContext;
import com.sqlteacher.application.collaboration.AssignmentAnalyticsFilter;
import com.sqlteacher.application.collaboration.AssignmentAnalyticsReport;
import com.sqlteacher.application.collaboration.AdminUserSummary;
import com.sqlteacher.application.collaboration.RetentionCategory;
import com.sqlteacher.application.collaboration.RetentionPreview;
import com.sqlteacher.application.collaboration.RetentionJob;
import com.sqlteacher.desktop.DesktopExecutors;
import com.sqlteacher.desktop.DeadlineValueConverter;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.FlowPane;
import javafx.util.StringConverter;

import java.time.Instant;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Cloud login and classroom management view; all API work stays off the FX thread. */
public final class CloudCenterController {
    private static final List<String> STATUS_STYLES = List.of("status-info", "status-success", "status-error");

    private final CloudApiClient api;
    private final CloudSessionService session;
    private final CloudLearningSyncService sync;
    private final AssignmentDeliveryService assignmentDeliveryService;
    private final Consumer<AssignmentTaskContext> openAssignmentAction;
    private final AtomicInteger activeOperations = new AtomicInteger();

    @FXML private TextField classNameField;
    @FXML private Label statusLabel;
    @FXML private Label accountLabel;
    @FXML private Label classSummaryLabel;
    @FXML private Label selectedClassLabel;
    @FXML private Label classAnalyticsLabel;
    @FXML private HBox statusBanner;
    @FXML private VBox authenticatedContent;
    @FXML private VBox signedOutPrompt;
    @FXML private FlowPane classCreationPane;
    @FXML private VBox memberManagementPane;
    @FXML private FlowPane assignmentCreationPane;
    @FXML private HBox assignmentLifecyclePane;
    @FXML private HBox studentAssignmentPane;
    @FXML private HBox assignmentAnalyticsPane;
    @FXML private Label submissionQueueLabel;
    @FXML private ProgressIndicator busyIndicator;
    @FXML private Button logoutButton;
    @FXML private Button exportClassRecordsButton;
    @FXML private ListView<String> classList;
    @FXML private TextField memberEmailField;
    @FXML private ComboBox<UserRole> memberRoleCombo;
    @FXML private TextField assignmentExerciseField;
    @FXML private TextField assignmentTitleField;
    @FXML private TextField assignmentDescriptionField;
    @FXML private DatePicker assignmentDueDatePicker;
    @FXML private ComboBox<String> assignmentDueTimeCombo;
    @FXML private ListView<String> assignmentList;
    @FXML private Label assignmentAnalyticsLabel;
    @FXML private VBox adminOperationsPane;
    @FXML private Label adminHealthLabel;
    @FXML private ListView<String> adminUserList;
    @FXML private ListView<String> adminAuditList;
    @FXML private TextField adminReasonField;
    @FXML private ComboBox<RetentionCategory> retentionCategoryCombo;
    @FXML private TextField retentionCutoffField;
    @FXML private TextField retentionBackupField;
    @FXML private Label retentionStatusLabel;

    private List<ClassroomService.Classroom> classrooms = List.of();
    private List<ClassAssignment> assignments = List.of();
    private List<AdminUserSummary> adminUsers = List.of();
    private RetentionPreview retentionPreview;
    private RetentionJob retentionJob;
    private boolean applyingClassSelection;
    private final Runnable switchIdentityAction;
    private final DesktopAccessProfile accessProfile;

    public CloudCenterController(
        CloudApiClient api,
        CloudSessionService session,
        CloudLearningSyncService sync,
        AssignmentDeliveryService assignmentDeliveryService,
        DesktopAccessProfile accessProfile,
        Runnable switchIdentityAction,
        Consumer<AssignmentTaskContext> openAssignmentAction
    ) {
        this.api = api;
        this.session = session;
        this.sync = sync;
        this.assignmentDeliveryService = java.util.Objects.requireNonNull(assignmentDeliveryService);
        this.accessProfile = java.util.Objects.requireNonNull(accessProfile, "accessProfile must not be null");
        this.switchIdentityAction = java.util.Objects.requireNonNull(
            switchIdentityAction,
            "switchIdentityAction must not be null"
        );
        this.openAssignmentAction = java.util.Objects.requireNonNull(openAssignmentAction);
    }

    @FXML
    private void initialize() {
        memberRoleCombo.getItems().setAll(UserRole.TEACHER, UserRole.STUDENT);
        memberRoleCombo.setConverter(new StringConverter<>() {
            @Override public String toString(UserRole role) {
                if (role == null) return "";
                return role == UserRole.TEACHER ? AppI18n.get("CloudCenterController.1") : AppI18n.get("CloudCenterController.2");
            }

            @Override public UserRole fromString(String value) {
                return AppI18n.get("CloudCenterController.3").equals(value) ? UserRole.TEACHER : UserRole.STUDENT;
            }
        });
        memberRoleCombo.setValue(UserRole.STUDENT);
        assignmentDueTimeCombo.getItems().setAll(DeadlineValueConverter.timeOptions());
        assignmentDueTimeCombo.setValue(DeadlineValueConverter.DEFAULT_TIME);
        retentionCategoryCombo.getItems().setAll(RetentionCategory.values());
        retentionCategoryCombo.setValue(RetentionCategory.SYNC_EVENTS);
        classList.setPlaceholder(new Label(AppI18n.get("CloudCenterController.4")));
        assignmentList.setPlaceholder(new Label(AppI18n.get("CloudCenterController.5")));
        classList.getSelectionModel().selectedIndexProperty().addListener((observable, oldValue, newValue) -> {
            updateSelectedClassLabel();
            if (!applyingClassSelection) refreshAssignments();
        });
        assignmentList.getSelectionModel().selectedIndexProperty().addListener((observable, oldValue, newValue) -> {
            int index = newValue.intValue();
            if (index >= 0 && index < assignments.size()) {
                ClassAssignment selected = assignments.get(index);
                assignmentTitleField.setText(selected.title());
                assignmentDescriptionField.setText(selected.description());
                setAssignmentDeadline(selected.dueAt());
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
        studentAssignmentPane.setVisible(!canManageClass);
        studentAssignmentPane.setManaged(!canManageClass);
        assignmentAnalyticsPane.setVisible(canManageClass);
        assignmentAnalyticsPane.setManaged(canManageClass);
        exportClassRecordsButton.setVisible(canManageClass);
        exportClassRecordsButton.setManaged(canManageClass);
        boolean administrator = accessProfile.kind() == DesktopAccessProfile.Kind.ADMIN;
        adminOperationsPane.setVisible(administrator);
        adminOperationsPane.setManaged(administrator);
        updateSessionState();
        showInitialSessionStatus();
    }

    @FXML
    private void onCreateClass() {
        requireClassManager();
        String name = required(classNameField, AppI18n.get("CloudCenterController.6"));
        if (name == null) return;
        run(AppI18n.get("CloudCenterController.7"), () -> {
            var current = currentSession();
            var created = api.createClass(current.accessToken(), name);
            loadClasses(current.accessToken(), created.id());
            Platform.runLater(() -> {
                classNameField.clear();
                showStatus(AppI18n.get("CloudCenterController.8") + created.name() + AppI18n.get("CloudCenterController.9"), Status.SUCCESS);
            });
        });
    }

    @FXML
    private void onRefresh() {
        String selectedId = selectedClassId();
        run(AppI18n.get("CloudCenterController.10"), () -> {
            var current = currentSession();
            loadClasses(current.accessToken(), selectedId);
            Platform.runLater(() -> showStatus(AppI18n.get("CloudCenterController.11"), Status.SUCCESS));
        });
    }

    @FXML
    private void onLogout() {
        switchIdentityAction.run();
    }

    @FXML
    private void onSync() {
        run(AppI18n.get("CloudCenterController.12"), () -> {
            var result = sync.synchronize();
            var retry = assignmentDeliveryService.retryPending();
            Platform.runLater(() -> showStatus(
                AppI18n.get("CloudCenterController.13") + result.uploaded() + AppI18n.get("CloudCenterController.14") + result.downloaded()
                    + AppI18n.get("CloudCenterController.15") + retry.delivered() + AppI18n.get("CloudCenterController.16") + retry.remaining() + AppI18n.get("CloudCenterController.17")
                    + (retry.rejected() == 0 ? "" : AppI18n.get("CloudCenterController.18") + retry.rejected() + AppI18n.get("CloudCenterController.19")),
                Status.SUCCESS
            ));
        });
    }

    @FXML
    private void onOpenAssignment() {
        var selectedClass = selectedClass();
        int index = assignmentList.getSelectionModel().getSelectedIndex();
        if (selectedClass == null || index < 0 || index >= assignments.size()) {
            showStatus(AppI18n.get("CloudCenterController.20"), Status.ERROR);
            return;
        }
        ClassAssignment assignment = assignments.get(index);
        if (assignment.status() != AssignmentStatus.PUBLISHED) {
            showStatus(AppI18n.get("CloudCenterController.21"), Status.ERROR);
            return;
        }
        if (assignment.dueAt() != null && !Instant.now().isBefore(assignment.dueAt())) {
            showStatus(AppI18n.get("CloudCenterController.22"), Status.ERROR);
            return;
        }
        openAssignmentAction.accept(new AssignmentTaskContext(selectedClass.id(), assignment));
    }

    @FXML
    private void onExportClassRecords() {
        requireClassManager();
        var selected = selectedClass();
        if (selected == null) {
            showStatus(AppI18n.get("CloudCenterController.23"), Status.ERROR);
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(AppI18n.get("CloudCenterController.24"));
        chooser.setInitialFileName("SQLTeacher-" + selected.name().replaceAll("[\\/:*?\"<>|]", "_") + AppI18n.get("CloudCenterController.25"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(AppI18n.get("CloudCenterController.26"), "*.csv"));
        var selectedFile = chooser.showSaveDialog(classAnalyticsLabel.getScene().getWindow());
        if (selectedFile == null) return;
        Path target = selectedFile.toPath();
        run(AppI18n.get("CloudCenterController.27"), () -> {
            String csv = api.exportClassLearningCsv(currentSession().accessToken(), selected.id());
            try {
                Files.writeString(target, csv, StandardCharsets.UTF_8);
            } catch (java.io.IOException error) {
                throw new IllegalStateException(AppI18n.get("CloudCenterController.28"), error);
            }
            Platform.runLater(() -> showStatus(AppI18n.get("CloudCenterController.29") + target, Status.SUCCESS));
        });
    }

    @FXML
    private void onRefreshAdminOperations() {
        requireAdministrator();
        run(AppI18n.get("CloudCenterController.30"), () -> {
            String token = currentSession().accessToken();
            var health = api.getAdminHealth(token);
            List<AdminUserSummary> users = api.listAdminUsers(token);
            var audit = api.getAdminAudit(token, null, null, null, 0, 50);
            Platform.runLater(() -> {
                adminUsers = List.copyOf(users);
                adminHealthLabel.setText(AppI18n.get("CloudCenterController.31") + health.activeUsers() + AppI18n.get("CloudCenterController.32") + health.disabledUsers()
                    + AppI18n.get("CloudCenterController.33") + health.activeAccessSessions() + AppI18n.get("CloudCenterController.34") + health.assignments()
                    + AppI18n.get("CloudCenterController.35") + health.submissions());
                adminUserList.getItems().setAll(users.stream().map(user -> user.displayName() + " · " + user.email()
                    + " · " + (user.disabled() ? AppI18n.get("CloudCenterController.36") : AppI18n.get("CloudCenterController.37")) + " · " + user.roles()).toList());
                adminAuditList.getItems().setAll(audit.entries().stream().map(entry -> entry.createdAt() + " · "
                    + entry.action() + " · " + entry.result() + " · " + entry.reasonCode()).toList());
                showStatus(AppI18n.get("CloudCenterController.38"), Status.SUCCESS);
            });
        });
    }

    @FXML private void onDisableAdminUser() { changeAdminUser(true); }

    @FXML private void onRestoreAdminUser() { changeAdminUser(false); }

    @FXML
    private void onRevokeAdminUserSessions() {
        requireAdministrator();
        AdminUserSummary user = selectedAdminUser();
        String reason = required(adminReasonField, AppI18n.get("CloudCenterController.39"));
        if (user == null || reason == null) {
            if (user == null) showStatus(AppI18n.get("CloudCenterController.40"), Status.ERROR);
            return;
        }
        run(AppI18n.get("CloudCenterController.41"), () -> {
            api.revokeUserSessions(currentSession().accessToken(), user.id(), reason);
            Platform.runLater(() -> {
                adminReasonField.clear();
                showStatus(AppI18n.get("CloudCenterController.42"), Status.SUCCESS);
            });
        });
    }

    private void changeAdminUser(boolean disabled) {
        requireAdministrator();
        AdminUserSummary user = selectedAdminUser();
        String reason = required(adminReasonField, AppI18n.get("CloudCenterController.43"));
        if (user == null || reason == null) {
            if (user == null) showStatus(AppI18n.get("CloudCenterController.44"), Status.ERROR);
            return;
        }
        run(disabled ? AppI18n.get("CloudCenterController.45") : AppI18n.get("CloudCenterController.46"), () -> {
            api.setUserDisabled(currentSession().accessToken(), user.id(), disabled, reason);
            Platform.runLater(() -> {
                adminReasonField.clear();
                showStatus(disabled ? AppI18n.get("CloudCenterController.47") : AppI18n.get("CloudCenterController.48"), Status.SUCCESS);
                onRefreshAdminOperations();
            });
        });
    }

    @FXML
    private void onPreviewRetention() {
        requireAdministrator();
        RetentionCategory category = retentionCategoryCombo.getValue();
        Instant cutoff;
        try {
            cutoff = Instant.parse(required(retentionCutoffField, AppI18n.get("CloudCenterController.49")));
        } catch (RuntimeException error) {
            showStatus(AppI18n.get("CloudCenterController.50"), Status.ERROR);
            return;
        }
        run(AppI18n.get("CloudCenterController.51"), () -> {
            RetentionPreview preview = api.previewRetention(currentSession().accessToken(), category, cutoff);
            Platform.runLater(() -> {
                retentionPreview = preview;
                retentionStatusLabel.setText(AppI18n.get("CloudCenterController.52") + preview.id() + AppI18n.get("CloudCenterController.53") + preview.affectedRows()
                    + AppI18n.get("CloudCenterController.54") + preview.expiresAt());
                showStatus(AppI18n.get("CloudCenterController.55"), Status.SUCCESS);
            });
        });
    }

    @FXML
    private void onExecuteRetention() {
        requireAdministrator();
        if (retentionPreview == null) {
            showStatus(AppI18n.get("CloudCenterController.56"), Status.ERROR);
            return;
        }
        String backupReference = required(retentionBackupField, AppI18n.get("CloudCenterController.57"));
        if (backupReference == null) return;
        run(AppI18n.get("CloudCenterController.58"), () -> {
            RetentionJob job = api.executeRetention(currentSession().accessToken(), retentionPreview.id(),
                retentionPreview.confirmationToken(), backupReference);
            Platform.runLater(() -> {
                retentionJob = job;
                retentionPreview = null;
                retentionStatusLabel.setText(AppI18n.get("CloudCenterController.59") + job.id() + " · " + job.status()
                    + AppI18n.get("CloudCenterController.60") + job.affectedRows() + AppI18n.get("CloudCenterController.61"));
                showStatus(AppI18n.get("CloudCenterController.62"), Status.SUCCESS);
            });
        });
    }

    @FXML
    private void onRestoreRetention() {
        requireAdministrator();
        if (retentionJob == null || !"COMPLETED".equals(retentionJob.status())) {
            showStatus(AppI18n.get("CloudCenterController.63"), Status.ERROR);
            return;
        }
        run(AppI18n.get("CloudCenterController.64"), () -> {
            RetentionJob restored = api.restoreRetention(currentSession().accessToken(), retentionJob.id());
            Platform.runLater(() -> {
                retentionJob = restored;
                retentionStatusLabel.setText(AppI18n.get("CloudCenterController.65") + restored.id() + AppI18n.get("CloudCenterController.66"));
                showStatus(AppI18n.get("CloudCenterController.67"), Status.SUCCESS);
            });
        });
    }

    @FXML
    private void onAddMember() {
        requireClassManager();
        var selected = selectedClass();
        if (selected == null) {
            showStatus(AppI18n.get("CloudCenterController.68"), Status.ERROR);
            return;
        }
        String email = required(memberEmailField, AppI18n.get("CloudCenterController.69"));
        if (email == null) return;
        UserRole role = memberRoleCombo.getValue();
        run(AppI18n.get("CloudCenterController.70"), () -> {
            var current = currentSession();
            api.addClassMember(current.accessToken(), selected.id(), email, role);
            loadClasses(current.accessToken(), selected.id());
            Platform.runLater(() -> {
                memberEmailField.clear();
                showStatus(AppI18n.get("CloudCenterController.71") + selected.name() + "”", Status.SUCCESS);
            });
        });
    }

    @FXML
    private void onCreateAssignment() {
        createAssignment(false);
    }

    @FXML
    private void onSaveAssignmentDraft() {
        createAssignment(true);
    }

    @FXML
    private void onCopyAssignment() {
        requireClassManager();
        var selectedClass = selectedClass();
        ClassAssignment assignment = selectedAssignment();
        if (selectedClass == null || assignment == null) {
            showStatus(AppI18n.get("CloudCenterController.72"), Status.ERROR);
            return;
        }
        run(AppI18n.get("CloudCenterController.73"), () -> {
            var current = currentSession();
            api.copyAssignment(current.accessToken(), selectedClass.id(), assignment.id(), assignment.version());
            List<ClassAssignment> refreshed = api.listAssignments(current.accessToken(), selectedClass.id());
            Platform.runLater(() -> {
                applyAssignments(selectedClass.id(), refreshed);
                showStatus(AppI18n.get("CloudCenterController.74"), Status.SUCCESS);
            });
        });
    }

    private void createAssignment(boolean draft) {
        requireClassManager();
        var selected = selectedClass();
        if (selected == null) {
            showStatus(AppI18n.get("CloudCenterController.75"), Status.ERROR);
            return;
        }
        String exerciseId = required(assignmentExerciseField, AppI18n.get("CloudCenterController.76"));
        String title = required(assignmentTitleField, AppI18n.get("CloudCenterController.77"));
        if (exerciseId == null || title == null) return;
        Instant dueAt = selectedAssignmentDeadline();
        String description = assignmentDescriptionField.getText() == null ? "" : assignmentDescriptionField.getText().trim();
        run(draft ? AppI18n.get("CloudCenterController.78") : AppI18n.get("CloudCenterController.79"), () -> {
            var current = currentSession();
            ClassAssignment created = api.createAssignmentDraft(current.accessToken(), selected.id(), exerciseId,
                title, description, dueAt);
            if (!draft) api.changeAssignmentStatus(current.accessToken(), selected.id(), created.id(),
                AssignmentStatus.PUBLISHED, created.version());
            List<ClassAssignment> refreshed = api.listAssignments(current.accessToken(), selected.id());
            Platform.runLater(() -> {
                applyAssignments(selected.id(), refreshed);
                assignmentExerciseField.clear();
                assignmentTitleField.clear();
                assignmentDescriptionField.clear();
                setAssignmentDeadline(null);
                showStatus(draft ? AppI18n.get("CloudCenterController.80") : AppI18n.get("CloudCenterController.81"), Status.SUCCESS);
            });
        });
    }

    @FXML
    private void onRefreshAssignmentAnalytics() {
        requireClassManager();
        var selectedClass = selectedClass();
        var assignment = selectedAssignment();
        if (selectedClass == null || assignment == null) {
            showStatus(AppI18n.get("CloudCenterController.82"), Status.ERROR);
            return;
        }
        run(AppI18n.get("CloudCenterController.83"), () -> {
            AssignmentAnalyticsReport report = api.getAssignmentAnalytics(currentSession().accessToken(),
                selectedClass.id(), assignment.id(), AssignmentAnalyticsFilter.firstPage());
            Platform.runLater(() -> {
                assignmentAnalyticsLabel.setText(AppI18n.get("CloudCenterController.84") + report.totalStudents() + AppI18n.get("CloudCenterController.85")
                    + report.submittedStudents() + AppI18n.get("CloudCenterController.86") + report.passedStudents()
                    + AppI18n.get("CloudCenterController.87") + report.totalAttempts() + AppI18n.get("CloudCenterController.88")
                    + Math.round(report.completionRate() * 100) + "% · 通过率 "
                    + Math.round(report.passRate() * 100) + "%");
                showStatus(AppI18n.get("CloudCenterController.89"), Status.SUCCESS);
            });
        });
    }

    @FXML
    private void onExportAssignmentAnalytics() {
        requireClassManager();
        var selectedClass = selectedClass();
        var assignment = selectedAssignment();
        if (selectedClass == null || assignment == null) {
            showStatus(AppI18n.get("CloudCenterController.90"), Status.ERROR);
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(AppI18n.get("CloudCenterController.91"));
        chooser.setInitialFileName("SQLTeacher-" + assignment.title().replaceAll("[\\/:*?\"<>|]", "_") + AppI18n.get("CloudCenterController.92"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(AppI18n.get("CloudCenterController.93"), "*.csv"));
        var selectedFile = chooser.showSaveDialog(assignmentAnalyticsLabel.getScene().getWindow());
        if (selectedFile == null) return;
        run(AppI18n.get("CloudCenterController.94"), () -> {
            String csv = api.exportAssignmentAnalyticsCsv(currentSession().accessToken(), selectedClass.id(),
                assignment.id(), AssignmentAnalyticsFilter.firstPage());
            try {
                Files.writeString(selectedFile.toPath(), csv, StandardCharsets.UTF_8);
            } catch (java.io.IOException error) {
                throw new IllegalStateException(AppI18n.get("CloudCenterController.95"), error);
            }
            Platform.runLater(() -> showStatus(AppI18n.get("CloudCenterController.96"), Status.SUCCESS));
        });
    }

    @FXML
    private void onCloseAssignment() {
        changeSelectedAssignmentStatus(AssignmentStatus.CLOSED, AppI18n.get("CloudCenterController.97"));
    }

    @FXML
    private void onWithdrawAssignment() {
        changeSelectedAssignmentStatus(AssignmentStatus.WITHDRAWN, AppI18n.get("CloudCenterController.98"));
    }

    @FXML
    private void onArchiveAssignment() {
        changeSelectedAssignmentStatus(AssignmentStatus.ARCHIVED, AppI18n.get("CloudCenterController.99"));
    }

    @FXML
    private void onUpdateAssignment() {
        requireClassManager();
        var selectedClass = selectedClass();
        int index = assignmentList.getSelectionModel().getSelectedIndex();
        if (selectedClass == null || index < 0 || index >= assignments.size()) {
            showStatus(AppI18n.get("CloudCenterController.100"), Status.ERROR);
            return;
        }
        String title = required(assignmentTitleField, AppI18n.get("CloudCenterController.101"));
        if (title == null) return;
        Instant dueAt = selectedAssignmentDeadline();
        ClassAssignment assignment = assignments.get(index);
        String description = assignmentDescriptionField.getText() == null
            ? "" : assignmentDescriptionField.getText().trim();
        run(AppI18n.get("CloudCenterController.102"), () -> {
            var current = currentSession();
            api.updateAssignment(current.accessToken(), selectedClass.id(), assignment.id(), title,
                description, dueAt, assignment.version());
            List<ClassAssignment> refreshed = api.listAssignments(current.accessToken(), selectedClass.id());
            Platform.runLater(() -> {
                applyAssignments(selectedClass.id(), refreshed);
                showStatus(AppI18n.get("CloudCenterController.103"), Status.SUCCESS);
            });
        });
    }

    private Instant selectedAssignmentDeadline() {
        return DeadlineValueConverter.toInstant(
            assignmentDueDatePicker.getValue(), assignmentDueTimeCombo.getValue(), ZoneId.systemDefault()
        );
    }

    private void setAssignmentDeadline(Instant dueAt) {
        ZoneId zone = ZoneId.systemDefault();
        assignmentDueDatePicker.setValue(DeadlineValueConverter.datePart(dueAt, zone));
        assignmentDueTimeCombo.setValue(DeadlineValueConverter.timePart(dueAt, zone));
    }

    private void changeSelectedAssignmentStatus(AssignmentStatus status, String successMessage) {
        requireClassManager();
        var selectedClass = selectedClass();
        int index = assignmentList.getSelectionModel().getSelectedIndex();
        if (selectedClass == null || index < 0 || index >= assignments.size()) {
            showStatus(AppI18n.get("CloudCenterController.104"), Status.ERROR);
            return;
        }
        ClassAssignment assignment = assignments.get(index);
        run(AppI18n.get("CloudCenterController.105"), () -> {
            var current = currentSession();
            api.changeAssignmentStatus(current.accessToken(), selectedClass.id(), assignment.id(), status,
                assignment.version());
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
        run(AppI18n.get("CloudCenterController.106") + selected.name() + AppI18n.get("CloudCenterController.107"), () -> {
            List<ClassAssignment> assignments = api.listAssignments(current.orElseThrow().accessToken(), selected.id());
            var summary = canManageClass() ? api.getClassLearningSummary(current.orElseThrow().accessToken(), selected.id()) : null;
            Platform.runLater(() -> {
                applyAssignments(selected.id(), assignments);
                applyClassAnalytics(summary);
                showStatus(AppI18n.get("CloudCenterController.108") + selected.name() + "”", Status.INFO);
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
                .map(item -> item.name() + "  ·  " + item.members().size() + AppI18n.get("CloudCenterController.109"))
                .toList());
            if (selected == null) classList.getSelectionModel().clearSelection();
            else classList.getSelectionModel().select(loadedClasses.indexOf(selected));
        } finally {
            applyingClassSelection = false;
        }
        classSummaryLabel.setText(loadedClasses.isEmpty()
            ? AppI18n.get("CloudCenterController.110")
            : AppI18n.get("CloudCenterController.111") + loadedClasses.size() + AppI18n.get("CloudCenterController.112"));
        updateSelectedClassLabel();
        applyAssignments(selected == null ? null : selected.id(), assignments);
    }

    private void applyAssignments(String classroomId, List<ClassAssignment> assignments) {
        if (classroomId != null && !classroomId.equals(selectedClassId())) return;
        this.assignments = List.copyOf(assignments);
        assignmentList.getItems().setAll(assignments.stream()
            .map(item -> item.title() + AppI18n.get("CloudCenterController.113") + item.exerciseId() + " · " + assignmentStatusLabel(item.status())
                + (item.dueAt() == null ? "" : AppI18n.get("CloudCenterController.114") + item.dueAt()))
            .toList());
        assignmentList.setPlaceholder(new Label(classroomId == null
            ? AppI18n.get("CloudCenterController.115")
            : AppI18n.get("CloudCenterController.116")));
        updateSubmissionQueueLabel();
    }

    private void updateSubmissionQueueLabel() {
        if (canManageClass()) {
            submissionQueueLabel.setText(AppI18n.get("CloudCenterController.117"));
            return;
        }
        try {
            int pending = assignmentDeliveryService.pendingCount();
            submissionQueueLabel.setText(pending == 0 ? AppI18n.get("CloudCenterController.118") : AppI18n.get("CloudCenterController.119") + pending + AppI18n.get("CloudCenterController.120"));
        } catch (RuntimeException error) {
            submissionQueueLabel.setText(AppI18n.get("CloudCenterController.121"));
        }
    }

    private void applyClassAnalytics(com.sqlteacher.application.collaboration.ClassLearningSummary summary) {
        if (summary == null) {
            classAnalyticsLabel.setText(AppI18n.get("CloudCenterController.122"));
            return;
        }
        classAnalyticsLabel.setText(AppI18n.get("CloudCenterController.123") + summary.studentCount() + AppI18n.get("CloudCenterController.124")
            + summary.activeStudentCount() + AppI18n.get("CloudCenterController.125") + summary.syncedEvents() + AppI18n.get("CloudCenterController.126")
            + summary.successfulEvents() + AppI18n.get("CloudCenterController.127"));
    }

    private String assignmentStatusLabel(AssignmentStatus status) {
        return switch (status) {
            case DRAFT -> AppI18n.get("CloudCenterController.128");
            case PUBLISHED -> AppI18n.get("CloudCenterController.129");
            case CLOSED -> AppI18n.get("CloudCenterController.130");
            case WITHDRAWN -> AppI18n.get("CloudCenterController.131");
            case ARCHIVED -> AppI18n.get("CloudCenterController.132");
        };
    }

    private ClassroomService.Classroom selectedClass() {
        int index = classList.getSelectionModel().getSelectedIndex();
        return index >= 0 && index < classrooms.size() ? classrooms.get(index) : null;
    }

    private ClassAssignment selectedAssignment() {
        int index = assignmentList.getSelectionModel().getSelectedIndex();
        return index >= 0 && index < assignments.size() ? assignments.get(index) : null;
    }

    private AdminUserSummary selectedAdminUser() {
        int index = adminUserList.getSelectionModel().getSelectedIndex();
        return index >= 0 && index < adminUsers.size() ? adminUsers.get(index) : null;
    }

    private String selectedClassId() {
        var selected = selectedClass();
        return selected == null ? null : selected.id();
    }

    private void updateSelectedClassLabel() {
        var selected = selectedClass();
        selectedClassLabel.setText(selected == null
            ? AppI18n.get("CloudCenterController.133")
            : selected.name() + " · " + selected.members().size() + AppI18n.get("CloudCenterController.134"));
    }

    private void updateSessionState() {
        var current = session.current();
        boolean signedIn = current.isPresent();
        accountLabel.setText(current
            .map(value -> value.user().displayName() + " · " + value.user().roles().stream().map(this::roleName).sorted().reduce((a, b) -> a + "/" + b).orElse(AppI18n.get("CloudCenterController.135")))
            .orElse(AppI18n.get("CloudCenterController.136")));
        signedOutPrompt.setVisible(!signedIn);
        signedOutPrompt.setManaged(!signedIn);
        authenticatedContent.setVisible(signedIn);
        authenticatedContent.setManaged(signedIn);
        authenticatedContent.setDisable(signedIn && activeOperations.get() > 0);
        logoutButton.setDisable(!signedIn || activeOperations.get() > 0);
    }

    private void showInitialSessionStatus() {
        boolean signedIn = session.current().isPresent();
        showStatus(
            AppI18n.get(signedIn ? "cloud-center.status.signed-in" : "cloud-center.status.signed-out"),
            signedIn ? Status.SUCCESS : Status.INFO
        );
    }

    private String roleName(UserRole role) {
        return switch (role) {
            case ADMIN -> AppI18n.get("CloudCenterController.137");
            case TEACHER -> AppI18n.get("CloudCenterController.138");
            case STUDENT -> AppI18n.get("CloudCenterController.139");
        };
    }

    private boolean canManageClass() {
        return accessProfile.kind() == DesktopAccessProfile.Kind.TEACHER
            || accessProfile.kind() == DesktopAccessProfile.Kind.ADMIN;
    }

    private void requireClassManager() {
        if (!canManageClass()) throw new SecurityException(AppI18n.get("CloudCenterController.140"));
    }

    private void requireAdministrator() {
        if (accessProfile.kind() != DesktopAccessProfile.Kind.ADMIN) {
            throw new SecurityException(AppI18n.get("CloudCenterController.141"));
        }
    }

    private CloudAuthenticationService.Session currentSession() {
        return session.current().orElseThrow(() -> new IllegalStateException(AppI18n.get("CloudCenterController.142")));
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
        statusLabel.setText(message == null || message.isBlank() ? AppI18n.get("CloudCenterController.143") : message);
        statusBanner.getStyleClass().removeAll(STATUS_STYLES);
        statusBanner.getStyleClass().add(status.styleClass);
    }

    private static String message(RuntimeException error) {
        return error.getMessage() == null || error.getMessage().isBlank()
            ? AppI18n.get("CloudCenterController.144")
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
