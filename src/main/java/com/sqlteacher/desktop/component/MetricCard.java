package com.sqlteacher.desktop.component;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** Compact metric primitive for dashboards and learning evidence summaries. */
public final class MetricCard extends VBox {
    private final StringProperty label = new SimpleStringProperty(this, "label", "");
    private final StringProperty value = new SimpleStringProperty(this, "value", "—");
    private final StringProperty detail = new SimpleStringProperty(this, "detail", "");

    public MetricCard() {
        setSpacing(4);
        getStyleClass().add("metric-card");
        Label labelNode = new Label();
        labelNode.getStyleClass().add("metric-label");
        labelNode.textProperty().bind(label);
        Label valueNode = new Label();
        valueNode.getStyleClass().add("metric-value");
        valueNode.textProperty().bind(value);
        Label detailNode = new Label();
        detailNode.getStyleClass().add("metric-detail");
        detailNode.textProperty().bind(detail);
        detailNode.visibleProperty().bind(detail.isNotEmpty());
        detailNode.managedProperty().bind(detailNode.visibleProperty());
        getChildren().addAll(labelNode, valueNode, detailNode);
    }

    public String getLabel() { return label.get(); }
    public void setLabel(String text) { label.set(text == null ? "" : text); }
    public StringProperty labelProperty() { return label; }
    public String getValue() { return value.get(); }
    public void setValue(String text) { value.set(text == null ? "" : text); }
    public StringProperty valueProperty() { return value; }
    public String getDetail() { return detail.get(); }
    public void setDetail(String text) { detail.set(text == null ? "" : text); }
    public StringProperty detailProperty() { return detail; }
}
