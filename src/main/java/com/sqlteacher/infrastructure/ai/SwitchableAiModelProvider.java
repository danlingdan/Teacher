package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.application.ai.*;
import java.util.Objects;

public final class SwitchableAiModelProvider implements AiModelProvider {
    private final AiModelProvider local;
    private final NetworkAiSettingsService settings;
    public SwitchableAiModelProvider(AiModelProvider local,NetworkAiSettingsService settings){this.local=Objects.requireNonNull(local);this.settings=Objects.requireNonNull(settings);}
    @Override
    public AiCompletionResult complete(AiCompletionRequest request) {
        OpenAiCompatibleConfiguration configuration = settings.current().orElse(null);
        if (configuration == null) return local.complete(request);
        try {
            return new OpenAiCompatibleModelProvider(configuration).complete(request);
        } finally {
            configuration.destroy();
        }
    }

    @Override
    public String preferredModel() {
        OpenAiCompatibleConfiguration configuration = settings.current().orElse(null);
        if (configuration == null) return "";
        try {
            return configuration.model();
        } finally {
            configuration.destroy();
        }
    }
}
