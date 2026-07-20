package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.connection.DatabaseConnectionProfile;
import com.sqlteacher.application.connection.DatabaseConnectionTarget;
import com.sqlteacher.application.connection.DatabaseConnectionTestResult;
import com.sqlteacher.application.connection.DatabaseConnectionTestService;
import com.sqlteacher.application.connection.DatabaseDialect;
import com.sqlteacher.application.connection.DatabaseCredentialSession;
import com.sqlteacher.application.connection.ServerConnectionTarget;
import com.sqlteacher.application.connection.SqliteConnectionTarget;
import com.sqlteacher.application.error.ApplicationExceptionMapper;
import com.sqlteacher.desktop.DesktopExecutors;
import com.sqlteacher.desktop.GlobalLoading;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ConnectionSettingsController {
    private final ConnectionManagementService managementService;
    private final DatabaseConnectionTestService testService;
    private final ApplicationExceptionMapper exceptionMapper;
    private final DatabaseCredentialSession credentialSession;

    @FXML private ListView<DatabaseConnectionProfile> profileList;
    @FXML private Label currentLabel;
    @FXML private Label statusLabel;
    @FXML private TextField idField;
    @FXML private TextField nameField;
    @FXML private ComboBox<DatabaseDialect> dialectBox;
    @FXML private TextField pathField;
    @FXML private TextField hostField;
    @FXML private TextField portField;
    @FXML private TextField databaseField;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private CheckBox readOnlyCheck;
    @FXML private CheckBox enabledCheck;
    @FXML private Button saveButton;
    @FXML private Button deleteButton;
    @FXML private Button selectButton;
    @FXML private Button testButton;

    public ConnectionSettingsController(
        ConnectionManagementService managementService,
        DatabaseConnectionTestService testService,
        ApplicationExceptionMapper exceptionMapper,
        DatabaseCredentialSession credentialSession
    ) {
        this.managementService = Objects.requireNonNull(managementService);
        this.testService = Objects.requireNonNull(testService);
        this.exceptionMapper = Objects.requireNonNull(exceptionMapper);
        this.credentialSession = Objects.requireNonNull(credentialSession);
    }

    @FXML
    private void initialize() {
        dialectBox.getItems().setAll(DatabaseDialect.values());
        dialectBox.setValue(DatabaseDialect.SQLITE);
        dialectBox.valueProperty().addListener((ignored, oldValue, newValue) -> updateTargetFields());
        profileList.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected void updateItem(DatabaseConnectionProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                    : item.displayName() + "  ·  " + item.dialect() + (item.enabled() ? "" : "  ·  已禁用"));
            }
        });
        profileList.getSelectionModel().selectedItemProperty().addListener(
            (ignored, oldValue, selected) -> showProfile(selected)
        );
        updateTargetFields();
        refreshProfiles(null);
    }

    @FXML private void onRefresh() { refreshProfiles(profileIdOfSelection()); }

    @FXML
    private void onNew() {
        profileList.getSelectionModel().clearSelection();
        clearForm();
        idField.requestFocus();
    }

    @FXML
    private void onSave() {
        DatabaseConnectionProfile profile;
        try {
            profile = buildProfileFromForm();
        } catch (RuntimeException error) {
            showStatus(error.getMessage(), true);
            return;
        }
        credentialSession.forget(profile.id());
        runAsync("正在保存连接…", () -> managementService.saveProfile(profile), saved -> {
            showStatus("连接配置已保存。", false);
            refreshProfiles(saved.id());
        });
    }

    @FXML
    private void onTest() {
        DatabaseConnectionProfile profile;
        try {
            profile = buildProfileFromForm();
        } catch (RuntimeException error) {
            showStatus(error.getMessage(), true);
            return;
        }
        char[] password = passwordField.getText().toCharArray();
        passwordField.clear();
        runAsync("正在测试数据库连接…", () -> {
            try {
                DatabaseConnectionTestResult result = testService.testConnection(profile, password);
                boolean savedServerProfile = result.successful()
                    && profile.target() instanceof ServerConnectionTarget
                    && managementService.findProfile(profile.id()).filter(profile::equals).isPresent();
                if (savedServerProfile) {
                    credentialSession.remember(profile.id(), password);
                } else {
                    credentialSession.forget(profile.id());
                }
                return result;
            } finally {
                Arrays.fill(password, '\0');
            }
        }, result -> showStatus(formatTestResult(result), !result.successful()));
    }

    @FXML
    private void onSelect() {
        DatabaseConnectionProfile selected = profileList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showStatus("请先选择一个已保存的连接。", true);
            return;
        }
        runAsync("正在切换当前连接…", () -> managementService.selectProfile(selected.id()), profile -> {
            showStatus("当前连接已切换为：" + profile.displayName(), false);
            refreshProfiles(profile.id());
        });
    }

    @FXML
    private void onDelete() {
        DatabaseConnectionProfile selected = profileList.getSelectionModel().getSelectedItem();
        if (selected == null || selected.builtIn()) {
            showStatus("内置连接不能删除。", true);
            return;
        }
        Alert confirmation = new Alert(
            Alert.AlertType.CONFIRMATION,
            "确定删除连接“" + selected.displayName() + "”吗？",
            ButtonType.CANCEL,
            ButtonType.OK
        );
        confirmation.setHeaderText("删除连接配置");
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        runAsync("正在删除连接…", () -> {
            credentialSession.forget(selected.id());
            managementService.removeProfile(selected.id());
            return null;
        }, ignored -> {
            showStatus("连接配置已删除。", false);
            refreshProfiles("demo");
        });
    }

    private void refreshProfiles(String selectedId) {
        runAsync("正在加载连接配置…", () -> new ProfilesSnapshot(
            managementService.listProfiles(),
            managementService.currentProfile()
        ), snapshot -> {
            profileList.getItems().setAll(snapshot.profiles());
            currentLabel.setText("当前连接：" + snapshot.current()
                .map(DatabaseConnectionProfile::displayName).orElse("-") );
            String targetId = selectedId != null ? selectedId
                : snapshot.current().map(DatabaseConnectionProfile::id).orElse(null);
            if (targetId != null) {
                snapshot.profiles().stream().filter(profile -> profile.id().equals(targetId)).findFirst()
                    .ifPresent(profileList.getSelectionModel()::select);
            }
        });
    }

    private void showProfile(DatabaseConnectionProfile profile) {
        if (profile == null) {
            updateActions(null);
            return;
        }
        idField.setText(profile.id());
        nameField.setText(profile.displayName());
        dialectBox.setValue(profile.dialect());
        readOnlyCheck.setSelected(profile.readOnly());
        enabledCheck.setSelected(profile.enabled());
        passwordField.clear();
        if (profile.target() instanceof SqliteConnectionTarget sqlite) {
            pathField.setText(sqlite.databasePath().toString());
            clearServerFields();
        } else if (profile.target() instanceof ServerConnectionTarget server) {
            pathField.clear();
            hostField.setText(server.host());
            portField.setText(Integer.toString(server.port()));
            databaseField.setText(server.databaseName());
            usernameField.setText(server.username());
        }
        updateTargetFields();
        updateActions(profile);
    }

    private void updateActions(DatabaseConnectionProfile profile) {
        boolean builtIn = profile != null && profile.builtIn();
        saveButton.setDisable(builtIn);
        deleteButton.setDisable(profile == null || builtIn);
        selectButton.setDisable(profile == null || !profile.enabled());
        idField.setDisable(builtIn);
        dialectBox.setDisable(builtIn);
    }

    private void clearForm() {
        idField.clear(); nameField.clear(); pathField.clear(); clearServerFields(); passwordField.clear();
        dialectBox.setValue(DatabaseDialect.SQLITE);
        readOnlyCheck.setSelected(true); enabledCheck.setSelected(true);
        updateActions(null); updateTargetFields(); showStatus("请输入新的连接配置。", false);
    }

    private void clearServerFields() {
        hostField.clear(); portField.clear(); databaseField.clear(); usernameField.clear();
    }

    private void updateTargetFields() {
        boolean sqlite = dialectBox.getValue() == DatabaseDialect.SQLITE;
        pathField.setDisable(!sqlite);
        hostField.setDisable(sqlite); portField.setDisable(sqlite);
        databaseField.setDisable(sqlite); usernameField.setDisable(sqlite); passwordField.setDisable(sqlite);
    }

    private DatabaseConnectionProfile buildProfileFromForm() {
        return buildProfile(
            idField.getText(), nameField.getText(), dialectBox.getValue(), pathField.getText(),
            hostField.getText(), portField.getText(), databaseField.getText(), usernameField.getText(),
            readOnlyCheck.isSelected(), enabledCheck.isSelected()
        );
    }

    static DatabaseConnectionProfile buildProfile(
        String id, String name, DatabaseDialect dialect, String path, String host, String port,
        String database, String username, boolean readOnly, boolean enabled
    ) {
        Objects.requireNonNull(dialect, "请选择数据库类型。");
        DatabaseConnectionTarget target;
        if (dialect == DatabaseDialect.SQLITE) {
            if (path == null || path.isBlank()) throw new IllegalArgumentException("请输入 SQLite 文件路径。");
            target = new SqliteConnectionTarget(Path.of(path.trim()));
        } else {
            int parsedPort;
            try { parsedPort = Integer.parseInt(port == null ? "" : port.trim()); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("端口必须是 1-65535 的整数。"); }
            target = new ServerConnectionTarget(dialect, host, parsedPort, database, username);
        }
        return new DatabaseConnectionProfile(id, name, target, readOnly, enabled, false);
    }

    private <T> void runAsync(String message, Supplier<T> task, Consumer<T> onSuccess) {
        GlobalLoading.show(message);
        DesktopExecutors.background().execute(() -> {
            try {
                T result = task.get();
                Platform.runLater(() -> { GlobalLoading.hide(); onSuccess.accept(result); });
            } catch (Throwable error) {
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    showStatus(exceptionMapper.map(error).userMessage(), true);
                });
            }
        });
    }

    private void showStatus(String message, boolean error) {
        statusLabel.setText(message == null || message.isBlank() ? "输入内容无效，请检查后重试。" : message);
        statusLabel.getStyleClass().removeAll("sql-result-hint", "sql-error-hint");
        statusLabel.getStyleClass().add(error ? "sql-error-hint" : "sql-result-hint");
        statusLabel.setVisible(true); statusLabel.setManaged(true);
    }

    private String profileIdOfSelection() {
        DatabaseConnectionProfile selected = profileList.getSelectionModel().getSelectedItem();
        return selected == null ? null : selected.id();
    }

    private static String formatTestResult(DatabaseConnectionTestResult result) {
        if (!result.successful()) return result.message();
        String product = result.databaseProduct().isBlank() ? "数据库" : result.databaseProduct();
        String version = result.databaseVersion().isBlank() ? "" : " " + result.databaseVersion();
        return result.message() + " " + product + version;
    }

    private record ProfilesSnapshot(
        List<DatabaseConnectionProfile> profiles,
        Optional<DatabaseConnectionProfile> current
    ) { }
}
