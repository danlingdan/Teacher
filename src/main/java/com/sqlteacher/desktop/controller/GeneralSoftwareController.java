package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.collaboration.CloudApiClient;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.support.*;
import com.sqlteacher.application.system.*;
import com.sqlteacher.application.update.*;
import com.sqlteacher.desktop.AppI18n;
import com.sqlteacher.desktop.DesktopExecutors;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class GeneralSoftwareController {
    private final UpdateService updates;
    private final ProblemReportService reports;
    private final DiagnosticBundleService diagnostics;
    private final GeneralSoftwareService system;
    private final CloudApiClient cloudApi;
    private final CloudSessionService sessions;
    private final Runnable switchIdentity;
    private UpdateManifest available;
    private Path installer;
    private String reportDraftId = UUID.randomUUID().toString();

    @FXML private VBox root;
    @FXML private Label buildInfoLabel;
    @FXML private Label updateStatusLabel;
    @FXML private Label updateDetailsLabel;
    @FXML private ProgressBar updateProgress;
    @FXML private Button downloadButton;
    @FXML private Button installButton;
    @FXML private CheckBox automaticUpdatesCheck;
    @FXML private ComboBox<GeneralSoftwareSettings.ProxyMode> proxyModeCombo;
    @FXML private TextField proxyHostField;
    @FXML private Spinner<Integer> proxyPortSpinner;
    @FXML private CheckBox reducedMotionCheck;
    @FXML private CheckBox highContrastCheck;
    @FXML private CheckBox supportLoggingCheck;
    @FXML private ComboBox<String> languageCombo;
    @FXML private Label connectivityLabel;
    @FXML private Label storageLabel;
    @FXML private ListView<String> taskList;
    @FXML private ListView<String> notificationList;
    @FXML private ComboBox<ProblemReportDraft.Type> reportTypeCombo;
    @FXML private ComboBox<ProblemReportDraft.Severity> reportSeverityCombo;
    @FXML private TextField reportSummaryField;
    @FXML private TextArea reportDescriptionArea;
    @FXML private TextArea reportStepsArea;
    @FXML private TextField reportContactField;
    @FXML private CheckBox environmentCheck;
    @FXML private CheckBox errorsCheck;
    @FXML private CheckBox networkCheck;
    @FXML private CheckBox updateStateCheck;
    @FXML private TextArea diagnosticPreviewArea;
    @FXML private Label reportStatusLabel;
    @FXML private PasswordField currentPasswordField;
    @FXML private PasswordField newPasswordField;
    @FXML private PasswordField confirmPasswordField;
    @FXML private Label accountStatusLabel;
    @FXML private ComboBox<String> helpTopicCombo;
    @FXML private TextArea helpTextArea;

    public GeneralSoftwareController(UpdateService updates, ProblemReportService reports,
                                     DiagnosticBundleService diagnostics, GeneralSoftwareService system,
                                     CloudApiClient cloudApi, CloudSessionService sessions, Runnable switchIdentity) {
        this.updates = Objects.requireNonNull(updates); this.reports = Objects.requireNonNull(reports);
        this.diagnostics = Objects.requireNonNull(diagnostics); this.system = Objects.requireNonNull(system);
        this.cloudApi = Objects.requireNonNull(cloudApi); this.sessions = Objects.requireNonNull(sessions);
        this.switchIdentity = Objects.requireNonNull(switchIdentity);
    }

    @FXML private void initialize() {
        buildInfoLabel.setText(ApplicationBuildInfo.current().supportSummary());
        proxyModeCombo.setItems(FXCollections.observableArrayList(GeneralSoftwareSettings.ProxyMode.values()));
        languageCombo.setItems(FXCollections.observableArrayList(AppI18n.get("GeneralSoftwareController.1"), "English"));
        proxyPortSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 65535, 0));
        reportTypeCombo.setItems(FXCollections.observableArrayList(ProblemReportDraft.Type.values()));
        reportSeverityCombo.setItems(FXCollections.observableArrayList(ProblemReportDraft.Severity.values()));
        reportTypeCombo.setValue(ProblemReportDraft.Type.BUG);
        reportSeverityCombo.setValue(ProblemReportDraft.Severity.PARTIAL_FAILURE);
        helpTopicCombo.setItems(FXCollections.observableArrayList(system.helpTopics()));
        helpTopicCombo.valueProperty().addListener((ignored, old, value) -> { if (value != null) helpTextArea.setText(system.help(value)); });
        if (!helpTopicCombo.getItems().isEmpty()) helpTopicCombo.setValue(helpTopicCombo.getItems().getFirst());
        loadSettings(); refreshSystemViews(); previewDiagnostics();
        downloadButton.setDisable(true); installButton.setDisable(true); updateProgress.setVisible(false);
        if (diagnostics.previousRunCrashed()) reportStatusLabel.setText(AppI18n.get("GeneralSoftwareController.2"));
    }

    @FXML private void onCheckUpdates() {
        updateStatusLabel.setText(AppI18n.get("GeneralSoftwareController.3"));
        run(() -> updates.check(true), result -> {
            updateStatusLabel.setText(result.message()); available = result.available();
            downloadButton.setDisable(result.status() != UpdateCheckResult.Status.AVAILABLE && result.status() != UpdateCheckResult.Status.UNSUPPORTED);
            updateDetailsLabel.setText(available == null ? "" : AppI18n.get("GeneralSoftwareController.4") + available.publishedAt() + AppI18n.get("GeneralSoftwareController.5") + available.releaseNotesUrl()
                + AppI18n.get("GeneralSoftwareController.6") + formatBytes(available.installerSize()));
            refreshSystemViews();
        }, error -> updateStatusLabel.setText(AppI18n.get("GeneralSoftwareController.7") + safe(error)));
    }

    @FXML private void onDownloadUpdate() {
        if (available == null) return;
        updateProgress.setProgress(0); updateProgress.setVisible(true); downloadButton.setDisable(true);
        run(() -> updates.download(available, value -> Platform.runLater(() -> updateProgress.setProgress(value))), result -> {
            installer = result; installButton.setDisable(!updates.ready(available, installer));
            updateStatusLabel.setText(installButton.isDisable() ? AppI18n.get("GeneralSoftwareController.8") : AppI18n.get("GeneralSoftwareController.9"));
            refreshSystemViews();
        }, error -> { updateStatusLabel.setText(AppI18n.get("GeneralSoftwareController.10") + safe(error)); downloadButton.setDisable(false); });
    }

    @FXML private void onInstallUpdate() {
        if (available == null || installer == null || !updates.ready(available, installer)) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, AppI18n.get("GeneralSoftwareController.11"), ButtonType.CANCEL, ButtonType.OK);
        alert.setTitle(AppI18n.get("GeneralSoftwareController.12") + available.version()); alert.setHeaderText(AppI18n.get("GeneralSoftwareController.13"));
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        updates.launchInstaller(available, installer); Platform.exit();
    }

    @FXML private void onSkipUpdate() { if (available != null) { updates.skip(available.version()); updateStatusLabel.setText(AppI18n.get("GeneralSoftwareController.14") + available.version() + AppI18n.get("GeneralSoftwareController.15")); } }

    @FXML private void onSaveGeneralSettings() {
        GeneralSoftwareSettings old = system.settings();
        long supportExpiry = supportLoggingCheck.isSelected() ? Instant.now().plusSeconds(30 * 60).toEpochMilli() : 0;
        String language = "English".equals(languageCombo.getValue()) ? "en" : "zh";
        try {
            system.saveSettings(new GeneralSoftwareSettings(1, automaticUpdatesCheck.isSelected(), old.skippedVersion(), proxyModeCombo.getValue(),
                proxyHostField.getText(), proxyPortSpinner.getValue(), reducedMotionCheck.isSelected(), highContrastCheck.isSelected(),
                supportLoggingCheck.isSelected(), supportExpiry, old.updateMirrorsEnabled(), language));
            AppI18n.applyLanguage(language);
            applyAccessibility();
            connectivityLabel.setText(AppI18n.get("GeneralSoftwareController.16"));
        } catch (RuntimeException error) { connectivityLabel.setText(AppI18n.get("GeneralSoftwareController.17") + safe(error)); }
    }

    @FXML private void onExportSettings() {
        FileChooser chooser = new FileChooser(); chooser.setTitle(AppI18n.get("GeneralSoftwareController.18")); chooser.setInitialFileName("SQLTeacher-settings-1.10.json");
        File selected = chooser.showSaveDialog(root.getScene().getWindow());
        if (selected != null) connectivityLabel.setText(AppI18n.get("GeneralSoftwareController.19") + system.exportSettings(selected.toPath()).getFileName());
    }
    @FXML private void onImportSettings() {
        FileChooser chooser = new FileChooser(); chooser.setTitle(AppI18n.get("GeneralSoftwareController.20")); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        File selected = chooser.showOpenDialog(root.getScene().getWindow());
        if (selected == null) return;
        try { system.importSettings(selected.toPath()); loadSettings(); connectivityLabel.setText(AppI18n.get("GeneralSoftwareController.21")); }
        catch (RuntimeException error) { connectivityLabel.setText(AppI18n.get("GeneralSoftwareController.22") + safe(error)); }
    }
    @FXML private void onResetSettings() { system.saveSettings(GeneralSoftwareSettings.defaults()); loadSettings(); connectivityLabel.setText(AppI18n.get("GeneralSoftwareController.23")); }
    @FXML private void onConnectivityCheck() { connectivityLabel.setText(AppI18n.get("GeneralSoftwareController.24")); run(system::connectivitySummary, value -> connectivityLabel.setText(value), error -> connectivityLabel.setText(AppI18n.get("GeneralSoftwareController.25") + safe(error))); }
    @FXML private void onRefreshStorage() { refreshSystemViews(); }
    @FXML private void onClearRebuildable() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, AppI18n.get("GeneralSoftwareController.26"), ButtonType.CANCEL, ButtonType.OK);
        if (alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) run(system::clearRebuildableFiles, ignored -> refreshSystemViews(), error -> storageLabel.setText(AppI18n.get("GeneralSoftwareController.27") + safe(error)));
    }

    @FXML private void onPreviewDiagnostics() { previewDiagnostics(); }
    @FXML private void onExportDiagnostics() {
        DirectoryChooser chooser = new DirectoryChooser(); chooser.setTitle(AppI18n.get("GeneralSoftwareController.28")); File selected = chooser.showDialog(root.getScene().getWindow());
        if (selected == null) return;
        run(() -> diagnostics.export(selection(), selected.toPath()), path -> reportStatusLabel.setText(AppI18n.get("GeneralSoftwareController.29") + path.getFileName()), error -> reportStatusLabel.setText(AppI18n.get("GeneralSoftwareController.30") + safe(error)));
    }
    @FXML private void onSubmitReport() {
        try {
            ProblemReportDraft draft = new ProblemReportDraft(reportDraftId, reportTypeCombo.getValue(), reportSeverityCombo.getValue(),
                reportSummaryField.getText(), reportDescriptionArea.getText(), reportStepsArea.getText(), "", "", reportContactField.getText(), selection(), null);
            Map<String, Object> preview = diagnostics.preview(selection()); diagnosticPreviewArea.setText(pretty(preview));
            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION, AppI18n.get("GeneralSoftwareController.31"), ButtonType.CANCEL, ButtonType.OK);
            confirmation.setHeaderText(AppI18n.get("GeneralSoftwareController.32"));
            if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
            String token = sessions.refresh().map(value -> value.accessToken()).orElse(null);
            reportStatusLabel.setText(AppI18n.get("GeneralSoftwareController.33"));
            run(() -> reports.submit(draft, preview, token), receipt -> {
                reportStatusLabel.setText(AppI18n.get("GeneralSoftwareController.34") + receipt.reportId());
                reportDraftId = UUID.randomUUID().toString();
                system.notify(AppNotification.Category.SUPPORT, AppI18n.get("GeneralSoftwareController.35"), AppI18n.get("GeneralSoftwareController.36") + receipt.reportId(), "support"); refreshSystemViews();
            }, error -> reportStatusLabel.setText(AppI18n.get("GeneralSoftwareController.37") + safe(error)));
        } catch (RuntimeException error) { reportStatusLabel.setText(AppI18n.get("GeneralSoftwareController.38") + safe(error)); }
    }

    @FXML private void onChangePassword() {
        if (sessions.current().isEmpty()) { accountStatusLabel.setText(AppI18n.get("GeneralSoftwareController.39")); return; }
        if (!newPasswordField.getText().equals(confirmPasswordField.getText())) { accountStatusLabel.setText(AppI18n.get("GeneralSoftwareController.40")); return; }
        char[] current = currentPasswordField.getText().toCharArray(); char[] replacement = newPasswordField.getText().toCharArray();
        String token = sessions.current().orElseThrow().accessToken(); accountStatusLabel.setText(AppI18n.get("GeneralSoftwareController.41"));
        run(() -> { cloudApi.changePassword(token, current, replacement); return true; }, ignored -> {
            sessions.signOut(); clearPasswords(); accountStatusLabel.setText(AppI18n.get("GeneralSoftwareController.42")); switchIdentity.run();
        }, error -> { java.util.Arrays.fill(current, '\0'); java.util.Arrays.fill(replacement, '\0'); accountStatusLabel.setText(AppI18n.get("GeneralSoftwareController.43") + safe(error)); });
    }

    private void loadSettings() {
        GeneralSoftwareSettings value = system.settings(); automaticUpdatesCheck.setSelected(value.automaticUpdateChecks());
        proxyModeCombo.setValue(value.proxyMode()); proxyHostField.setText(value.proxyHost()); proxyPortSpinner.getValueFactory().setValue(value.proxyPort());
        reducedMotionCheck.setSelected(value.reducedMotion()); highContrastCheck.setSelected(value.highContrast()); supportLoggingCheck.setSelected(value.supportLogging());
        languageCombo.setValue("en".equalsIgnoreCase(value.language()) ? "English" : AppI18n.get("GeneralSoftwareController.44"));
        applyAccessibility();
    }
    private void refreshSystemViews() {
        StorageOverview storage = system.storage(); storageLabel.setText(storage.categoryBytes().entrySet().stream().map(item -> item.getKey() + " " + formatBytes(item.getValue())).collect(java.util.stream.Collectors.joining(" · "))
            + (storage.usableBytes() >= 0 ? AppI18n.get("GeneralSoftwareController.45") + formatBytes(storage.usableBytes()) : ""));
        taskList.setItems(FXCollections.observableArrayList(system.tasks().stream().map(item -> item.title() + " · " + item.status() + (item.errorCode().isBlank() ? "" : " · " + item.errorCode())).toList()));
        notificationList.setItems(FXCollections.observableArrayList(system.notifications().stream().map(item -> (item.read() ? "" : "● ") + item.title() + " · " + item.message()).toList()));
    }
    private DiagnosticSelection selection() { return new DiagnosticSelection(environmentCheck.isSelected(), errorsCheck.isSelected(), networkCheck.isSelected(), updateStateCheck.isSelected()); }
    private void previewDiagnostics() { diagnosticPreviewArea.setText(pretty(diagnostics.preview(selection()))); }
    private static String pretty(Object value) { try { return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValueAsString(value); } catch (Exception error) { return AppI18n.get("GeneralSoftwareController.46"); } }
    private static String safe(Throwable error) {
        String value = error.getMessage();
        if (value == null || value.isBlank()) return AppI18n.get("GeneralSoftwareController.47");
        value = value.replaceAll("(?i)(bearer|token|password|authorization|api.?key)\\s*[:=]?\\s*\\S+", "$1=[REDACTED]")
            .replaceAll("(?i)([A-Z]:\\\\|/home/|/Users/)[^\\s]+", "[LOCAL_PATH]");
        return value.substring(0, Math.min(value.length(), 240));
    }
    private void applyAccessibility() {
        root.getStyleClass().removeAll("high-contrast", "reduced-motion");
        if (highContrastCheck.isSelected()) root.getStyleClass().add("high-contrast");
        if (reducedMotionCheck.isSelected()) root.getStyleClass().add("reduced-motion");
    }
    private static String formatBytes(long bytes) { if (bytes < 1024) return bytes + " B"; if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0); return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0)); }
    private void clearPasswords() { currentPasswordField.clear(); newPasswordField.clear(); confirmPasswordField.clear(); }
    private <T> void run(java.util.concurrent.Callable<T> work, java.util.function.Consumer<T> success, java.util.function.Consumer<Throwable> failure) {
        java.util.concurrent.CompletableFuture.supplyAsync(() -> { try { return work.call(); } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); } }, DesktopExecutors.background())
            .whenComplete((value, error) -> Platform.runLater(() -> { if (error == null) success.accept(value); else failure.accept(error.getCause() == null ? error : error.getCause()); }));
    }
}
