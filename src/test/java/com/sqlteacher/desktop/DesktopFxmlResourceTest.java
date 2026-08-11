package com.sqlteacher.desktop;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void homeShouldUseRuntimeVersionAndNonOverlappingQuickActionGrid() throws Exception {
        Document document = parse("/fxml/home.fxml");
        var headers = document.getElementsByTagName("PageHeader");
        assertEquals(1, headers.getLength());
        var header = headers.item(0).getAttributes();
        assertEquals("pageHeader", header.getNamedItem("fx:id").getNodeValue());
        assertFalse(header.getNamedItem("eyebrow") != null,
            "Home version must be supplied from runtime build metadata, not hard-coded in FXML");
        assertEquals(1, document.getElementsByTagName("GridPane").getLength());
        assertEquals(0, document.getElementsByTagName("TilePane").getLength());
    }

    @Test
    void studentExerciseShouldRevealAnswerWorkspaceOnlyAfterConfirmation() throws Exception {
        Document document = parse("/fxml/student-exercise.fxml");
        var selection = elementByFxId(document, "selectionStepPane");
        var answer = elementByFxId(document, "answerStepPane");
        var editor = elementByFxId(document, "sqlArea");
        var start = elementByFxId(document, "startButton");

        assertNotNull(selection);
        assertNotNull(answer);
        assertEquals("false", answer.getAttribute("visible"));
        assertEquals("false", answer.getAttribute("managed"));
        assertTrue(isDescendantOf(editor, answer), "SQL editor must belong only to the answer step");
        assertTrue(isDescendantOf(start, selection), "confirmation button must belong to the selection step");
        assertEquals(1, document.getElementsByTagName("SplitPane").getLength(),
            "The selection step must not use the former always-visible page split");
    }

    @Test
    void linearWorkflowsShouldHideLaterStageContentUntilItIsRelevant() throws Exception {
        assertInitiallyHidden("/fxml/ai-assistant.fxml", "resultStepPane");
        assertInitiallyHidden("/fxml/connection-settings.fxml", "connectionFormPane");
        assertInitiallyHidden("/fxml/exercise-management.fxml", "exerciseFormPane");
        assertInitiallyHidden("/fxml/database-learning.fxml", "modelReviewPane");
        assertInitiallyHidden("/fxml/database-learning.fxml", "dataReviewPane");
    }

    @Test
    void cloudCenterShouldBindTeacherOnlyControlsToTheirActualContainers() throws Exception {
        Document document = parse("/fxml/cloud-center.fxml");
        var memberEmail = elementByFxId(document, "memberEmailField");
        var assignmentCreation = elementByFxId(document, "assignmentCreationPane");
        var assignmentExercise = elementByFxId(document, "assignmentExerciseField");
        var assignmentAnalytics = elementByFxId(document, "assignmentAnalyticsPane");
        var assignmentAnalyticsLabel = elementByFxId(document, "assignmentAnalyticsLabel");

        assertNotNull(assignmentCreation);
        assertNotNull(assignmentAnalytics);
        assertFalse(isDescendantOf(memberEmail, assignmentCreation),
            "member management must not be mistaken for assignment creation");
        assertTrue(isDescendantOf(assignmentExercise, assignmentCreation),
            "assignment fields must belong to the teacher-only creation pane");
        assertTrue(isDescendantOf(assignmentAnalyticsLabel, assignmentAnalytics),
            "assignment analytics must belong to a teacher-only pane");
    }

    @Test
    void knowledgeCenterShouldKeepTheBeginnerFlowOutsideCollapsedAdvancedSettings() throws Exception {
        Document document = parse("/fxml/knowledge-center.fxml");
        var workflow = elementByFxId(document, "workflowSteps");
        var advanced = elementByFxId(document, "advancedPane");
        var query = elementByFxId(document, "queryField");
        var objective = elementByFxId(document, "objectiveField");
        var authoring = elementByFxId(document, "authoringPane");
        var importButton = elementByFxId(document, "importButton");

        assertNotNull(workflow);
        assertNotNull(advanced);
        assertEquals("false", advanced.getAttribute("expanded"));
        assertFalse(isDescendantOf(query, advanced),
            "asking a basic question must not require opening advanced settings");
        assertTrue(isDescendantOf(objective, advanced),
            "technical objective identifiers belong in advanced settings");
        assertTrue(isDescendantOf(authoring, advanced),
            "teacher material management belongs in advanced settings");
        assertTrue(isDescendantOf(importButton, authoring),
            "batch import must remain available inside teacher management");
    }

    private static void assertWellFormed(String resourcePath) throws Exception {
        Document document = parse(resourcePath);
        assertNotNull(document.getDocumentElement(), () -> "Missing FXML root element: " + resourcePath);
    }

    private static Document parse(String resourcePath) throws Exception {
        try (InputStream input = DesktopFxmlResourceTest.class.getResourceAsStream(resourcePath)) {
            assertNotNull(input, () -> "Missing FXML resource: " + resourcePath);
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input);
        }
    }

    private static org.w3c.dom.Element elementByFxId(Document document, String fxId) {
        var elements = document.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            var element = (org.w3c.dom.Element) elements.item(index);
            if (fxId.equals(element.getAttribute("fx:id"))) return element;
        }
        return null;
    }

    private static void assertInitiallyHidden(String resource, String fxId) throws Exception {
        var element = elementByFxId(parse(resource), fxId);
        assertNotNull(element, () -> "Missing staged workflow node " + fxId + " in " + resource);
        assertEquals("false", element.getAttribute("visible"));
        assertEquals("false", element.getAttribute("managed"));
    }

    private static boolean isDescendantOf(org.w3c.dom.Node child, org.w3c.dom.Node ancestor) {
        for (org.w3c.dom.Node current = child; current != null; current = current.getParentNode()) {
            if (current == ancestor) return true;
        }
        return false;
    }
}
