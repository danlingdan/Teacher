package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.application.config.ApplicationVersion;
import com.sqlteacher.application.runner.CodeRunRequest;
import com.sqlteacher.application.runner.LocalCodeRunner;
import com.sqlteacher.domain.activity.CodeExecutionLimits;
import com.sqlteacher.domain.activity.CodeLanguage;

import java.time.Instant;
import java.util.Set;
import java.util.function.Consumer;

/** v3.4.0 REF-8: health/session/code-runner surface of the desktop bridge. */
final class SystemApiSection extends ApiSection {

    SystemApiSection(ApiSectionHost host) {
        super(host);
    }

    @Override
    public Set<String> supportedMethods() {
        return Set.of(
            "system.health", "session.current",
            "runner.capabilities", "runner.run",
            "editor.languages"
        );
    }

    @Override
    public JsonNode handle(String method, JsonNode params, CancellationToken cancellation,
                           Consumer<LocalAppEvent> events) throws Exception {
        return switch (method) {
            case "system.health" -> health();
            case "session.current" -> currentSession();
            case "runner.capabilities" -> runnerCapabilities(cancellation);
            case "runner.run" -> runnerRun(params, cancellation, events);
            case "editor.languages" -> editorLanguages();
            default -> throw new IllegalStateException("Method whitelist and dispatcher are inconsistent");
        };
    }

    private ObjectNode health() {
        ObjectNode result = mapper.createObjectNode();
        result.put("status", "ready");
        result.put("contractVersion", LocalAppContract.VERSION);
        result.put("applicationVersion", ApplicationVersion.current());
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("javaVendor", System.getProperty("java.vendor"));
        result.put("coreInitialized", coreInitialized());
        result.put("timestamp", Instant.now().toString());
        return result;
    }

    private JsonNode runnerCapabilities(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return mapper.createObjectNode().set("items",
            mapper.valueToTree(context().getBean(LocalCodeRunner.class).capabilities()));
    }

    private JsonNode runnerRun(JsonNode params, CancellationToken cancellation,
                               Consumer<LocalAppEvent> events) {
        CodeLanguage language;
        try {
            language = CodeLanguage.valueOf(requiredText(params, "language", 16).toUpperCase());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unsupported code language");
        }
        String source = requiredText(params, "sourceCode", 256 * 1024);
        String input = params.path("standardInput").asText("");
        if (input.length() > 64 * 1024) throw new IllegalArgumentException("standardInput exceeds 64 KiB");
        emit(events, "runner.progress", "phase", "starting");
        var result = context().getBean(LocalCodeRunner.class).run(
            new CodeRunRequest(language, source, input, CodeExecutionLimits.defaults()),
            cancellation::cancelled);
        cancellation.throwIfCancelled();
        emit(events, "runner.progress", "phase", "completed");
        return mapper.valueToTree(result);
    }

    private ObjectNode editorLanguages() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode languages = result.putArray("languages");
        languages.addObject().put("id", "sql").put("label", "SQL").put("completionSource", "deterministic-catalog");
        languages.addObject().put("id", "java").put("label", "Java").put("completionSource", "monaco-defaults");
        result.put("maxModelBytes", 1_048_576);
        return result;
    }
}
