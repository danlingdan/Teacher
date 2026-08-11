package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.application.config.ApplicationVersion;
import com.sqlteacher.application.database.DatabaseInitializationService;
import com.sqlteacher.application.learning.MasteryLevel;
import com.sqlteacher.application.learning.StudentLearningQueueService;
import com.sqlteacher.infrastructure.cloud.InMemoryLearningEventOwnerContext;
import com.sqlteacher.infrastructure.spring.SqlTeacherApplicationConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.function.Consumer;

public final class DefaultLocalAppApi implements LocalAppApi {
    public static final String CONTRACT_VERSION = "3.0-alpha.1";
    private static final Set<String> METHODS = Set.of(
        "system.health", "home.summary", "knowledge.sample", "editor.languages", "benchmark.echo", "task.demo"
    );

    private final ObjectMapper mapper;
    private AnnotationConfigApplicationContext context;

    public DefaultLocalAppApi(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public JsonNode invoke(String method, JsonNode params, CancellationToken cancellation,
                           Consumer<LocalAppEvent> events) throws Exception {
        if (!METHODS.contains(method)) throw new IllegalArgumentException("Unknown local application method: " + method);
        return switch (method) {
            case "system.health" -> health();
            case "home.summary" -> homeSummary(cancellation);
            case "knowledge.sample" -> knowledgeSample();
            case "editor.languages" -> editorLanguages();
            case "benchmark.echo" -> params.deepCopy();
            case "task.demo" -> demoTask(params, cancellation, events);
            default -> throw new IllegalStateException("Method whitelist and dispatcher are inconsistent");
        };
    }

    private ObjectNode health() {
        ObjectNode result = mapper.createObjectNode();
        result.put("status", "ready");
        result.put("contractVersion", CONTRACT_VERSION);
        result.put("applicationVersion", ApplicationVersion.current());
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("javaVendor", System.getProperty("java.vendor"));
        result.put("coreInitialized", context != null);
        result.put("timestamp", Instant.now().toString());
        return result;
    }

    private ObjectNode homeSummary(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        AnnotationConfigApplicationContext applicationContext = context();
        cancellation.throwIfCancelled();
        var queue = applicationContext.getBean(StudentLearningQueueService.class).refresh();
        var dashboard = queue.dashboard();
        ObjectNode result = mapper.createObjectNode();
        result.put("ownerId", dashboard.ownerId());
        result.put("policyVersion", dashboard.policyVersion());
        result.put("knowledgePointCount", dashboard.mastery().size());
        result.put("needsPracticeCount", dashboard.mastery().stream()
            .filter(item -> item.level() == MasteryLevel.NEEDS_PRACTICE).count());
        result.put("cloudAvailable", queue.cloudAvailable());
        result.put("calculationMillis", dashboard.calculationTime().toMillis());
        ArrayNode actions = result.putArray("actions");
        queue.items().forEach(item -> {
            ObjectNode action = actions.addObject();
            action.put("id", item.action().id());
            action.put("type", item.action().type().name());
            action.put("title", item.action().title());
            action.put("description", item.action().description());
            action.put("priority", item.action().priority());
        });
        return result;
    }

    private ObjectNode knowledgeSample() throws IOException {
        try (InputStream stream = DefaultLocalAppApi.class.getResourceAsStream("/v3-alpha1/knowledge-sample.md")) {
            if (stream == null) throw new IOException("Bundled Alpha.1 knowledge sample is missing");
            ObjectNode result = mapper.createObjectNode();
            result.put("id", "alpha1-safe-rendering");
            result.put("title", "确定性学习闭环");
            result.put("markdown", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            result.put("trustedHtml", false);
            result.put("externalResourcesAllowed", false);
            return result;
        }
    }

    private ObjectNode editorLanguages() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode languages = result.putArray("languages");
        languages.addObject().put("id", "sql").put("label", "SQL").put("completionSource", "deterministic-catalog");
        languages.addObject().put("id", "java").put("label", "Java").put("completionSource", "monaco-defaults");
        result.put("maxModelBytes", 1_048_576);
        return result;
    }

    private ObjectNode demoTask(JsonNode params, CancellationToken cancellation,
                                Consumer<LocalAppEvent> events) throws InterruptedException {
        int steps = Math.clamp(params.path("steps").asInt(5), 1, 20);
        int delayMillis = Math.clamp(params.path("delayMillis").asInt(25), 0, 500);
        for (int step = 1; step <= steps; step++) {
            cancellation.throwIfCancelled();
            if (delayMillis > 0) Thread.sleep(delayMillis);
            ObjectNode payload = mapper.createObjectNode();
            payload.put("step", step);
            payload.put("total", steps);
            payload.put("percent", step * 100 / steps);
            events.accept(new LocalAppEvent("progress", payload));
        }
        return mapper.createObjectNode().put("completed", true).put("steps", steps);
    }

    private synchronized AnnotationConfigApplicationContext context() {
        if (context != null) return context;
        AnnotationConfigApplicationContext created = new AnnotationConfigApplicationContext(SqlTeacherApplicationConfig.class);
        try {
            created.getBean(DatabaseInitializationService.class).initialize();
            created.getBean(InMemoryLearningEventOwnerContext.class).useGuest();
            context = created;
            return created;
        } catch (RuntimeException error) {
            created.close();
            throw error;
        }
    }

    @Override
    public synchronized void close() {
        if (context != null) {
            context.close();
            context = null;
        }
    }
}
