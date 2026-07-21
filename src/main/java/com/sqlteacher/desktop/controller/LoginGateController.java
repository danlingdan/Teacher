package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.collaboration.CloudApiClient;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.CloudLearningSyncService;
import com.sqlteacher.application.collaboration.DesktopAccessProfile;
import com.sqlteacher.desktop.DesktopExecutors;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Mandatory startup identity gate with server login, registration, and an explicit local guest mode. */
public final class LoginGateController {
    private static final Logger LOG = LoggerFactory.getLogger(LoginGateController.class);
    private final CloudApiClient api;
    private final CloudSessionService sessions;
    private final CloudLearningSyncService sync;
    private final Consumer<DesktopAccessProfile> onAuthenticated;

    @FXML private TextField emailField;
    @FXML private TextField displayNameField;
    @FXML private PasswordField passwordField;
    @FXML private Label statusLabel;
    @FXML private HBox statusBox;
    @FXML private ProgressIndicator progressIndicator;
    @FXML private Button loginButton;
    @FXML private Button registerButton;
    @FXML private Button guestButton;

    public LoginGateController(
        CloudApiClient api,
        CloudSessionService sessions,
        CloudLearningSyncService sync,
        Consumer<DesktopAccessProfile> onAuthenticated
    ) {
        this.api = Objects.requireNonNull(api, "api must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.sync = Objects.requireNonNull(sync, "sync must not be null");
        this.onAuthenticated = Objects.requireNonNull(onAuthenticated, "onAuthenticated must not be null");
    }

    @FXML
    private void initialize() {
        statusBox.setVisible(false);
        statusBox.setManaged(false);
    }

    @FXML private void onLogin() { authenticate(false); }
    @FXML private void onRegister() { authenticate(true); }

    @FXML
    private void onGuest() {
        sessions.signOut();
        onAuthenticated.accept(DesktopAccessProfile.guest());
    }

    private void authenticate(boolean register) {
        String email = text(emailField);
        String displayName = text(displayNameField);
        String passwordText = passwordField.getText();
        if (email.isBlank()) {
            showError("请输入邮箱地址");
            emailField.requestFocus();
            return;
        }
        if (register && displayName.isBlank()) {
            showError("注册账号时请输入昵称");
            displayNameField.requestFocus();
            return;
        }
        if (passwordText == null || passwordText.isBlank()) {
            showError("请输入密码");
            passwordField.requestFocus();
            return;
        }

        setBusy(true, register ? "正在创建学生账号…" : "正在验证账号…");
        char[] password = passwordText.toCharArray();
        DesktopExecutors.background().execute(() -> {
            try {
                var cloudSession = register
                    ? api.register(email, displayName, password)
                    : api.login(email, password);
                sessions.signIn(cloudSession);
                DesktopAccessProfile profile = DesktopAccessProfile.from(cloudSession);
                try {
                    sync.synchronize();
                } catch (RuntimeException error) {
                    LOG.warn("Initial account data synchronization failed: {}", error.getMessage());
                }
                Platform.runLater(() -> {
                    passwordField.clear();
                    onAuthenticated.accept(profile);
                });
            } catch (RuntimeException error) {
                Platform.runLater(() -> {
                    setBusy(false, null);
                    showError(userMessage(error, register));
                });
            } finally {
                Arrays.fill(password, '\0');
            }
        });
    }

    private void setBusy(boolean busy, String message) {
        progressIndicator.setVisible(busy);
        progressIndicator.setManaged(busy);
        loginButton.setDisable(busy);
        registerButton.setDisable(busy);
        guestButton.setDisable(busy);
        emailField.setDisable(busy);
        displayNameField.setDisable(busy);
        passwordField.setDisable(busy);
        if (message != null) {
            statusLabel.setText(message);
            statusBox.getStyleClass().remove("login-status-error");
            statusBox.getStyleClass().add("login-status-info");
            statusBox.setVisible(true);
            statusBox.setManaged(true);
        }
    }

    private void showError(String message) {
        statusLabel.setText(message);
        statusBox.getStyleClass().remove("login-status-info");
        statusBox.getStyleClass().add("login-status-error");
        statusBox.setVisible(true);
        statusBox.setManaged(true);
    }

    private static String userMessage(RuntimeException error, boolean register) {
        String message = error.getMessage();
        if (message != null && message.contains("HTTP 401")) return "邮箱或密码不正确";
        if (message != null && message.contains("HTTP 409")) return "该邮箱已经注册，请直接登录";
        if (message != null && message.contains("HTTP 400")) return register
            ? "注册信息不符合要求，请检查邮箱和密码长度"
            : "登录信息格式不正确";
        return message == null || message.isBlank() ? "无法连接云服务，请稍后重试" : message;
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().trim();
    }
}
