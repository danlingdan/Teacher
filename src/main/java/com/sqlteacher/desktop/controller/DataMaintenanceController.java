package com.sqlteacher.desktop.controller;
import com.sqlteacher.desktop.AppI18n;

import com.sqlteacher.application.config.ApplicationVersion;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.maintenance.ApplicationBackupService;
import com.sqlteacher.application.maintenance.BackupSnapshot;
import com.sqlteacher.desktop.GlobalLoading;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class DataMaintenanceController {
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ApplicationBackupService backupService;
    private final SqlTeacherConfiguration configuration;
    private final boolean maintenanceAllowed;

    @FXML private Label versionLabel;
    @FXML private Label dataDirectoryLabel;
    @FXML private Label maintenanceStatusLabel;
    @FXML private ListView<BackupSnapshot> backupList;
    @FXML private Button refreshBackupsButton;
    @FXML private Button createBackupButton;
    @FXML private Button restoreDemoButton;
    @FXML private Button restoreBackupButton;

    public DataMaintenanceController(
            ApplicationBackupService backupService,
            SqlTeacherConfiguration configuration,
            boolean maintenanceAllowed) {
        this.backupService = Objects.requireNonNull(backupService);
        this.configuration = Objects.requireNonNull(configuration);
        this.maintenanceAllowed = maintenanceAllowed;
    }

    @FXML
    private void initialize() {
        versionLabel.setText("SQLTeacher " + ApplicationVersion.current());
        dataDirectoryLabel.setText(configuration.dataDirectory().toString());
        backupList.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected void updateItem(BackupSnapshot item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    String kind = item.automatic() ? AppI18n.get("DataMaintenanceController.1") : AppI18n.get("DataMaintenanceController.2");
                    String time = DISPLAY_TIME.format(item.createdAt().atZone(ZoneId.systemDefault()));
                    setText(kind + AppI18n.get("DataMaintenanceController.3") + time + " · " + formatBytes(item.sizeBytes()));
                }
            }
        });
        if (maintenanceAllowed) {
            refreshBackups();
        } else {
            String reason = AppI18n.get("DataMaintenanceController.4");
            backupList.setDisable(true);
            backupList.setPlaceholder(new Label(reason));
            refreshBackupsButton.setDisable(true);
            createBackupButton.setDisable(true);
            restoreDemoButton.setDisable(true);
            restoreBackupButton.setDisable(true);
            maintenanceStatusLabel.setText(reason);
        }
    }

    @FXML
    private void onRefreshBackups() {
        refreshBackups();
    }

    @FXML
    private void onCreateBackup() {
        runAsync(AppI18n.get("DataMaintenanceController.5"), () -> {
            BackupSnapshot snapshot = backupService.createBackup();
            Platform.runLater(() -> {
                maintenanceStatusLabel.setText(AppI18n.get("DataMaintenanceController.6") + snapshot.id());
                refreshBackups();
            });
        });
    }

    @FXML
    private void onRestoreBackup() {
        BackupSnapshot selected = backupList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            maintenanceStatusLabel.setText(AppI18n.get("DataMaintenanceController.7"));
            return;
        }
        Alert confirmation = new Alert(
            Alert.AlertType.CONFIRMATION,
            AppI18n.get("DataMaintenanceController.8"),
            ButtonType.CANCEL,
            ButtonType.OK
        );
        confirmation.setTitle(AppI18n.get("DataMaintenanceController.9"));
        confirmation.setHeaderText(AppI18n.get("DataMaintenanceController.10") + selected.id() + AppI18n.get("DataMaintenanceController.11"));
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        runAsync(AppI18n.get("DataMaintenanceController.12"), () -> {
            backupService.restoreBackup(selected.id());
            Platform.runLater(() -> {
                new Alert(Alert.AlertType.INFORMATION, AppI18n.get("DataMaintenanceController.13"))
                    .showAndWait();
                Platform.exit();
            });
        });
    }

    @FXML
    private void onRestoreDemo() {
        Alert confirmation = new Alert(
            Alert.AlertType.CONFIRMATION,
            AppI18n.get("DataMaintenanceController.14"),
            ButtonType.CANCEL,
            ButtonType.OK
        );
        confirmation.setTitle(AppI18n.get("DataMaintenanceController.15"));
        confirmation.setHeaderText(AppI18n.get("DataMaintenanceController.16"));
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        runAsync(AppI18n.get("DataMaintenanceController.17"), () -> Platform.runLater(
            () -> maintenanceStatusLabel.setText(AppI18n.get("DataMaintenanceController.18"))
        ), backupService::restoreDemoDatabase);
    }

    private void refreshBackups() {
        runAsync(AppI18n.get("DataMaintenanceController.19"), () -> {
            var snapshots = backupService.listBackups();
            Platform.runLater(() -> backupList.setItems(FXCollections.observableArrayList(snapshots)));
        });
    }

    private void runAsync(String loadingText, Runnable operation) {
        runAsync(loadingText, operation, () -> { });
    }

    private void runAsync(String loadingText, Runnable successUi, Runnable operation) {
        GlobalLoading.show(loadingText);
        CompletableFuture.runAsync(operation).whenComplete((ignored, error) -> Platform.runLater(() -> {
            GlobalLoading.hide();
            if (error == null) {
                successUi.run();
            } else {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause() : error;
                maintenanceStatusLabel.setText(AppI18n.get("DataMaintenanceController.20") + cause.getMessage());
            }
        }));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
