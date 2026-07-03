package com.sqlteacher.desktop;

import com.sqlteacher.application.ai.AiStatus;
import com.sqlteacher.application.ai.AiStatusService;
import com.sqlteacher.application.database.DatabaseInitializationResult;
import com.sqlteacher.application.database.DatabaseInitializationService;
import com.sqlteacher.infrastructure.spring.SqlTeacherApplicationConfig;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public final class SqlTeacherFxApp extends Application {
    private AnnotationConfigApplicationContext context;

    @Override
    public void start(Stage stage) {
        context = new AnnotationConfigApplicationContext(SqlTeacherApplicationConfig.class);
        DatabaseInitializationResult databaseResult = context.getBean(DatabaseInitializationService.class).initialize();
        AiStatus aiStatus = context.getBean(AiStatusService.class).checkStatus();

        Label title = new Label("SQLTeacher");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");

        Label subtitle = new Label("Stage 1 desktop shell");
        Label databaseStatus = new Label("SQLite app database: " + databaseResult.appDatabasePath());
        Label demoStatus = new Label("SQLite demo database: " + databaseResult.demoDatabasePath());
        Label aiStatusLabel = new Label("Ollama: " + aiStatus.message());
        Button settingsButton = new Button("Settings");
        settingsButton.setDisable(true);

        VBox root = new VBox(12, title, subtitle, databaseStatus, demoStatus, aiStatusLabel, settingsButton);
        root.setPadding(new Insets(24));
        root.setMinSize(520, 220);

        stage.setTitle("SQLTeacher");
        stage.setScene(new Scene(root));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void stop() {
        if (context != null) {
            context.close();
        }
    }
}
