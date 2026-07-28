package com.sqlteacher.desktop.appearance;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;

public final class UiIcons {
    private UiIcons() {
    }

    public static Node create(UiIcon icon, double size) {
        SVGPath path = new SVGPath();
        path.setContent(icon.path());
        path.getStyleClass().add("ui-icon-shape");
        double scale = size / 24.0;
        path.getTransforms().add(new Scale(scale, scale));
        StackPane container = new StackPane(path);
        container.setAlignment(Pos.CENTER);
        container.setMinSize(size, size);
        container.setPrefSize(size, size);
        container.setMaxSize(size, size);
        container.getStyleClass().add("ui-icon");
        container.setMouseTransparent(true);
        return container;
    }

    public static void decorate(ButtonBase button, UiIcon icon, String accessibleText) {
        button.setGraphic(create(icon, 18));
        button.setAccessibleText(accessibleText);
        button.setTooltip(new Tooltip(accessibleText));
    }
}
