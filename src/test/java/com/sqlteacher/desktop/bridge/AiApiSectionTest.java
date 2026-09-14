package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.application.ai.AiContextCategory;
import com.sqlteacher.application.ai.AiContextPreview;
import com.sqlteacher.application.ai.AiTaskType;
import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.connection.DatabaseConnectionProfile;
import com.sqlteacher.application.connection.DatabaseDialect;
import com.sqlteacher.application.connection.SqliteConnectionTarget;
import com.sqlteacher.application.exercise.ExerciseExplanation;
import com.sqlteacher.application.exercise.ExerciseExplainRequest;
import com.sqlteacher.application.exercise.ExerciseTextDraftingService;
import com.sqlteacher.application.knowledge.CourseKnowledgeSearchFilter;
import com.sqlteacher.application.knowledge.GroundedKnowledgeAnswer;
import com.sqlteacher.application.knowledge.GroundedKnowledgeExplanationService;
import com.sqlteacher.application.nl2sql.Nl2SqlRequest;
import com.sqlteacher.application.nl2sql.Nl2SqlSafetyService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.fake;
import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.hostWithBeans;
import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.hostWithoutCore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * v3.4.0 REF-9: the AI section only drafts: grounded answers stream in bounded deltas, NL2SQL
 * requests are mapped from validated connection profiles, and explanation requests never carry
 * expected answers. All enforcement stays in the Java services behind the ports.
 */
class AiApiSectionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private static DatabaseConnectionProfile profile(boolean enabled) {
        return new DatabaseConnectionProfile("demo", "演示库",
            new SqliteConnectionTarget(Path.of("demo.db")), false, enabled, true);
    }

    @Test
    void aiKnowledgeAskRejectsBlankQuestionWithoutTheCore() {
        AiApiSection section = new AiApiSection(hostWithoutCore());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> section.handle("ai.knowledge.ask", mapper.createObjectNode()
                .put("question", "   "), () -> false, ignored -> { }));
        assertEquals("question must contain at most 2000 characters", error.getMessage());
    }

    @Test
    void aiKnowledgeAskStreamsTheGroundedAnswerInBoundedDeltas() throws Exception {
        List<Object[]> asks = new ArrayList<>();
        GroundedKnowledgeExplanationService explanation = fake(GroundedKnowledgeExplanationService.class, Map.of(
            "explain", args -> {
                asks.add(args);
                return new GroundedKnowledgeAnswer(false, "x".repeat(500), "test-model", List.of(), "");
            }));
        List<LocalAppEvent> events = new ArrayList<>();
        try (var host = hostWithBeans(explanation)) {
            AiApiSection section = new AiApiSection(host);

            JsonNode result = section.handle("ai.knowledge.ask", mapper.createObjectNode()
                .put("question", "什么是索引"), () -> false, events::add);

            assertFalse(result.path("aiGenerated").asBoolean());
            assertEquals("test-model", result.path("model").asText());
            assertEquals("x".repeat(500), result.path("answer").asText());
        }
        assertEquals("什么是索引", asks.get(0)[0]);
        assertEquals(CourseKnowledgeSearchFilter.allLocal(), asks.get(0)[1]);

        // 500 字符按 240 上限切片：3 个增量事件，拼接后与答案一致。
        assertEquals(3, events.size());
        StringBuilder streamed = new StringBuilder();
        for (LocalAppEvent event : events) {
            assertEquals("ai.delta", event.type());
            streamed.append(event.payload().path("delta").asText());
        }
        assertEquals(List.of(240, 240, 20),
            events.stream().map(event -> event.payload().path("delta").asText().length()).toList());
        assertEquals("x".repeat(500), streamed.toString());
    }

    @Test
    void aiSqlPreviewRejectsMissingConnections() {
        ConnectionManagementService connections = fake(ConnectionManagementService.class,
            Map.of("findProfile", args -> Optional.empty()));
        try (var host = hostWithBeans(connections)) {
            AiApiSection section = new AiApiSection(host);

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> section.handle("ai.sql.preview", mapper.createObjectNode()
                    .put("connectionId", "missing")
                    .put("question", "查询所有学生"), () -> false, ignored -> { }));
            assertEquals("Database connection was not found", error.getMessage());
        }
    }

    @Test
    void aiSqlPreviewRejectsDisabledConnections() {
        ConnectionManagementService connections = fake(ConnectionManagementService.class,
            Map.of("findProfile", args -> Optional.of(profile(false))));
        try (var host = hostWithBeans(connections)) {
            AiApiSection section = new AiApiSection(host);

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> section.handle("ai.sql.preview", mapper.createObjectNode()
                    .put("connectionId", "demo")
                    .put("question", "查询所有学生"), () -> false, ignored -> { }));
            assertEquals("Database connection is disabled", error.getMessage());
        }
    }

    @Test
    void aiSqlPreviewMapsTheValidatedProfileIntoTheSafetyRequest() throws Exception {
        List<Nl2SqlRequest> requests = new ArrayList<>();
        ConnectionManagementService connections = fake(ConnectionManagementService.class,
            Map.of("findProfile", args -> Optional.of(profile(true))));
        Nl2SqlSafetyService safety = fake(Nl2SqlSafetyService.class, Map.of(
            "preview", args -> {
                requests.add((Nl2SqlRequest) args[0]);
                return new AiContextPreview(AiTaskType.NL2SQL,
                    Set.of(AiContextCategory.USER_REQUEST, AiContextCategory.DATABASE_SCHEMA),
                    List.of("demo"), 120, List.of());
            }));
        try (var host = hostWithBeans(connections, safety)) {
            AiApiSection section = new AiApiSection(host);

            JsonNode result = section.handle("ai.sql.preview", mapper.createObjectNode()
                .put("connectionId", "demo")
                .put("question", "查询所有学生"), () -> false, ignored -> { });

            assertEquals("NL2SQL", result.path("taskType").asText());
            assertEquals(120, result.path("characterCount").asInt());
        }
        assertEquals(1, requests.size());
        assertEquals("查询所有学生", requests.get(0).naturalLanguage());
        assertEquals("demo", requests.get(0).connectionId());
        assertEquals(DatabaseDialect.SQLITE, requests.get(0).dialect());
    }

    @Test
    void aiExerciseExplainMapsTheDraftRequestWithoutExpectedAnswers() throws Exception {
        List<ExerciseExplainRequest> requests = new ArrayList<>();
        ExerciseTextDraftingService drafting = fake(ExerciseTextDraftingService.class, Map.of(
            "explainFailure", args -> {
                requests.add((ExerciseExplainRequest) args[0]);
                return new ExerciseExplanation("JOIN 顺序有误", "test-model");
            }));
        ObjectNode params = mapper.createObjectNode()
            .put("title", "练习一")
            .put("description", "找出所有学生")
            .put("knowledgePoint", "JOIN")
            .put("answer", "SELECT * FROM a");
        params.putArray("feedback").add("行数不一致");
        try (var host = hostWithBeans(drafting)) {
            AiApiSection section = new AiApiSection(host);

            JsonNode result = section.handle("ai.exercise.explain", params, () -> false, ignored -> { });

            assertEquals("JOIN 顺序有误", result.path("explanation").asText());
            assertEquals("test-model", result.path("model").asText());
        }
        assertEquals(1, requests.size());
        ExerciseExplainRequest request = requests.get(0);
        assertEquals("练习一", request.title());
        assertEquals("SELECT * FROM a", request.studentSql());
        assertEquals("QUERY", request.exerciseType());
        assertEquals(List.of("行数不一致"), request.feedback());
    }
}
