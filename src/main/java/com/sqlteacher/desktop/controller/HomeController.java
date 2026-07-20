package com.sqlteacher.desktop.controller;

import javafx.fxml.FXML;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;



public final class HomeController {

    @FXML
    private VBox aiAssistantCard;

    @FXML
    private VBox sqlPracticeCard;

    @FXML
    private VBox tableSchemaCard;

    private Runnable onNavigateAiAssistant;
    private Runnable onNavigateSqlPractice;
    private Runnable onNavigateTableSchema;

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
    private void initialize() {
    }

    @FXML
    private void onAiAssistantCardClick(MouseEvent event) {
        if (onNavigateAiAssistant != null) {
            onNavigateAiAssistant.run();
        }
    }

    @FXML
    private void onSqlPracticeCardClick(MouseEvent event) {
        if (onNavigateSqlPractice != null) {
            onNavigateSqlPractice.run();
        }
    }

    @FXML
    private void onTableSchemaCardClick(MouseEvent event) {
        if (onNavigateTableSchema != null) {
            onNavigateTableSchema.run();
        }
    }
}