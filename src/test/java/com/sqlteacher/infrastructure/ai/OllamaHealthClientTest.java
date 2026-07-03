package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.infrastructure.environment.VerificationStatus;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OllamaHealthClientTest {
    @Test
    void shouldReturnWarningWhenOllamaIsUnavailable() {
        OllamaHealthClient client = new OllamaHealthClient(
            URI.create("http://127.0.0.1:9/api/tags"),
            Duration.ofMillis(200)
        );

        assertEquals(VerificationStatus.WARNING, client.checkHealth().status());
    }
}
