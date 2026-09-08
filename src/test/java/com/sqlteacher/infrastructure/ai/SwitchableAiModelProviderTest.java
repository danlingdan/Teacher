package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.application.ai.AiCompletionRequest;
import com.sqlteacher.application.ai.AiCompletionResult;
import com.sqlteacher.application.ai.AiModelProvider;
import com.sqlteacher.application.ai.NetworkAiSettingsService;
import com.sqlteacher.application.ai.OpenAiCompatibleConfiguration;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused coverage for the per-fingerprint provider cache in {@link SwitchableAiModelProvider}. */
class SwitchableAiModelProviderTest {
    private static final URI ENDPOINT = URI.create("https://127.0.0.1:1/v1");
    // Connections to this unreachable loopback endpoint fail fast, keeping the test hermetic.
    private static final AiCompletionRequest REQUEST =
        new AiCompletionRequest("ignored-model", "prompt", Duration.ofSeconds(1));

    @Test
    void shouldRouteToLocalProviderWhenNoNetworkProfileIsConfigured() {
        SingleUseSettingsService settings = new SingleUseSettingsService();
        AiModelProvider local = request -> AiCompletionResult.success("local-draft", "local-model");
        SwitchableAiModelProvider provider = new SwitchableAiModelProvider(local, settings);

        AiCompletionResult result = provider.complete(REQUEST);

        assertTrue(result.success());
        assertEquals("local-draft", result.content());
        assertNull(provider.cachedNetworkProvider());
    }

    @Test
    void shouldReuseCachedProviderForEqualConfigurationContent() {
        SingleUseSettingsService settings = new SingleUseSettingsService();
        settings.configure(ENDPOINT, "model-a", "key-1".toCharArray());
        SwitchableAiModelProvider provider = new SwitchableAiModelProvider(
            request -> { throw new AssertionError("local provider must not run while a profile is active"); },
            settings);

        provider.complete(REQUEST);
        OpenAiCompatibleModelProvider first = provider.cachedNetworkProvider();
        provider.complete(REQUEST);
        OpenAiCompatibleModelProvider second = provider.cachedNetworkProvider();

        assertSame(first, second, "equal configuration content must reuse the cached provider");
        provider.close();
    }

    @Test
    void shouldRebuildCachedProviderWhenConfigurationChanges() {
        SingleUseSettingsService settings = new SingleUseSettingsService();
        settings.configure(ENDPOINT, "model-a", "key-1".toCharArray());
        SwitchableAiModelProvider provider = new SwitchableAiModelProvider(
            request -> { throw new AssertionError("local provider must not run while a profile is active"); },
            settings);

        provider.complete(REQUEST);
        OpenAiCompatibleModelProvider first = provider.cachedNetworkProvider();
        settings.reconfigure("model-b");
        provider.complete(REQUEST);
        OpenAiCompatibleModelProvider second = provider.cachedNetworkProvider();

        assertNotSame(first, second, "changed configuration content must rebuild the provider");
        provider.close();
    }

    @Test
    void shouldDestroyCachedProviderOnClose() {
        SingleUseSettingsService settings = new SingleUseSettingsService();
        settings.configure(ENDPOINT, "model-a", "key-1".toCharArray());
        SwitchableAiModelProvider provider = new SwitchableAiModelProvider(
            request -> AiCompletionResult.failure("unused", "local-model"), settings);

        provider.complete(REQUEST);
        provider.close();

        assertNull(provider.cachedNetworkProvider());
        settings.clear();
    }

    /** Hands out a fresh single-use configuration per lookup, mirroring the persistent service. */
    private static final class SingleUseSettingsService implements NetworkAiSettingsService {
        private OpenAiCompatibleConfiguration template;

        @Override
        public void configure(URI endpoint, String model, char[] apiKey) {
            clear();
            template = new OpenAiCompatibleConfiguration(endpoint, model, apiKey);
        }

        void reconfigure(String model) {
            configure(template.endpoint(), model, template.apiKey());
        }

        @Override
        public Optional<OpenAiCompatibleConfiguration> current() {
            if (template == null) {
                return Optional.empty();
            }
            return Optional.of(new OpenAiCompatibleConfiguration(
                template.endpoint(), template.model(), template.apiKey()));
        }

        @Override
        public void clear() {
            if (template != null) {
                template.destroy();
                template = null;
            }
        }
    }
}
