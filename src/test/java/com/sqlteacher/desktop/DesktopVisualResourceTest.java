package com.sqlteacher.desktop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

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
        String pages = resource("/css/components/pages.css");
        String responsive = resource("/css/layouts/responsive.css");

        assertTrue(base.contains(".app-sidebar"));
        assertTrue(base.contains(".font-modern"));
        assertTrue(light.contains(".theme-light") || light.contains("SQLTeacher v1.5 light theme"));
        assertTrue(light.contains(".theme-light .app-sidebar .nav-button:hover .ui-icon-shape"));
        assertTrue(tokens.contains("-st-bg-canvas"));
        assertTrue(tokens.contains("-st-info-surface"));
        assertTrue(tokens.contains("-color-accent-emphasis"));
        assertTrue(components.contains(".sql-workbench-split"));
        assertTrue(pages.contains(".table-schema-page"));
        assertTrue(pages.contains(".exercise-form .text-area"));
        assertTrue(pages.contains(".teaching-tabs .tab-content-area"));
        assertTrue(pages.contains(".cloud-status-banner .label"));
        assertTrue(pages.contains(".text-input:disabled"));
        assertTrue(pages.contains("-color-cell-fg: -st-text-primary"));
        assertTrue(pages.contains(".authenticated-content:disabled"));
        assertTrue(pages.contains(".label:disabled"));
        assertTrue(pages.contains("-color-button-fg: -st-text-disabled"));
        assertTrue(pages.contains(".identity-pill"));
        assertTrue(pages.contains(".model-selector .list-cell"));
        assertTrue(pages.contains(".risk-dialog-sql"));
        assertFalse(pages.contains("#141c30"));
        assertFalse(pages.contains("#172035"));
        assertFalse(pages.contains("#212c44"));
        assertTrue(responsive.contains(".layout-compact"));
    }

    @Test
    void lightThemeTextTokensShouldMeetNormalTextContrast() throws IOException {
        String tokens = resource("/css/foundation/tokens.css");
        String light = tokens.substring(tokens.indexOf(".theme-light"));
        String surface = token(light, "-st-bg-surface");

        assertTrue(contrast(token(light, "-st-text-primary"), surface) >= 4.5);
        assertTrue(contrast(token(light, "-st-text-secondary"), surface) >= 4.5);
        assertTrue(contrast(token(light, "-st-text-muted"), surface) >= 4.5);
        assertTrue(contrast(token(light, "-st-text-disabled"), surface) >= 4.5);
    }

    @Test
    void shellAndSqlWorkbenchShouldUseAdaptiveStructures() throws IOException {
        String shell = resource("/fxml/MainWindow.fxml");
        String home = resource("/fxml/home.fxml");
        String sql = resource("/fxml/SqlPractice.fxml");
        String cloud = resource("/fxml/cloud-center.fxml");
        String assistant = resource("/fxml/ai-assistant.fxml");
        String exercise = resource("/fxml/student-exercise.fxml");
        String teaching = resource("/fxml/teaching-content.fxml");

        assertTrue(shell.contains("fx:id=\"mainWindowRoot\""));
        assertTrue(shell.contains("fx:id=\"appSidebar\""));
        assertTrue(home.contains("TilePane"));
        assertTrue(sql.contains("fx:id=\"workbenchSplit\""));
        assertFalse(sql.contains("sql-practice-scroll"));
        assertTrue(cloud.contains("fx:id=\"signedOutPrompt\""));
        assertTrue(cloud.contains("styleClass=\"authenticated-content\""));
        assertTrue(cloud.contains("fx:id=\"assignmentDueDatePicker\""));
        assertFalse(cloud.contains("assignmentDueAtField"));
        assertFalse(cloud.contains("aiEndpointField"));
        assertFalse(cloud.contains("网络 AI"));
        assertTrue(assistant.contains("fx:id=\"providerSelector\""));
        assertTrue(assistant.contains("fx:id=\"localProviderPane\""));
        assertTrue(assistant.contains("fx:id=\"networkProviderPane\""));
        assertTrue(assistant.contains("fx:id=\"networkApiKeyField\""));
        assertTrue(exercise.contains("fx:id=\"schemaLabel\""));
        assertTrue(exercise.contains("prefRowCount=\"9\""));
        assertTrue(teaching.contains("fx:id=\"assignmentClassroomCombo\""));
        assertTrue(teaching.contains("fx:id=\"learningClassroomCombo\""));
        assertTrue(teaching.contains("fx:id=\"learningAssignmentCombo\""));
        assertTrue(teaching.contains("fx:id=\"learningStudentCombo\""));
        assertFalse(teaching.contains("assignmentDueAtField"));
    }

    @Test
    void homeShouldUseAccessibleVectorIconPlaceholdersInsteadOfEmoji() throws IOException {
        String home = resource("/fxml/home.fxml");
        assertTrue(home.contains("accessibleText=\"%home.7\""), "accessible labels should be localized keys");
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

    private static String token(String css, String name) {
        var matcher = Pattern.compile(Pattern.quote(name) + "\\s*:\\s*(#[0-9a-fA-F]{6})").matcher(css);
        assertTrue(matcher.find(), () -> "Missing hexadecimal token: " + name);
        return matcher.group(1);
    }

    private static double contrast(String first, String second) {
        double light = luminance(first);
        double dark = luminance(second);
        return (Math.max(light, dark) + 0.05) / (Math.min(light, dark) + 0.05);
    }

    private static double luminance(String color) {
        double red = channel(color.substring(1, 3));
        double green = channel(color.substring(3, 5));
        double blue = channel(color.substring(5, 7));
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
    }

    private static double channel(String hex) {
        double value = Integer.parseInt(hex, 16) / 255.0;
        return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
