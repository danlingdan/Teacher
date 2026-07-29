package com.sqlteacher.desktop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopVisualResourceTest {
    @Test
    void shouldShipBaseAndLightThemeResources() throws IOException {
        String base = resource("/css/app.css");
        String light = resource("/css/theme-light.css");
        String tokens = resource("/css/foundation/tokens.css");
        String components = resource("/css/components/core.css");
        String responsive = resource("/css/layouts/responsive.css");

        assertTrue(base.contains(".app-sidebar"));
        assertTrue(base.contains(".font-modern"));
        assertTrue(light.contains(".theme-light") || light.contains("SQLTeacher v1.5 light theme"));
        assertTrue(light.contains(".panel-card"));
        assertTrue(tokens.contains("-st-bg-canvas"));
        assertTrue(tokens.contains("-color-accent-emphasis"));
        assertTrue(components.contains(".sql-workbench-split"));
        assertTrue(responsive.contains(".layout-compact"));
    }

    @Test
    void shellAndSqlWorkbenchShouldUseAdaptiveStructures() throws IOException {
        String shell = resource("/fxml/MainWindow.fxml");
        String home = resource("/fxml/home.fxml");
        String sql = resource("/fxml/SqlPractice.fxml");

        assertTrue(shell.contains("fx:id=\"mainWindowRoot\""));
        assertTrue(shell.contains("fx:id=\"appSidebar\""));
        assertTrue(home.contains("TilePane"));
        assertTrue(sql.contains("fx:id=\"workbenchSplit\""));
        assertFalse(sql.contains("sql-practice-scroll"));
    }

    @Test
    void homeShouldUseAccessibleVectorIconPlaceholdersInsteadOfEmoji() throws IOException {
        String home = resource("/fxml/home.fxml");
        assertTrue(home.contains("accessibleText=\"打开 SQL 练习\""));
        assertFalse(home.contains("🔧"));
        assertFalse(home.contains("✨"));
        assertFalse(home.contains("📊"));
    }

    private static String resource(String path) throws IOException {
        try (var input = DesktopVisualResourceTest.class.getResourceAsStream(path)) {
            assertNotNull(input, () -> "Missing resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
