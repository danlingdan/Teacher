package com.sqlteacher.desktop.component;

import com.sqlteacher.desktop.appearance.UiIcon;
import com.sqlteacher.desktop.appearance.UiIcons;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** Reusable empty, loading and failure presentation. Business state remains controller-owned. */
public final class StatePanel extends VBox {
    private final StringProperty title = new SimpleStringProperty(this, "title", "");
    private final StringProperty message = new SimpleStringProperty(this, "message", "");

    public StatePanel() {
        setAlignment(Pos.CENTER);
        setSpacing(8);
        getStyleClass().add("state-panel");
        getChildren().add(UiIcons.create(UiIcon.BOOK, 28));

        Label titleLabel = new Label();
        titleLabel.getStyleClass().add("state-panel-title");
        titleLabel.textProperty().bind(title);

        Label messageLabel = new Label();
        messageLabel.getStyleClass().add("state-panel-message");
        messageLabel.textProperty().bind(message);
        messageLabel.setWrapText(true);
        getChildren().addAll(titleLabel, messageLabel);
    }

    public String getTitle() { return title.get(); }
    public void setTitle(String value) { title.set(value == null ? "" : value); }
    public StringProperty titleProperty() { return title; }

    public String getMessage() { return message.get(); }
    public void setMessage(String value) { message.set(value == null ? "" : value); }
    public StringProperty messageProperty() { return message; }
}
