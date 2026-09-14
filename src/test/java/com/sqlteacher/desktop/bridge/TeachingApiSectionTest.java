package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.application.collaboration.UserRole;
import com.sqlteacher.application.exercise.ExerciseManagementService;
import com.sqlteacher.application.exercise.ExerciseProgressOverview;
import com.sqlteacher.application.exercise.ExerciseProgressService;
import com.sqlteacher.application.learning.InterventionService;
import com.sqlteacher.application.learning.InterventionStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.fake;
import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.hostWithBeans;
import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.4.0 REF-9: every teaching method starts from the deterministic teacher/admin role guard;
 * behind it the section only maps parameters onto the exercise management, progress, and
 * intervention ports. Role checks stay in Java and run before any bean is resolved for work.
 */
class TeachingApiSectionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private static ApiSectionTestSupport.FakeCloudSessions teacherSessions() {
        var sessions = new ApiSectionTestSupport.FakeCloudSessions();
        sessions.signIn(session("t-1", "教师账号", UserRole.TEACHER));
        return sessions;
    }

    @Test
    void teachingWorkspaceRejectsNonTeacherRoles() {
        try (var host = hostWithBeans(new ApiSectionTestSupport.FakeCloudSessions())) {
            TeachingApiSection section = new TeachingApiSection(host);

            SecurityException error = assertThrows(SecurityException.class,
                () -> section.handle("teaching.workspace", mapper.createObjectNode(), () -> false, ignored -> { }));
            assertEquals("Teaching workspace requires teacher or administrator role", error.getMessage());
        }
    }

    @Test
    void teachingWorkspaceAssemblesTheTeacherOverview() throws Exception {
        ExerciseManagementService management = fake(ExerciseManagementService.class, Map.of(
            "listExercises", args -> List.of(),
            "listDatasets", args -> List.of()));
        ExerciseProgressService progress = fake(ExerciseProgressService.class, Map.of(
            "overview", args -> new ExerciseProgressOverview(3, 10, 8, 4, 0.5, Duration.ofMinutes(2), 1, 2),
            "listExerciseProgress", args -> List.of()));
        try (var host = hostWithBeans(teacherSessions(), management, progress)) {
            TeachingApiSection section = new TeachingApiSection(host);

            JsonNode result = section.handle("teaching.workspace", mapper.createObjectNode(), () -> false, ignored -> { });

            assertEquals("TEACHER", result.path("role").asText());
            assertTrue(result.path("canPublish").asBoolean());
            assertEquals("java-and-cloud-server", result.path("authority").asText());
            assertTrue(result.path("exercises").isArray());
            assertTrue(result.path("datasets").isArray());
            assertEquals(3, result.path("progressOverview").path("sessions").asInt());
            assertEquals(2, result.path("progressOverview").path("completedExercises").asInt());
            assertTrue(result.path("progressItems").isArray());
        }
    }

    @Test
    void teachingExerciseToggleRejectsNonPositiveExpectedVersion() {
        try (var host = hostWithBeans(teacherSessions())) {
            TeachingApiSection section = new TeachingApiSection(host);

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> section.handle("teaching.exercise.toggle", mapper.createObjectNode()
                    .put("exerciseId", "ex-1")
                    .put("enabled", true)
                    .put("expectedVersion", 0), () -> false, ignored -> { }));
            assertEquals("expectedVersion must be positive", error.getMessage());
        }
    }

    @Test
    void teachingExerciseExportRejectsEmptySelections() {
        ObjectNode params = mapper.createObjectNode();
        params.putArray("exerciseIds");
        try (var host = hostWithBeans(teacherSessions())) {
            TeachingApiSection section = new TeachingApiSection(host);

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> section.handle("teaching.exercise.export", params, () -> false, ignored -> { }));
            assertEquals("At least one exercise must be selected", error.getMessage());
        }
    }

    @Test
    void teachingExerciseExportReturnsThePackageText() throws Exception {
        List<List<String>> exported = new ArrayList<>();
        ExerciseManagementService management = fake(ExerciseManagementService.class, Map.of(
            "exportPackage", args -> {
                @SuppressWarnings("unchecked")
                List<String> ids = (List<String>) args[0];
                exported.add(ids);
                return "PACKAGE-1";
            }));
        ObjectNode params = mapper.createObjectNode();
        params.putArray("exerciseIds").add("ex-1").add("ex-2");
        try (var host = hostWithBeans(teacherSessions(), management)) {
            TeachingApiSection section = new TeachingApiSection(host);

            JsonNode result = section.handle("teaching.exercise.export", params, () -> false, ignored -> { });

            assertEquals("PACKAGE-1", result.path("text").asText());
        }
        assertEquals(List.of(List.of("ex-1", "ex-2")), exported);
    }

    @Test
    void teachingInterventionUpdateMapsTheStatusEnum() throws Exception {
        List<Object[]> updates = new ArrayList<>();
        InterventionService interventions = fake(InterventionService.class, Map.of(
            "updateStatus", args -> {
                updates.add(args);
                return null;
            }));
        try (var host = hostWithBeans(teacherSessions(), interventions)) {
            TeachingApiSection section = new TeachingApiSection(host);

            JsonNode result = section.handle("teaching.intervention.update", mapper.createObjectNode()
                .put("candidateId", "cand-1")
                .put("status", "ACKNOWLEDGED"), () -> false, ignored -> { });

            assertTrue(result.path("updated").asBoolean());
            assertEquals("cand-1", result.path("candidateId").asText());
            assertEquals("ACKNOWLEDGED", result.path("status").asText());

            assertThrows(IllegalArgumentException.class,
                () -> section.handle("teaching.intervention.update", mapper.createObjectNode()
                    .put("candidateId", "cand-1")
                    .put("status", "NOT_A_STATUS"), () -> false, ignored -> { }));
        }
        assertEquals(1, updates.size());
        assertEquals("cand-1", updates.get(0)[0]);
        assertEquals(InterventionStatus.ACKNOWLEDGED, updates.get(0)[1]);
    }
}
