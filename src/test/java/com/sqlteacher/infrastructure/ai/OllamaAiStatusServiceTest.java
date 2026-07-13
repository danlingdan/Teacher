package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.application.ai.AiStatus;
import com.sqlteacher.application.ai.AiAvailability;
import com.sqlteacher.application.config.AiConfiguration;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OllamaAiStatusServiceTest {
    @Test
    void shouldReturnWarningWhenOllamaEndpointIsUnavailable() {
        OllamaAiStatusService service = new OllamaAiStatusService(
            new AiConfiguration(URI.create("http://127.0.0.1:9"), Duration.ofMillis(200), Duration.ofMillis(30000), "test-model")
        );

        AiStatus status = service.checkStatus();

        assertEquals(AiAvailability.UNAVAILABLE, status.status());
        assertFalse(status.available());
    }
}
