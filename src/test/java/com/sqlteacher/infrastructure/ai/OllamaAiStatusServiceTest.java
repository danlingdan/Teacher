package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.application.ai.AiStatus;
import com.sqlteacher.infrastructure.config.AiModelProperties;
import com.sqlteacher.infrastructure.environment.VerificationStatus;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OllamaAiStatusServiceTest {
    @Test
    void shouldReturnWarningWhenOllamaEndpointIsUnavailable() {
        OllamaAiStatusService service = new OllamaAiStatusService(
            new AiModelProperties(URI.create("http://127.0.0.1:9"), Duration.ofMillis(200))
        );

        AiStatus status = service.checkStatus();

        assertEquals(VerificationStatus.WARNING, status.status());
        assertFalse(status.available());
    }
}
