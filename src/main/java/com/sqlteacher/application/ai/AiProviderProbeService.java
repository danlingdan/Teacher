package com.sqlteacher.application.ai;

public interface AiProviderProbeService {
    AiProviderProbeResult probe(AiProviderProfileDraft profile, char[] credential);
}
