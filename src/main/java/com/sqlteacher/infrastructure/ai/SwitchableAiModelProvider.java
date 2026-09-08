package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.application.ai.AiCompletionRequest;
import com.sqlteacher.application.ai.AiCompletionResult;
import com.sqlteacher.application.ai.AiModelProvider;
import com.sqlteacher.application.ai.NetworkAiSettingsService;
import com.sqlteacher.application.ai.OpenAiCompatibleConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Arrays;
import java.util.Objects;

/**
 * Routes calls to the active network profile and caches one {@link OpenAiCompatibleModelProvider}
 * per configuration fingerprint. Creating a provider per call would build a new HttpClient and a
 * new ObjectMapper every time. The transient configuration handed out by the settings service is
 * consumed and destroyed on every call (single-use contract); the cache keeps one private copy of
 * the active configuration and destroys that copy only when the fingerprint changes or the
 * provider itself is closed.
 */
public final class SwitchableAiModelProvider implements AiModelProvider, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SwitchableAiModelProvider.class);

    private final AiModelProvider local;
    private final NetworkAiSettingsService settings;
    private final Object cacheLock = new Object();
    private CacheEntry cached; // guarded by cacheLock

    public SwitchableAiModelProvider(AiModelProvider local, NetworkAiSettingsService settings) {
        this.local = Objects.requireNonNull(local, "local must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
    }

    @Override
    public AiCompletionResult complete(AiCompletionRequest request) {
        OpenAiCompatibleConfiguration configuration = settings.current().orElse(null);
        if (configuration == null) {
            destroyCachedProvider("network AI profile was deactivated");
            return local.complete(request);
        }
        return providerFor(configuration).complete(request);
    }

    @Override
    public String preferredModel() {
        OpenAiCompatibleConfiguration configuration = settings.current().orElse(null);
        if (configuration == null) {
            return "";
        }
        try {
            return configuration.model();
        } finally {
            configuration.destroy();
        }
    }

    @Override
    public void close() {
        destroyCachedProvider("provider closed");
    }

    /** Returns the cached provider, rebuilding it whenever the configuration fingerprint changed. */
    private OpenAiCompatibleModelProvider providerFor(OpenAiCompatibleConfiguration configuration) {
        CacheKey key = CacheKey.of(configuration);
        synchronized (cacheLock) {
            if (cached != null && cached.matches(key)) {
                configuration.destroy(); // transient lookup result; the cache owns the live copy
                return cached.provider();
            }
            CacheEntry replacement;
            try {
                replacement = new CacheEntry(key, defensiveCopy(configuration));
            } catch (RuntimeException error) {
                configuration.destroy();
                throw error;
            }
            configuration.destroy(); // transient lookup result
            destroyCachedProvider("network AI configuration changed");
            cached = replacement;
            return replacement.provider();
        }
    }

    private void destroyCachedProvider(String reason) {
        synchronized (cacheLock) {
            CacheEntry entry = cached;
            if (entry == null) {
                return;
            }
            cached = null;
            try {
                entry.provider().close();
            } catch (RuntimeException error) {
                log.warn("Replaced network AI client did not close cleanly: {}", error.getClass().getSimpleName());
            }
            entry.configuration().destroy();
            log.debug("Destroyed cached network AI provider ({})", reason);
        }
    }

    private static OpenAiCompatibleConfiguration defensiveCopy(OpenAiCompatibleConfiguration source) {
        return new OpenAiCompatibleConfiguration(source.endpoint(), source.model(), source.apiKey());
    }

    /** Package-private snapshot of the cached provider for focused tests in this package. */
    OpenAiCompatibleModelProvider cachedNetworkProvider() {
        synchronized (cacheLock) {
            return cached == null ? null : cached.provider();
        }
    }

    private static final class CacheEntry {
        private final CacheKey key;
        private final OpenAiCompatibleConfiguration configuration;
        private final OpenAiCompatibleModelProvider provider;

        CacheEntry(CacheKey key, OpenAiCompatibleConfiguration configuration) {
            this.key = key;
            this.configuration = configuration;
            this.provider = new OpenAiCompatibleModelProvider(configuration);
        }

        boolean matches(CacheKey other) {
            return this.key.equals(other);
        }

        OpenAiCompatibleConfiguration configuration() {
            return configuration;
        }

        OpenAiCompatibleModelProvider provider() {
            return provider;
        }
    }

    /**
     * Content fingerprint of a profile configuration. The credential is represented by its
     * length and hash only; no key material is retained. The settings interface exposes no
     * profile identity, so equal content is the strongest available cache key.
     */
    private record CacheKey(URI endpoint, String model, int keyLength, int keyFingerprint) {
        static CacheKey of(OpenAiCompatibleConfiguration configuration) {
            char[] key = configuration.apiKey();
            try {
                return new CacheKey(
                    configuration.endpoint(),
                    configuration.model(),
                    key.length,
                    Arrays.hashCode(key)
                );
            } finally {
                Arrays.fill(key, '\0');
            }
        }
    }
}
