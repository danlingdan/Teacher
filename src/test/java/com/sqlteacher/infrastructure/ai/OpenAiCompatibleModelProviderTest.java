package com.sqlteacher.infrastructure.ai;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiCompatibleModelProviderTest {

    @Test
    void shouldNormalizeProviderBaseUrlsToChatCompletions() {
        assertEquals(
            URI.create("https://api.deepseek.com/chat/completions"),
            OpenAiCompatibleModelProvider.chatCompletionsEndpoint(URI.create("https://api.deepseek.com"))
        );
        assertEquals(
            URI.create("https://api.openai.com/v1/chat/completions"),
            OpenAiCompatibleModelProvider.chatCompletionsEndpoint(URI.create("https://api.openai.com/v1"))
        );
    }

    @Test
    void shouldPreserveCompletionUrlsAndConvertModelsUrls() {
        URI completion = URI.create("https://provider.example/v1/chat/completions");
        assertEquals(completion, OpenAiCompatibleModelProvider.chatCompletionsEndpoint(completion));
        assertEquals(
            completion,
            OpenAiCompatibleModelProvider.chatCompletionsEndpoint(URI.create("https://provider.example/v1/models"))
        );
        assertEquals(
            completion,
            OpenAiCompatibleModelProvider.chatCompletionsEndpoint(URI.create("https://provider.example/v1/chat/completions/"))
        );
    }
}
