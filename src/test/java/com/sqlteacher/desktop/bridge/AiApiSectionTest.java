package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.application.ai.AiAvailability;
import com.sqlteacher.application.ai.AiContextCategory;
import com.sqlteacher.application.ai.AiContextPreview;
import com.sqlteacher.application.ai.AiModelSelection;
import com.sqlteacher.application.ai.AiModelSelectionService;
import com.sqlteacher.application.ai.AiProviderKind;
import com.sqlteacher.application.ai.AiProviderProfile;
import com.sqlteacher.application.ai.AiProviderProfileDraft;
import com.sqlteacher.application.ai.AiProviderProfileService;
import com.sqlteacher.application.ai.AiProviderProbeResult;
import com.sqlteacher.application.ai.AiProviderProbeService;
import com.sqlteacher.application.ai.AiStatus;
import com.sqlteacher.application.ai.AiStatusService;
import com.sqlteacher.application.ai.AiTaskErrorCode;
import com.sqlteacher.application.ai.AiTaskType;
import com.sqlteacher.application.ai.OpenAiCompatibleConfiguration;
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

import java.net.URI;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void aiKnowledgeAskMapsStructuredContextIntoTheSearchFilter() throws Exception {
        List<Object[]> asks = new ArrayList<>();
        GroundedKnowledgeExplanationService explanation = fake(GroundedKnowledgeExplanationService.class, Map.of(
            "explain", args -> {
                asks.add(args);
                return new GroundedKnowledgeAnswer(false, "片段", "test-model", List.of(), "");
            }));
        try (var host = hostWithBeans(explanation)) {
            AiApiSection section = new AiApiSection(host);

            // v3.6.0 KBF-1：阅读上下文经结构化字段映射为课程/章节过滤（保持私有可见语义）。
            section.handle("ai.knowledge.ask", mapper.createObjectNode()
                .put("question", "什么是周转时间")
                .set("context", mapper.createObjectNode()
                    .put("courseTitle", "操作系统")
                    .put("sectionTitle", "进程调度")), () -> false, ignored -> { });

            // 空上下文与缺省字段保持全库检索语义（旧前端兼容）。
            ObjectNode emptyContextParams = mapper.createObjectNode().put("question", "什么是周转时间");
            emptyContextParams.putObject("context");
            section.handle("ai.knowledge.ask", emptyContextParams, () -> false, ignored -> { });

            assertEquals(new CourseKnowledgeSearchFilter("操作系统", "进程调度", "", true), asks.get(0)[1]);
            assertEquals(CourseKnowledgeSearchFilter.allLocal(), asks.get(1)[1]);
        }
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

    @Test
    void aiProviderListShapesProfilesWithoutCredentialMaterial() throws Exception {
        var profiles = List.of(new AiProviderProfile("deepseek", "DeepSeek",
            AiProviderKind.OPENAI_COMPATIBLE, URI.create("https://api.deepseek.com"),
            "deepseek-chat", true, "dpapi:device-reference"));
        var service = fake(AiProviderProfileService.class, Map.of(
            "profiles", args -> profiles,
            "activeProfile", args -> Optional.of(profiles.get(0))));
        try (var host = hostWithBeans(service)) {
            AiApiSection section = new AiApiSection(host);

            JsonNode result = section.handle("ai.provider.list", mapper.createObjectNode(),
                () -> false, ignored -> { });

            assertEquals(1, result.path("items").size());
            assertEquals("deepseek", result.path("items").get(0).path("id").asText());
            assertEquals(true, result.path("items").get(0).path("active").asBoolean());
            assertEquals("deepseek", result.path("activeProfileId").asText());
            // 安全校验：列表响应不携带任何密钥材料（凭据引用也不出桥）。
            assertFalse(result.toString().toLowerCase().contains("credential"));
            assertFalse(result.toString().contains("dpapi"));
        }
    }

    @Test
    void aiProviderSavePassesDraftAndZeroesTheCredential() throws Exception {
        List<Object[]> saves = new ArrayList<>();
        var service = fake(AiProviderProfileService.class, Map.of(
            "save", args -> {
                saves.add(args);
                return null;
            },
            "profiles", args -> List.of(),
            "activeProfile", args -> Optional.empty()));
        try (var host = hostWithBeans(service)) {
            AiApiSection section = new AiApiSection(host);
            ObjectNode params = mapper.createObjectNode();
            params.put("displayName", "DeepSeek");
            params.put("kind", "OPENAI_COMPATIBLE");
            params.put("endpoint", "https://api.deepseek.com");
            params.put("model", "deepseek-chat");
            params.put("credential", "sk-secret");

            section.handle("ai.provider.save", params, () -> false, ignored -> { });

            assertEquals(1, saves.size());
            AiProviderProfileDraft draft = (AiProviderProfileDraft) saves.get(0)[0];
            assertEquals("DeepSeek", draft.displayName());
            assertEquals(URI.create("https://api.deepseek.com"), draft.endpoint());
            char[] credential = (char[]) saves.get(0)[1];
            // 调用返回后凭据数组被清零，不在前端可达的任何缓冲区残留。
            assertEquals("\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000", new String(credential));
        }
    }

    @Test
    void aiProviderActivateDeactivateAndRemoveDispatchToTheService() throws Exception {
        List<String> calls = new ArrayList<>();
        var service = fake(AiProviderProfileService.class, Map.of(
            "activate", args -> {
                calls.add("activate:" + args[0]);
                return null;
            },
            "deactivate", args -> {
                calls.add("deactivate");
                return null;
            },
            "remove", args -> {
                calls.add("remove:" + args[0]);
                return null;
            },
            "profiles", args -> List.of(),
            "activeProfile", args -> Optional.empty()));
        try (var host = hostWithBeans(service)) {
            AiApiSection section = new AiApiSection(host);

            section.handle("ai.provider.activate", mapper.createObjectNode().put("id", "p1"),
                () -> false, ignored -> { });
            section.handle("ai.provider.deactivate", mapper.createObjectNode(),
                () -> false, ignored -> { });
            section.handle("ai.provider.remove", mapper.createObjectNode().put("id", "p1"),
                () -> false, ignored -> { });

            assertEquals(List.of("activate:p1", "deactivate", "remove:p1"), calls);
        }
    }

    @Test
    void aiProviderTestProbesAndNeverEchoesTheCredential() throws Exception {
        var probe = fake(AiProviderProbeService.class, Map.of(
            "probe", args -> {
                char[] credential = (char[]) args[1];
                if (!new String(credential).equals("sk-live")) {
                    throw new IllegalArgumentException("probe must receive the typed credential");
                }
                return new AiProviderProbeResult(true, List.of("deepseek-chat"), "连接成功。", null);
            }));
        try (var host = hostWithBeans(probe)) {
            AiApiSection section = new AiApiSection(host);
            ObjectNode params = mapper.createObjectNode();
            params.put("displayName", "DeepSeek");
            params.put("kind", "OPENAI_COMPATIBLE");
            params.put("endpoint", "https://api.deepseek.com");
            params.put("model", "deepseek-chat");
            params.put("credential", "sk-live");

            JsonNode result = section.handle("ai.provider.test", params, () -> false, ignored -> { });

            assertTrue(result.path("success").asBoolean());
            assertEquals(1, result.path("models").size());
            assertFalse(result.toString().contains("sk-live"));
        }
    }

    // v3.10.0 HAJ-9：「发现模型」——ai.provider.models 复用同一探测服务，响应不含密钥材料。
    @Test
    void aiProviderModelsProbesTheTypedEndpointAndZeroesTheCredential() throws Exception {
        List<Object[]> probes = new ArrayList<>();
        var probe = fake(AiProviderProbeService.class, Map.of(
            "probe", args -> {
                probes.add(args);
                AiProviderProfileDraft draft = (AiProviderProfileDraft) args[0];
                if (draft.kind() != AiProviderKind.OPENAI_COMPATIBLE
                    || !draft.endpoint().equals(URI.create("https://api.example.com"))) {
                    throw new IllegalArgumentException("probe must receive the typed kind and endpoint");
                }
                if (!new String((char[]) args[1]).equals("sk-live")) {
                    throw new IllegalArgumentException("probe must receive the typed credential");
                }
                return new AiProviderProbeResult(true,
                    List.of("example-chat", "example-mini"), "连接成功，发现 2 个模型。", null);
            }));
        try (var host = hostWithBeans(probe)) {
            AiApiSection section = new AiApiSection(host);
            ObjectNode params = mapper.createObjectNode();
            params.put("endpoint", "https://api.example.com");
            params.put("credential", "sk-live");

            JsonNode result = section.handle("ai.provider.models", params, () -> false, ignored -> { });

            assertTrue(result.path("success").asBoolean());
            assertEquals("连接成功，发现 2 个模型。", result.path("message").asText());
            assertEquals(List.of("example-chat", "example-mini"), mapper.convertValue(
                result.path("models"), mapper.getTypeFactory().constructCollectionType(List.class, String.class)));
            assertFalse(result.hasNonNull("errorCode"));
            assertFalse(result.toString().contains("sk-live"));
        }
        // 调用返回后凭据数组被清零，不在前端可达的任何缓冲区残留。
        assertEquals("\u0000\u0000\u0000\u0000\u0000\u0000\u0000", new String((char[]) probes.get(0)[1]));
    }

    @Test
    void aiProviderModelsBorrowsTheStoredCredentialWhenEditingWithoutRetyping() throws Exception {
        List<Object[]> probes = new ArrayList<>();
        List<String> probedCredentials = new ArrayList<>();
        var probe = fake(AiProviderProbeService.class, Map.of(
            "probe", args -> {
                probes.add(args);
                // probe 消费密钥后，AiApiSection 的 finally 会把数组清零，因此必须在调用内快照。
                probedCredentials.add(new String((char[]) args[1]));
                return new AiProviderProbeResult(true, List.of("stored-chat"), "连接成功，发现 1 个模型。", null);
            }));
        List<String> configurationIds = new ArrayList<>();
        var providers = fake(AiProviderProfileService.class, Map.of(
            "configuration", args -> {
                configurationIds.add((String) args[0]);
                return Optional.of(new OpenAiCompatibleConfiguration(
                    URI.create("https://api.example.com"), "stored-chat", "sk-stored".toCharArray()));
            }));
        try (var host = hostWithBeans(providers, probe)) {
            AiApiSection section = new AiApiSection(host);
            ObjectNode params = mapper.createObjectNode();
            params.put("id", "deepseek");
            params.put("endpoint", "https://api.example.com");

            JsonNode result = section.handle("ai.provider.models", params, () -> false, ignored -> { });

            assertTrue(result.path("success").asBoolean());
            assertEquals(1, result.path("models").size());
            assertEquals(List.of("deepseek"), configurationIds);
            assertEquals(List.of("sk-stored"), probedCredentials);
            // 借用的密钥副本是一次性契约：probe 消费后清零，响应与可达缓冲区均无残留。
            assertEquals("\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000", new String((char[]) probes.get(0)[1]));
            assertFalse(result.toString().contains("sk-stored"));
        }
    }

    @Test
    void aiProviderModelsProbesWithAnEmptyCredentialWhenNothingIsStoredOrTyped() throws Exception {
        List<Object[]> probes = new ArrayList<>();
        var probe = fake(AiProviderProbeService.class, Map.of(
            "probe", args -> {
                probes.add(args);
                return new AiProviderProbeResult(false, List.of(),
                    "认证失败，请检查 API Key。", AiTaskErrorCode.AUTHENTICATION_FAILED);
            }));
        try (var host = hostWithBeans(probe)) {
            AiApiSection section = new AiApiSection(host);
            ObjectNode params = mapper.createObjectNode();
            params.put("endpoint", "https://api.example.com");

            JsonNode result = section.handle("ai.provider.models", params, () -> false, ignored -> { });

            assertEquals("", new String((char[]) probes.get(0)[1]));
            assertFalse(result.path("success").asBoolean());
            assertEquals("认证失败，请检查 API Key。", result.path("message").asText());
            assertEquals("AUTHENTICATION_FAILED", result.path("errorCode").asText());
        }
    }

    @Test
    void aiProviderModelsSurfacesTheClassifiedProbeFailure() throws Exception {
        var probe = fake(AiProviderProbeService.class, Map.of(
            "probe", args -> new AiProviderProbeResult(false, List.of(),
                "Provider 正在限流，请稍后重试。", AiTaskErrorCode.RATE_LIMITED)));
        try (var host = hostWithBeans(probe)) {
            AiApiSection section = new AiApiSection(host);
            ObjectNode params = mapper.createObjectNode();
            params.put("endpoint", "https://api.example.com");
            params.put("credential", "sk-live");

            JsonNode result = section.handle("ai.provider.models", params, () -> false, ignored -> { });

            assertFalse(result.path("success").asBoolean());
            assertEquals("Provider 正在限流，请稍后重试。", result.path("message").asText());
            assertEquals("RATE_LIMITED", result.path("errorCode").asText());
            assertEquals(0, result.path("models").size());
        }
    }

    @Test
    void aiEngineStatusReportsActiveNetworkProfileWithoutEndpointLeak() throws Exception {
        var providers = fake(AiProviderProfileService.class, Map.of(
            "activeProfile", args -> Optional.of(new AiProviderProfile("deepseek", "DeepSeek",
                AiProviderKind.OPENAI_COMPATIBLE, URI.create("https://api.deepseek.com"),
                "deepseek-chat", true, "dpapi:device-reference"))));
        var ollama = fake(AiStatusService.class, Map.of(
            "checkStatus", args -> new AiStatus(AiAvailability.AVAILABLE, "ollama",
                "http://localhost:11434", 3, "Ollama service reachable, models=3")));
        try (var host = hostWithBeans(providers, ollama)) {
            AiApiSection section = new AiApiSection(host);

            JsonNode result = section.handle("ai.engine.status", mapper.createObjectNode(),
                () -> false, ignored -> { });

            // v3.10.0 HAJ-3：网络供应商生效时状态携带显示名与模型，不携带 endpoint/密钥材料。
            assertTrue(result.path("networkActive").asBoolean());
            assertEquals("OPENAI_COMPATIBLE", result.path("activeKind").asText());
            assertEquals("DeepSeek", result.path("displayName").asText());
            assertEquals("deepseek-chat", result.path("selectedModel").asText());
            assertTrue(result.path("ollamaAvailable").asBoolean());
            assertEquals(3, result.path("ollamaModelCount").asInt());
            assertFalse(result.toString().contains("api.deepseek.com"));
            assertFalse(result.toString().toLowerCase().contains("credential"));
        }
    }

    @Test
    void aiEngineStatusFallsBackToLocalOllamaAndRefreshesModelSelection() throws Exception {
        List<Object[]> refreshes = new ArrayList<>();
        var providers = fake(AiProviderProfileService.class, Map.of(
            "activeProfile", args -> Optional.empty()));
        var ollama = fake(AiStatusService.class, Map.of(
            "checkStatus", args -> new AiStatus(AiAvailability.UNAVAILABLE, "ollama",
                "http://localhost:11434", 0, "Ollama service unavailable: ConnectException")));
        var selection = fake(AiModelSelectionService.class, Map.of(
            "refresh", args -> {
                refreshes.add(args);
                return new AiModelSelection(List.of(), "", "Ollama is running, but no local model is installed");
            }));
        try (var host = hostWithBeans(providers, ollama, selection)) {
            AiApiSection section = new AiApiSection(host);

            JsonNode result = section.handle("ai.engine.status", mapper.createObjectNode(),
                () -> false, ignored -> { });

            assertFalse(result.path("networkActive").asBoolean());
            assertEquals("OLLAMA", result.path("activeKind").asText());
            assertEquals("本地 Ollama", result.path("displayName").asText());
            assertEquals("", result.path("selectedModel").asText());
            assertFalse(result.path("ollamaAvailable").asBoolean());
            // 状态打开即探测一次本地模型选择，避免首页长期展示过期缓存。
            assertEquals(1, refreshes.size());
        }
    }

    @Test
    void aiModelListAndSelectWrapTheSelectionService() throws Exception {
        List<String> selected = new ArrayList<>();
        var selection = fake(AiModelSelectionService.class, Map.of(
            "refresh", args -> new AiModelSelection(
                List.of("qwen2.5:7b", "llama3:8b"), "qwen2.5:7b", "Detected 2 local model(s)"),
            "select", args -> {
                selected.add((String) args[0]);
                return new AiModelSelection(
                    List.of("qwen2.5:7b", "llama3:8b"), (String) args[0], "Selected model: " + args[0]);
            }));
        try (var host = hostWithBeans(selection)) {
            AiApiSection section = new AiApiSection(host);

            JsonNode list = section.handle("ai.model.list", mapper.createObjectNode(),
                () -> false, ignored -> { });
            assertEquals(2, list.path("installedModels").size());
            assertEquals("qwen2.5:7b", list.path("selectedModel").asText());

            JsonNode picked = section.handle("ai.model.select",
                mapper.createObjectNode().put("model", "llama3:8b"), () -> false, ignored -> { });
            assertEquals(List.of("llama3:8b"), selected);
            assertEquals("llama3:8b", picked.path("selectedModel").asText());

            assertThrows(IllegalArgumentException.class, () -> section.handle("ai.model.select",
                mapper.createObjectNode().put("model", "  "), () -> false, ignored -> { }));
        }
    }
}
