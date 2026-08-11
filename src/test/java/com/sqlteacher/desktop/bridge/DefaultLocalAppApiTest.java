package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultLocalAppApiTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @TempDir
    Path tempDirectory;

    @Test
    void shouldExposeHealthWithoutInitializingSpringCore() throws Exception {
        try (var api = new DefaultLocalAppApi(mapper)) {
            var result = api.invoke("system.health", mapper.createObjectNode(), () -> false, ignored -> { });

            assertEquals("ready", result.path("status").asText());
            assertEquals(DefaultLocalAppApi.CONTRACT_VERSION, result.path("contractVersion").asText());
            assertFalse(result.path("coreInitialized").asBoolean());
        }
    }

    @Test
    @ResourceLock("sqlteacher.data.dir")
    void shouldExposeJavaOwnedGuestSessionForRoleGuards() throws Exception {
        System.setProperty("sqlteacher.data.dir", tempDirectory.resolve("guest-data").toString());
        try (var api = new DefaultLocalAppApi(mapper)) {
            var result = api.invoke("session.current", mapper.createObjectNode(), () -> false, ignored -> { });

            assertEquals("STUDENT", result.path("role").asText());
            assertEquals("guest", result.path("subjectId").asText());
            assertFalse(result.path("authenticated").asBoolean());
            assertTrue(result.path("permissions").isArray());
            var logout = api.invoke("account.logout", mapper.createObjectNode(), () -> false, ignored -> { });
            assertEquals("guest", logout.path("subjectId").asText());
            assertTrue(logout.path("remoteLogoutSucceeded").asBoolean());
        } finally {
            System.clearProperty("sqlteacher.data.dir");
        }
    }

    @Test
    @ResourceLock("sqlteacher.data.dir")
    void shouldExposeAlphaThreeToFiveWorkspacesThroughRealJavaServices() throws Exception {
        Path data = tempDirectory.resolve("data");
        Path vault = tempDirectory.resolve("vault");
        Files.createDirectories(vault.resolve("Data Structures"));
        Files.writeString(vault.resolve("Data Structures").resolve("Tree.md"), """
            # Binary Tree

            Read [[Traversal]] and inspect ![[tree.png]].
            """);
        Files.writeString(vault.resolve("Traversal.md"), "# Traversal\n\nPreorder is deterministic.");
        Files.write(vault.resolve("tree.png"), new byte[]{1, 2, 3});
        System.setProperty("sqlteacher.data.dir", data.toString());
        try (var api = new DefaultLocalAppApi(mapper)) {
            var courses = api.invoke("course.workspace", mapper.createObjectNode(), () -> false, ignored -> { });
            assertTrue(courses.path("courses").isArray());
            var quiz = java.util.stream.StreamSupport.stream(courses.path("courses").spliterator(), false)
                .flatMap(course -> java.util.stream.StreamSupport.stream(course.path("sections").spliterator(), false))
                .flatMap(section -> java.util.stream.StreamSupport.stream(section.path("activities").spliterator(), false))
                .filter(activity -> activity.path("enabled").asBoolean() && "QUIZ".equals(activity.path("type").asText()))
                .findFirst().orElseThrow();
            var definition = api.invoke("activity.definition",
                mapper.createObjectNode().put("activityId", quiz.path("id").asText()), () -> false, ignored -> { });
            assertEquals("QUIZ", definition.path("type").asText());
            var selections = mapper.createObjectNode();
            definition.path("specification").path("questions").forEach(question -> {
                assertFalse(question.has("correctOptionId"));
                assertFalse(question.has("explanation"));
                selections.put(question.path("id").asText(), question.path("options").get(0).path("id").asText());
            });
            var activityParams = mapper.createObjectNode().put("activityId", quiz.path("id").asText()).put("type", "QUIZ");
            activityParams.putObject("artifact").set("selectedOptionIds", selections);
            var activitySubmission = api.invoke("activity.submit", activityParams, () -> false, ignored -> { });
            assertTrue(activitySubmission.path("evaluation").path("criteria").isArray());
            assertFalse(activitySubmission.path("evaluationId").asText().isBlank());

            var previewParams = mapper.createObjectNode();
            previewParams.put("root", vault.toString());
            previewParams.put("courseTitle", "Algorithms");
            previewParams.put("sectionDepth", 1);
            previewParams.put("includeAttachments", true);
            var preview = api.invoke("knowledge.import.preview", previewParams, () -> false, ignored -> { });
            assertEquals(2, preview.path("markdownFiles").asInt());
            assertEquals(0, preview.path("missingAttachments").asInt());

            var executeParams = mapper.createObjectNode().put("previewToken", preview.path("token").asText());
            var report = api.invoke("knowledge.import.execute", executeParams, () -> false, ignored -> { });
            assertEquals(2, report.path("imported").asInt());
            assertEquals(0, report.path("failed").asInt());

            var unchangedPreview = api.invoke("knowledge.import.preview", previewParams, () -> false, ignored -> { });
            assertEquals(2, unchangedPreview.path("unchangedFiles").asInt());
            Files.writeString(vault.resolve("Traversal.md"), "# Traversal\n\nChanged after first import.");
            var changedPreview = api.invoke("knowledge.import.preview", previewParams, () -> false, ignored -> { });
            assertEquals(1, changedPreview.path("changedFiles").asInt());
            Files.writeString(vault.resolve("Traversal.md"), "# Traversal\n\nChanged after preview token.");
            var staleExecute = mapper.createObjectNode().put("previewToken", changedPreview.path("token").asText());
            var staleReport = api.invoke("knowledge.import.execute", staleExecute, () -> false, ignored -> { });
            assertEquals(1, staleReport.path("failed").asInt());

            var catalog = api.invoke("practice.catalog", mapper.createObjectNode(), () -> false, ignored -> { });
            assertTrue(catalog.path("items").isArray());
            if (!catalog.path("items").isEmpty()) {
                var startParams = mapper.createObjectNode().put("exerciseId", catalog.path("items").get(0).path("id").asText());
                var practiceSession = api.invoke("practice.start", startParams, () -> false, ignored -> { });
                var sessionParams = mapper.createObjectNode().put("sessionId", practiceSession.path("id").asText());
                assertEquals(1, api.invoke("practice.hint", sessionParams, () -> false, ignored -> { }).path("level").asInt());
                assertEquals(practiceSession.path("id").asText(),
                    api.invoke("practice.reset", sessionParams, () -> false, ignored -> { }).path("id").asText());
                assertTrue(api.invoke("practice.close", sessionParams, () -> false, ignored -> { }).path("closed").asBoolean());
            }
            var connections = api.invoke("data.connections", mapper.createObjectNode(), () -> false, ignored -> { });
            String connectionId = java.util.stream.StreamSupport.stream(connections.path("items").spliterator(), false)
                .filter(item -> !item.path("readOnly").asBoolean()).findFirst().orElse(connections.path("items").get(0))
                .path("id").asText();
            var analyzeParams = mapper.createObjectNode().put("connectionId", connectionId)
                .put("sql", "SELECT 1; DROP TABLE learning_events");
            var analysis = api.invoke("sql.analyze", analyzeParams, () -> false, ignored -> { });
            assertFalse(analysis.path("executable").asBoolean());
            assertEquals("java", analysis.path("enforcedBy").asText());

            var selectParams = mapper.createObjectNode().put("connectionId", connectionId)
                .put("sql", "SELECT name FROM sqlite_master ORDER BY name").put("maxRows", 2).put("pageSize", 1);
            var firstPage = api.invoke("sql.execute", selectParams, () -> false, ignored -> { });
            assertTrue(firstPage.path("auditRecorded").asBoolean());
            assertTrue(firstPage.path("rows").size() <= 1);

            var riskyParams = mapper.createObjectNode().put("connectionId", connectionId)
                .put("sql", "DELETE FROM learning_events");
            var risky = api.invoke("sql.analyze", riskyParams, () -> false, ignored -> { });
            if (risky.path("confirmationRequired").asBoolean()) {
                var swapped = mapper.createObjectNode().put("connectionId", connectionId)
                    .put("sql", "DELETE FROM learning_event_attributes")
                    .put("confirmationToken", risky.path("confirmationToken").asText());
                assertThrows(IllegalArgumentException.class,
                    () -> api.invoke("sql.execute", swapped, () -> false, ignored -> { }));
            }

            var cloud = api.invoke("cloud.workspace", mapper.createObjectNode(), () -> false, ignored -> { });
            assertFalse(cloud.path("signedIn").asBoolean());
            assertEquals("SIGNED_OUT", cloud.path("state").asText());

            var settings = api.invoke("settings.workspace", mapper.createObjectNode(), () -> false, ignored -> { });
            assertFalse(settings.path("secretsExposed").asBoolean());
            assertTrue(settings.path("runnerCapabilities").isArray());
            var update = mapper.createObjectNode().put("developerMode", true).put("language", "zh")
                .put("reducedMotion", true).put("supportLogging", true)
                .put("theme", "dark").put("font", "classic").put("density", "compact");
            var saved = api.invoke("settings.update", update, () -> false, ignored -> { });
            assertTrue(saved.path("saved").asBoolean());
            assertTrue(saved.path("developerMode").asBoolean());
            assertEquals("dark", saved.path("general").path("theme").asText());
            assertEquals("classic", saved.path("general").path("font").asText());
            assertEquals("compact", saved.path("general").path("density").asText());
            assertTrue(saved.path("general").path("supportLoggingExpiresAt").asLong() > System.currentTimeMillis());
            api.invoke("settings.update", mapper.createObjectNode().put("developerMode", false),
                () -> false, ignored -> { });

            assertThrows(SecurityException.class,
                () -> api.invoke("teaching.workspace", mapper.createObjectNode(), () -> false, ignored -> { }));
        } finally {
            System.clearProperty("sqlteacher.data.dir");
        }
    }
}
