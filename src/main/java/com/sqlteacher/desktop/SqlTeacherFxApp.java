package com.sqlteacher.desktop;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public final class SqlTeacherFxApp extends Application {
    @Override
    public void start(Stage stage) {
        Label title = new Label("SQLTeacher");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");

        Label subtitle = new Label("Stage 0 JavaFX technology verification");
        Label status = new Label("Desktop shell can start. Core pages will be added in later stages.");

        VBox root = new VBox(12, title, subtitle, status);
        root.setPadding(new Insets(24));
        root.setMinSize(520, 220);

        stage.setTitle("SQLTeacher");
        stage.setScene(new Scene(root));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
