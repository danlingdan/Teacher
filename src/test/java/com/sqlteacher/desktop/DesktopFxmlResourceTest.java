package com.sqlteacher.desktop;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class DesktopFxmlResourceTest {

    @Test
    void shouldProvideWellFormedMainHomeAndAiAssistantFxml() throws Exception {
        assertWellFormed("/fxml/MainWindow.fxml");
        assertWellFormed("/fxml/home.fxml");
        assertWellFormed("/fxml/SqlPractice.fxml");
        assertWellFormed("/fxml/ai-assistant.fxml");
        assertWellFormed("/fxml/connection-settings.fxml");
        assertWellFormed("/fxml/settings.fxml");
        assertWellFormed("/fxml/sql-safety-settings.fxml");
        assertWellFormed("/fxml/appearance-settings.fxml");
        assertWellFormed("/fxml/data-maintenance.fxml");
        assertWellFormed("/fxml/student-exercise.fxml");
        assertWellFormed("/fxml/exercise-management.fxml");
        assertWellFormed("/fxml/exercise-progress.fxml");
        assertWellFormed("/fxml/knowledge-center.fxml");
        assertWellFormed("/fxml/cloud-center.fxml");
        assertWellFormed("/fxml/teaching-content.fxml");
        assertWellFormed("/fxml/login-gate.fxml");
        assertWellFormed("/fxml/course-map.fxml");
        assertWellFormed("/fxml/database-learning.fxml");
        assertWellFormed("/fxml/activity-workspace.fxml");
    }

    private static void assertWellFormed(String resourcePath) throws Exception {
        try (InputStream input = DesktopFxmlResourceTest.class.getResourceAsStream(resourcePath)) {
            assertNotNull(input, () -> "Missing FXML resource: " + resourcePath);
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input);
            assertNotNull(document.getDocumentElement(), () -> "Missing FXML root element: " + resourcePath);
        }
    }
}
