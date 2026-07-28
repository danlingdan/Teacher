package com.sqlteacher.desktop.controller;

import com.sqlteacher.desktop.appearance.UiIcon;
import com.sqlteacher.desktop.appearance.UiIcons;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

public final class HomeController {

    @FXML private Label sqlIcon;
    @FXML private Label aiIcon;
    @FXML private Label schemaIcon;

    private Runnable onNavigateAiAssistant;
    private Runnable onNavigateSqlPractice;
    private Runnable onNavigateTableSchema;

    @FXML
    private void initialize() {
        sqlIcon.setGraphic(UiIcons.create(UiIcon.CODE, 30));
        aiIcon.setGraphic(UiIcons.create(UiIcon.SPARK, 30));
        schemaIcon.setGraphic(UiIcons.create(UiIcon.TABLE, 30));
    }

    public void setOnNavigateAiAssistant(Runnable onNavigateAiAssistant) {
        this.onNavigateAiAssistant = onNavigateAiAssistant;
    }

    public void setOnNavigateSqlPractice(Runnable onNavigateSqlPractice) {
        this.onNavigateSqlPractice = onNavigateSqlPractice;
    }

    public void setOnNavigateTableSchema(Runnable onNavigateTableSchema) {
        this.onNavigateTableSchema = onNavigateTableSchema;
    }

    @FXML
    private void onAiAssistantCardClick() {
        if (onNavigateAiAssistant != null) {
            onNavigateAiAssistant.run();
        }
    }

    @FXML
    private void onSqlPracticeCardClick() {
        if (onNavigateSqlPractice != null) {
            onNavigateSqlPractice.run();
        }
    }

    @FXML
    private void onTableSchemaCardClick() {
        if (onNavigateTableSchema != null) {
            onNavigateTableSchema.run();
        }
    }
}
