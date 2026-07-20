package com.sqlteacher.desktop.controller;

import javafx.fxml.FXML;

public final class HomeController {

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
