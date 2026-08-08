package com.sqlteacher.desktop.component;

import javafx.beans.DefaultProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Shared page heading with stable hierarchy and an optional action area. */
@DefaultProperty("actions")
public final class PageHeader extends HBox {
    private final StringProperty eyebrow = new SimpleStringProperty(this, "eyebrow", "");
    private final StringProperty title = new SimpleStringProperty(this, "title", "");
    private final StringProperty description = new SimpleStringProperty(this, "description", "");
    private final HBox actions = new HBox(8);

    public PageHeader() {
        getStyleClass().add("page-header");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(16);

        Label eyebrowLabel = new Label();
        eyebrowLabel.getStyleClass().add("page-eyebrow");
        eyebrowLabel.textProperty().bind(eyebrow);
        eyebrowLabel.visibleProperty().bind(eyebrow.isNotEmpty());
        eyebrowLabel.managedProperty().bind(eyebrowLabel.visibleProperty());

        Label titleLabel = new Label();
        titleLabel.getStyleClass().add("page-title");
        titleLabel.textProperty().bind(title);
        titleLabel.setWrapText(true);

        Label descriptionLabel = new Label();
        descriptionLabel.getStyleClass().add("page-subtitle");
        descriptionLabel.textProperty().bind(description);
        descriptionLabel.setWrapText(true);
        descriptionLabel.visibleProperty().bind(description.isNotEmpty());
        descriptionLabel.managedProperty().bind(descriptionLabel.visibleProperty());

        VBox copy = new VBox(4, eyebrowLabel, titleLabel, descriptionLabel);
        HBox.setHgrow(copy, Priority.ALWAYS);
        actions.getStyleClass().add("page-header-actions");
        actions.setAlignment(Pos.CENTER_RIGHT);
        getChildren().addAll(copy, actions);
    }

    public ObservableList<Node> getActions() {
        return actions.getChildren();
    }

    public String getEyebrow() { return eyebrow.get(); }
    public void setEyebrow(String value) { eyebrow.set(value == null ? "" : value); }
    public StringProperty eyebrowProperty() { return eyebrow; }

    public String getTitle() { return title.get(); }
    public void setTitle(String value) { title.set(value == null ? "" : value); }
    public StringProperty titleProperty() { return title; }

    public String getDescription() { return description.get(); }
    public void setDescription(String value) { description.set(value == null ? "" : value); }
    public StringProperty descriptionProperty() { return description; }
}
