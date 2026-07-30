package com.sqlteacher.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.ai.*;
import com.sqlteacher.application.config.AiConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Explicitly enabled live smoke tests; never part of ordinary offline regression. */
@EnabledIfSystemProperty(named = "sqlteacher.live.ai", matches = "true")
class LiveAiProviderSmokeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path tempDirectory;

    @Test void shouldSwitchToSmallOllamaModelAndGenerateStructuredDraft() throws Exception {
        AiConfiguration configuration = new AiConfiguration(URI.create("http://127.0.0.1:11434"),
            Duration.ofSeconds(10), Duration.ofSeconds(90), "qwen3.5:0.8b");
        OllamaModelSelectionService selections = new OllamaModelSelectionService(configuration,
            Path.of("app-data", "selected-ai-model.txt"));
        AiModelSelection discovered = selections.refresh();
        assertTrue(discovered.installedModels().contains("qwen3.5:0.8b"));
        assertEquals("qwen3.5:0.8b", selections.select("qwen3.5:0.8b").selectedModel());

        OllamaAiModelProvider provider = new OllamaAiModelProvider(configuration, new OllamaAiStatusService(configuration));
        AiTaskResult result = taskService(provider).execute(taskRequest("qwen3.5:0.8b",
            "Return only a JSON object with fields sqlDraft, intent, explanation. "
                + "Request: query all student names. Schema: student(id,name)."));
        assertTrue(result.success(), result.message());
        assertTrue(JSON.readTree(result.content()).isObject());
    }

    @Test void shouldDiscoverAndCallDeepSeekOpenAiCompatibleProvider() throws Exception {
        String keyText = requiredEnvironment("DEEPSEEK_API_KEY");
        URI endpoint = URI.create(environment("DEEPSEEK_CHAT_ENDPOINT", "https://api.deepseek.com/chat/completions"));
        String model = environment("DEEPSEEK_MODEL", "deepseek-v4-flash");

        char[] probeKey = keyText.toCharArray();
        AiProviderProbeResult probe = new HttpAiProviderProbeService().probe(new AiProviderProfileDraft(
            "deepseek-live", "DeepSeek live", AiProviderKind.OPENAI_COMPATIBLE, endpoint, model, true), probeKey);
        assertTrue(probe.success(), probe.message());
        assertTrue(probe.models().contains(model), "Configured model was not returned by /models");

        PersistentNetworkAiSettingsService settings = new PersistentNetworkAiSettingsService(
            tempDirectory.resolve("profiles.json"), tempDirectory.resolve("credentials"));
        char[] callKey = keyText.toCharArray();
        try {
            settings.save(new AiProviderProfileDraft("deepseek-live", "DeepSeek live",
                AiProviderKind.OPENAI_COMPATIBLE, endpoint, model, true), callKey);
            settings.activate("deepseek-live");
            AiModelProvider localMustNotRun = request -> { throw new AssertionError("network profile was not selected"); };
            AiTaskResult result = taskService(new SwitchableAiModelProvider(localMustNotRun, settings)).execute(
                taskRequest(model, "Return only this JSON object and no other text: {\"status\":\"ok\"}"));
            assertTrue(result.success(), result.message());
            assertEquals("ok", JSON.readTree(result.content()).path("status").asText());
        } finally {
            settings.clear();
        }
    }

    private static DefaultAiTaskService taskService(AiModelProvider provider) {
        return new DefaultAiTaskService(provider, new AiUsagePolicy(10_000, 10_000, 10, Duration.ofSeconds(90)),
            new AiTaskHistoryService() {
                @Override public List<AiTaskHistoryEntry> recent() { return List.of(); }
                @Override public void record(AiTaskHistoryEntry entry) { }
                @Override public void favorite(String id, boolean favorite, String draftContent) { }
                @Override public int requestsToday() { return 0; }
            });
    }

    private static AiTaskRequest taskRequest(String model, String prompt) {
        return new AiTaskRequest(AiTaskType.NL2SQL, model, prompt, "live-smoke-v1",
            new AiContextPreview(AiTaskType.NL2SQL, Set.of(AiContextCategory.USER_REQUEST),
                List.of("live smoke test"), prompt.length(), List.of()));
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for live AI tests");
        return value;
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
