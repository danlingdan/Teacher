package com.sqlteacher.desktop.controller;

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

    @FXML private Label versionLabel;
    @FXML private Label dataDirectoryLabel;
    @FXML private Label maintenanceStatusLabel;
    @FXML private ListView<BackupSnapshot> backupList;

    public DataMaintenanceController(
            ApplicationBackupService backupService,
            SqlTeacherConfiguration configuration) {
        this.backupService = Objects.requireNonNull(backupService);
        this.configuration = Objects.requireNonNull(configuration);
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
                    String kind = item.automatic() ? "自动" : "手动";
                    String time = DISPLAY_TIME.format(item.createdAt().atZone(ZoneId.systemDefault()));
                    setText(kind + "备份 · " + time + " · " + formatBytes(item.sizeBytes()));
                }
            }
        });
        refreshBackups();
    }

    @FXML
    private void onRefreshBackups() {
        refreshBackups();
    }

    @FXML
    private void onCreateBackup() {
        runAsync("正在创建安全备份…", () -> {
            BackupSnapshot snapshot = backupService.createBackup();
            Platform.runLater(() -> {
                maintenanceStatusLabel.setText("备份已创建：" + snapshot.id());
                refreshBackups();
            });
        });
    }

    @FXML
    private void onRestoreBackup() {
        BackupSnapshot selected = backupList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            maintenanceStatusLabel.setText("请先选择一个备份。");
            return;
        }
        Alert confirmation = new Alert(
            Alert.AlertType.CONFIRMATION,
            "恢复会用所选备份替换当前应用数据。系统会先自动保存当前数据，完成后应用将退出，请重新打开。",
            ButtonType.CANCEL,
            ButtonType.OK
        );
        confirmation.setTitle("确认恢复备份");
        confirmation.setHeaderText("恢复 " + selected.id() + "？");
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        runAsync("正在校验并恢复备份…", () -> {
            backupService.restoreBackup(selected.id());
            Platform.runLater(() -> {
                new Alert(Alert.AlertType.INFORMATION, "恢复完成。SQLTeacher 现在将退出，请重新打开应用。")
                    .showAndWait();
                Platform.exit();
            });
        });
    }

    @FXML
    private void onRestoreDemo() {
        Alert confirmation = new Alert(
            Alert.AlertType.CONFIRMATION,
            "演示数据库将恢复为内置学生示例数据，不影响题库、学习记录和连接设置。",
            ButtonType.CANCEL,
            ButtonType.OK
        );
        confirmation.setTitle("恢复演示数据库");
        confirmation.setHeaderText("确认恢复演示数据？");
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        runAsync("正在恢复演示数据库…", () -> Platform.runLater(
            () -> maintenanceStatusLabel.setText("演示数据库已恢复。")
        ), backupService::restoreDemoDatabase);
    }

    private void refreshBackups() {
        runAsync("正在读取备份…", () -> {
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
                maintenanceStatusLabel.setText("操作失败：" + cause.getMessage());
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
