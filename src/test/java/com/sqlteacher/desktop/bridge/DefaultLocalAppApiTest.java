package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultLocalAppApiTest {
    private final ObjectMapper mapper = new ObjectMapper();

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
    void shouldReturnBundledUntrustedKnowledgeSample() throws Exception {
        try (var api = new DefaultLocalAppApi(mapper)) {
            var result = api.invoke("knowledge.sample", mapper.createObjectNode(), () -> false, ignored -> { });

            String markdown = result.path("markdown").asText();
            assertTrue(markdown.contains("[!important]"));
            assertTrue(markdown.contains("```mermaid"));
            assertTrue(markdown.contains("[[SQL 安全#确认门|SQL 安全边界]]"));
            assertFalse(result.path("trustedHtml").asBoolean());
            assertFalse(result.path("externalResourcesAllowed").asBoolean());
        }
    }
}
