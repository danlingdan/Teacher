package com.sqlteacher.desktop.controller;
import com.sqlteacher.desktop.AppI18n;

import com.sqlteacher.application.risk.SqlSafetyModeService;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;

import java.util.Objects;

public final class SqlSafetySettingsController {
    private final SqlSafetyModeService safetyModeService;

    @FXML private CheckBox unrestrictedModeCheck;
    @FXML private Label statusLabel;

    public SqlSafetySettingsController(SqlSafetyModeService safetyModeService) {
        this.safetyModeService = Objects.requireNonNull(safetyModeService);
    }

    @FXML
    private void initialize() {
        unrestrictedModeCheck.setSelected(safetyModeService.isUnrestrictedModeEnabled());
        renderStatus();
    }

    @FXML
    private void onToggleUnrestrictedMode() {
        boolean requested = unrestrictedModeCheck.isSelected();
        if (requested && !confirmEnable()) {
            unrestrictedModeCheck.setSelected(false);
            renderStatus();
            return;
        }
        try {
            safetyModeService.setUnrestrictedModeEnabled(requested);
            renderStatus();
        } catch (RuntimeException error) {
            unrestrictedModeCheck.setSelected(safetyModeService.isUnrestrictedModeEnabled());
            statusLabel.setText(error.getMessage() == null ? AppI18n.get("SqlSafetySettingsController.1") : error.getMessage());
            setStatusStyle("status-error");
        }
    }

    private boolean confirmEnable() {
        ButtonType enable = new ButtonType(AppI18n.get("SqlSafetySettingsController.2"), ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(
            Alert.AlertType.WARNING,
            AppI18n.get("SqlSafetySettingsController.3")
                + AppI18n.get("SqlSafetySettingsController.4")
                + AppI18n.get("SqlSafetySettingsController.5"),
            enable,
            ButtonType.CANCEL
        );
        alert.setTitle(AppI18n.get("SqlSafetySettingsController.6"));
        alert.setHeaderText(AppI18n.get("SqlSafetySettingsController.7"));
        return alert.showAndWait().filter(enable::equals).isPresent();
    }

    private void renderStatus() {
        if (safetyModeService.isUnrestrictedModeEnabled()) {
            statusLabel.setText(AppI18n.get("SqlSafetySettingsController.8"));
            setStatusStyle("status-error");
        } else {
            statusLabel.setText(AppI18n.get("SqlSafetySettingsController.9"));
            setStatusStyle("status-success");
        }
    }

    private void setStatusStyle(String styleClass) {
        statusLabel.getStyleClass().removeAll("status-info", "status-success", "status-error");
        statusLabel.getStyleClass().add(styleClass);
    }
}
