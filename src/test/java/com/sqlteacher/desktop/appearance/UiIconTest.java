package com.sqlteacher.desktop.appearance;

import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.javafx.FontIcon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UiIconTest {
    @Test
    void everySemanticIconShouldResolveFromTheBundledPack() {
        for (UiIcon icon : UiIcon.values()) {
            FontIcon resolved = new FontIcon(icon.literal());
            assertNotNull(resolved.getIconCode(), () -> "Unresolved icon literal: " + icon.literal());
            assertEquals(icon.literal(), resolved.getIconLiteral());
        }
    }
}
