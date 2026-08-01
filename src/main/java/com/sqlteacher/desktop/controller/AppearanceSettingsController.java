package com.sqlteacher.desktop.controller;
import com.sqlteacher.desktop.AppI18n;

import com.sqlteacher.desktop.appearance.UiDensity;
import com.sqlteacher.desktop.appearance.UiFontChoice;
import com.sqlteacher.desktop.appearance.UiPreferencesService;
import com.sqlteacher.desktop.appearance.UiPreferencesSnapshot;
import com.sqlteacher.desktop.appearance.UiTheme;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;

import java.util.Objects;

public final class AppearanceSettingsController {
    private final UiPreferencesService preferences;

    @FXML private ComboBox<UiTheme> themeCombo;
    @FXML private ComboBox<UiFontChoice> fontCombo;
    @FXML private ComboBox<UiDensity> densityCombo;
    @FXML private CheckBox reducedMotionCheck;
    @FXML private Label statusLabel;

    public AppearanceSettingsController(UiPreferencesService preferences) {
        this.preferences = Objects.requireNonNull(preferences);
    }

    @FXML
    private void initialize() {
        themeCombo.getItems().setAll(UiTheme.values());
        fontCombo.getItems().setAll(UiFontChoice.values());
        densityCombo.getItems().setAll(UiDensity.values());
        show(preferences.current());
    }

    @FXML
    private void onApply() {
        UiTheme theme = themeCombo.getValue();
        UiFontChoice font = fontCombo.getValue();
        UiDensity density = densityCombo.getValue();
        if (theme == null || font == null || density == null) {
            statusLabel.setText(AppI18n.get("AppearanceSettingsController.1"));
            statusLabel.getStyleClass().setAll("status-error");
            return;
        }
        preferences.save(new UiPreferencesSnapshot(theme, font, density, reducedMotionCheck.isSelected()));
        statusLabel.setText(AppI18n.get("AppearanceSettingsController.2"));
        statusLabel.getStyleClass().setAll("status-success");
    }

    @FXML
    private void onReset() {
        preferences.reset();
        show(preferences.current());
        statusLabel.setText(AppI18n.get("AppearanceSettingsController.3"));
        statusLabel.getStyleClass().setAll("status-info");
    }

    private void show(UiPreferencesSnapshot snapshot) {
        themeCombo.setValue(snapshot.theme());
        fontCombo.setValue(snapshot.font());
        densityCombo.setValue(snapshot.density());
        reducedMotionCheck.setSelected(snapshot.reducedMotion());
    }
}
