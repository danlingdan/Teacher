package com.sqlteacher.desktop.controller;
import com.sqlteacher.desktop.AppI18n;

import com.sqlteacher.application.risk.SqlSafetyModeService;
import javafx.fxml.FXML;
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
        unrestrictedModeCheck.setSelected(safetyModeService.isDeveloperModeEnabled());
        renderStatus();
    }

    @FXML
    private void onToggleUnrestrictedMode() {
        boolean requested = unrestrictedModeCheck.isSelected();
        try {
            safetyModeService.setDeveloperModeEnabled(requested);
            renderStatus();
        } catch (RuntimeException error) {
            unrestrictedModeCheck.setSelected(safetyModeService.isDeveloperModeEnabled());
            statusLabel.setText(error.getMessage() == null ? AppI18n.get("SqlSafetySettingsController.1") : error.getMessage());
            setStatusStyle("status-error");
        }
    }

    private void renderStatus() {
        if (safetyModeService.isDeveloperModeEnabled()) {
            statusLabel.setText(AppI18n.get("SqlSafetySettingsController.8"));
            setStatusStyle("status-success");
        } else {
            statusLabel.setText(AppI18n.get("SqlSafetySettingsController.9"));
            setStatusStyle("status-info");
        }
    }

    private void setStatusStyle(String styleClass) {
        statusLabel.getStyleClass().removeAll("status-info", "status-success", "status-error");
        statusLabel.getStyleClass().add(styleClass);
    }
}
