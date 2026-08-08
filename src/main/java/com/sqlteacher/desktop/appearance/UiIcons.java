package com.sqlteacher.desktop.appearance;

import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Tooltip;
import org.kordamp.ikonli.javafx.FontIcon;

public final class UiIcons {
    private UiIcons() {
    }

    public static Node create(UiIcon icon, double size) {
        FontIcon fontIcon = new FontIcon(icon.literal());
        fontIcon.setIconSize((int) Math.round(size));
        fontIcon.getStyleClass().addAll("ui-icon", "ui-icon-shape");
        fontIcon.setMouseTransparent(true);
        return fontIcon;
    }

    public static void decorate(ButtonBase button, UiIcon icon, String accessibleText) {
        button.setGraphic(create(icon, 18));
        button.setAccessibleText(accessibleText);
        button.setTooltip(new Tooltip(accessibleText));
    }
}
