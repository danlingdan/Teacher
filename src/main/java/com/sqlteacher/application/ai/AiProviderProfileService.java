package com.sqlteacher.application.ai;

import java.util.List;
import java.util.Optional;

public interface AiProviderProfileService {
    List<AiProviderProfile> profiles();
    Optional<AiProviderProfile> activeProfile();
    void save(AiProviderProfileDraft profile, char[] credential);
    void activate(String profileId);
    void deactivate();
    void remove(String profileId);
    Optional<OpenAiCompatibleConfiguration> configuration(String profileId);
}
