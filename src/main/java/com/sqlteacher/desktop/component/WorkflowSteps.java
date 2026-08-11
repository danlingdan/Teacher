package com.sqlteacher.desktop.component;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import java.util.Arrays;
import java.util.List;

/** Compact, reusable progress indicator for UI workflows with a real operation order. */
public final class WorkflowSteps extends HBox {
    private final StringProperty steps = new SimpleStringProperty(this, "steps", "");
    private final IntegerProperty activeStep = new SimpleIntegerProperty(this, "activeStep", 1);

    public WorkflowSteps() {
        getStyleClass().add("workflow-steps");
        setAlignment(Pos.CENTER_LEFT);
        steps.addListener((ignored, oldValue, newValue) -> rebuild());
        activeStep.addListener((ignored, oldValue, newValue) -> rebuild());
    }

    public String getSteps() {
        return steps.get();
    }

    public void setSteps(String value) {
        steps.set(value == null ? "" : value);
    }

    public StringProperty stepsProperty() {
        return steps;
    }

    public int getActiveStep() {
        return activeStep.get();
    }

    public void setActiveStep(int value) {
        activeStep.set(Math.max(1, value));
    }

    public IntegerProperty activeStepProperty() {
        return activeStep;
    }

    private void rebuild() {
        getChildren().clear();
        List<String> labels = Arrays.stream(getSteps().split("\\|"))
            .map(String::strip)
            .filter(value -> !value.isBlank())
            .toList();
        int active = Math.min(getActiveStep(), Math.max(1, labels.size()));
        for (int index = 0; index < labels.size(); index++) {
            if (index > 0) {
                Region connector = new Region();
                connector.getStyleClass().add("workflow-step-connector");
                connector.setPrefWidth(56);
                getChildren().add(connector);
            }
            Label step = new Label((index + 1) + "  " + labels.get(index));
            step.getStyleClass().add("workflow-step");
            if (index + 1 < active) step.getStyleClass().add("completed");
            else if (index + 1 == active) step.getStyleClass().add("active");
            getChildren().add(step);
        }
    }
}
