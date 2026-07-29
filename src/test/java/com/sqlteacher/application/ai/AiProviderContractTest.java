package com.sqlteacher.application.ai;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiProviderContractTest {
    @Test
    void shouldAllowHttpsAndLoopbackHttpButRejectPublicHttp() {
        AiProviderProfile network = profile(URI.create("https://ai.example.test/v1"));
        AiProviderProfile local = new AiProviderProfile(
            "local", "Ollama", AiProviderKind.OLLAMA, URI.create("http://127.0.0.1:11434"),
            "qwen", true, ""
        );

        assertEquals("https", network.endpoint().getScheme());
        assertEquals(AiProviderKind.OLLAMA, local.kind());
        assertThrows(IllegalArgumentException.class,
            () -> profile(URI.create("http://ai.example.test/v1")));
    }

    @Test
    void shouldExposeOnlyImmutableContextPreviewMetadata() {
        Set<AiContextCategory> categories = new java.util.HashSet<>(Set.of(AiContextCategory.DATABASE_SCHEMA));
        List<String> sources = new java.util.ArrayList<>(List.of("current schema"));
        AiContextPreview preview = new AiContextPreview(
            AiTaskType.NL2SQL, categories, sources, 128, List.of("student identity removed")
        );
        categories.clear();
        sources.clear();

        assertEquals(Set.of(AiContextCategory.DATABASE_SCHEMA), preview.categories());
        assertEquals(List.of("current schema"), preview.sources());
        assertThrows(UnsupportedOperationException.class,
            () -> preview.sources().add("unexpected"));
    }

    private static AiProviderProfile profile(URI endpoint) {
        return new AiProviderProfile(
            "network", "Network AI", AiProviderKind.OPENAI_COMPATIBLE, endpoint,
            "model-a", true, "credential-1"
        );
    }
}
