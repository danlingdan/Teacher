package com.sqlteacher.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.ai.AiStatus;
import com.sqlteacher.application.ai.AiAvailability;
import com.sqlteacher.application.ai.AiStatusService;
import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.infrastructure.support.HttpClients;

import java.net.http.HttpClient;

public final class OllamaAiStatusService implements AiStatusService {
    private final AiConfiguration properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OllamaAiStatusService(AiConfiguration properties) {
        this.properties = properties;
        this.httpClient = HttpClients.create(properties.healthTimeout());
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public AiStatus checkStatus() {
        OllamaHealthClient.Probe probe = OllamaHealthClient.probe(
            httpClient, objectMapper, properties.tagsEndpoint(), properties.healthTimeout());
        if (probe.failure() != null) {
            return unavailable(probe.failure());
        }
        if (!probe.reachable()) {
            return new AiStatus(
                AiAvailability.UNAVAILABLE,
                "ollama",
                properties.ollamaBaseUrl().toString(),
                0,
                "Ollama returned HTTP " + probe.statusCode()
            );
        }
        int modelCount = OllamaHealthClient.modelCount(probe.models());
        return new AiStatus(
            AiAvailability.AVAILABLE,
            "ollama",
            properties.ollamaBaseUrl().toString(),
            modelCount,
            "Ollama service reachable, models=" + modelCount
        );
    }

    private AiStatus unavailable(Exception ex) {
        return new AiStatus(
            AiAvailability.UNAVAILABLE,
            "ollama",
            properties.ollamaBaseUrl().toString(),
            0,
            "Ollama service unavailable: " + ex.getClass().getSimpleName()
        );
    }
}
