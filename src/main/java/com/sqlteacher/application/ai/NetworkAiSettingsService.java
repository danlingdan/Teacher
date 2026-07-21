package com.sqlteacher.application.ai;

import java.net.URI;
import java.util.Optional;

public interface NetworkAiSettingsService {
    void configure(URI endpoint, String model, char[] apiKey);
    Optional<OpenAiCompatibleConfiguration> current();
    void clear();
}
