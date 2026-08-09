package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.component.ManagedComponentId;
import com.sqlteacher.application.component.ManagedComponentService;
import com.sqlteacher.application.component.ManagedComponentStatus;
import com.sqlteacher.application.runner.LocalCodeRunner;
import com.sqlteacher.application.runner.RunnerCapability;
import com.sqlteacher.desktop.AppI18n;
import com.sqlteacher.desktop.DesktopExecutors;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;

/** Presents the runtime compatibility that is actually available on this computer. */
public final class EnvironmentSettingsController {
    private final LocalCodeRunner localCodeRunner;
    private final ManagedComponentService componentService;

    @FXML private VBox capabilityList;
    @FXML private VBox componentList;
    @FXML private Label operationStatus;
    @FXML private ProgressBar installProgress;
    @FXML private Button cancelButton;

    private ManagedComponentId activeInstall;

    EnvironmentSettingsController(LocalCodeRunner localCodeRunner, ManagedComponentService componentService) {
        this.localCodeRunner = Objects.requireNonNull(localCodeRunner);
        this.componentService = Objects.requireNonNull(componentService);
    }

    @FXML
    private void initialize() {
        refreshAll();
    }

    @FXML
    private void onRefresh() {
        refreshAll();
    }

    @FXML
    private void onCancel() {
        if (activeInstall != null) componentService.cancel(activeInstall);
    }

    private void refreshAll() {
        operationStatus.setText(AppI18n.get("environment-settings.5"));
        DesktopExecutors.background().execute(() -> {
            try {
                List<RunnerCapability> capabilities = localCodeRunner.capabilities();
                List<ManagedComponentStatus> components = componentService.statuses();
                Platform.runLater(() -> render(capabilities, components));
            } catch (Throwable error) {
                Platform.runLater(() -> operationStatus.setText(
                    AppI18n.get("environment-settings.6") + " " + safe(error)));
            }
        });
    }

    private void render(List<RunnerCapability> capabilities, List<ManagedComponentStatus> components) {
        capabilityList.getChildren().setAll(capabilities.stream()
            .map(EnvironmentSettingsController::capabilityLabel).toList());
        componentList.getChildren().setAll(components.stream().map(this::componentRow).toList());
        operationStatus.setText(AppI18n.get("environment-settings.7"));
        installProgress.setVisible(false);
        installProgress.setManaged(false);
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);
        activeInstall = null;
    }

    private HBox componentRow(ManagedComponentStatus component) {
        Label title = new Label(componentName(component.id()));
        title.getStyleClass().add("card-title");
        Label state = new Label(stateText(component));
        state.getStyleClass().add(component.ready() ? "model-status" : "sql-result-hint");
        VBox text = new VBox(3, title, state);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button install = new Button(component.state() == ManagedComponentStatus.State.FAILED
            ? AppI18n.get("environment-settings.9") : AppI18n.get("environment-settings.8"));
        install.getStyleClass().add("refresh-button");
        install.setDisable(component.ready() || component.state() == ManagedComponentStatus.State.INSTALLING);
        install.setOnAction(ignored -> confirmAndInstall(component));
        HBox row = new HBox(12, text, spacer, install);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("panel-card");
        return row;
    }

    private void confirmAndInstall(ManagedComponentStatus component) {
        String impact = AppI18n.get("environment-settings.10") + " " + component.source()
            + "\n" + AppI18n.get("environment-settings.11") + " " + component.license()
            + (component.requiresAdministrator() ? "\n" + AppI18n.get("environment-settings.12") : "")
            + (component.restartMayBeRequired() ? "\n" + AppI18n.get("environment-settings.13") : "");
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION, impact, ButtonType.CANCEL, ButtonType.OK);
        confirmation.setTitle(AppI18n.get("environment-settings.14"));
        confirmation.setHeaderText(AppI18n.get("environment-settings.15") + " " + componentName(component.id()));
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        startInstall(component.id());
    }

    private void startInstall(ManagedComponentId id) {
        activeInstall = id;
        installProgress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        installProgress.setVisible(true);
        installProgress.setManaged(true);
        cancelButton.setVisible(true);
        cancelButton.setManaged(true);
        operationStatus.setText(AppI18n.get("environment-settings.16") + " " + componentName(id));
        componentList.setDisable(true);
        DesktopExecutors.background().execute(() -> {
            try {
                ManagedComponentStatus result = componentService.install(id, progress -> Platform.runLater(() -> {
                    installProgress.setProgress(progress.fraction());
                    operationStatus.setText(progressText(progress.message(), id));
                }));
                Platform.runLater(() -> {
                    componentList.setDisable(false);
                    operationStatus.setText(result.ready()
                        ? AppI18n.get("environment-settings.17") + " " + componentName(id)
                        : stateText(result));
                    refreshAll();
                });
            } catch (Throwable error) {
                Platform.runLater(() -> {
                    componentList.setDisable(false);
                    operationStatus.setText(AppI18n.get("environment-settings.18") + " " + safe(error));
                    refreshAll();
                });
            }
        });
    }

    private static Label capabilityLabel(RunnerCapability capability) {
        String state = capability.available() ? "✓ " : "— ";
        String languageName = capability.language().name().equals("CPP") ? "C++" : capability.language().name();
        Label label = new Label(state + languageName
            + (capability.available() ? "" : "  ·  " + capability.reasonCode()));
        label.getStyleClass().add(capability.available() ? "model-status" : "sql-error-hint");
        return label;
    }

    private static String componentName(ManagedComponentId id) {
        return AppI18n.get("environment-settings.component." + id.name());
    }

    private static String stateText(ManagedComponentStatus status) {
        String state = AppI18n.get("environment-settings.state." + status.state().name());
        return state + (status.detail().isBlank() ? "" : " · " + status.detail());
    }

    private static String progressText(String message, ManagedComponentId id) {
        return AppI18n.get("environment-settings.progress." + message) + " " + componentName(id);
    }

    private static String safe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
