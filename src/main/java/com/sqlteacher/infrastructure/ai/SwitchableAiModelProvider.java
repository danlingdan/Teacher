package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.application.ai.*;
import java.util.Objects;

public final class SwitchableAiModelProvider implements AiModelProvider {
    private final AiModelProvider local;
    private final NetworkAiSettingsService settings;
    public SwitchableAiModelProvider(AiModelProvider local,NetworkAiSettingsService settings){this.local=Objects.requireNonNull(local);this.settings=Objects.requireNonNull(settings);}
    @Override public AiCompletionResult complete(AiCompletionRequest request){return settings.current().<AiModelProvider>map(OpenAiCompatibleModelProvider::new).orElse(local).complete(request);}
    @Override public String preferredModel(){return settings.current().map(OpenAiCompatibleConfiguration::model).orElse("");}
}
