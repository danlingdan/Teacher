package com.sqlteacher.desktop.controller;
import com.sqlteacher.desktop.AppI18n;

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
        restorePersistedSession();
    }

    private void restorePersistedSession() {
        setBusy(true, AppI18n.get("LoginGateController.1"));
        DesktopExecutors.background().execute(() -> {
            var restored = sessions.refresh();
            Platform.runLater(() -> restored.ifPresentOrElse(
                value -> onAuthenticated.accept(DesktopAccessProfile.from(value)),
                () -> setBusy(false, null)
            ));
        });
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
            showError(AppI18n.get("LoginGateController.2"));
            emailField.requestFocus();
            return;
        }
        if (register && displayName.isBlank()) {
            showError(AppI18n.get("LoginGateController.3"));
            displayNameField.requestFocus();
            return;
        }
        if (passwordText == null || passwordText.isBlank()) {
            showError(AppI18n.get("LoginGateController.4"));
            passwordField.requestFocus();
            return;
        }

        setBusy(true, register ? AppI18n.get("LoginGateController.5") : AppI18n.get("LoginGateController.6"));
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
        if (message != null && message.contains("HTTP 401")) return AppI18n.get("LoginGateController.7");
        if (message != null && message.contains("HTTP 409")) return AppI18n.get("LoginGateController.8");
        if (message != null && message.contains("HTTP 400")) return register
            ? AppI18n.get("LoginGateController.9")
            : AppI18n.get("LoginGateController.10");
        return message == null || message.isBlank() ? AppI18n.get("LoginGateController.11") : message;
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().trim();
    }
}
