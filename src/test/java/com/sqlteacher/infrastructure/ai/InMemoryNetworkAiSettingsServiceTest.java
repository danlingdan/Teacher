package com.sqlteacher.infrastructure.ai;

import org.junit.jupiter.api.Test;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

class InMemoryNetworkAiSettingsServiceTest {
    @Test void configuresAndClearsNetworkProviderInMemory() {
        var service=new InMemoryNetworkAiSettingsService();
        char[] key="test-api-key".toCharArray();
        service.configure(URI.create("https://example.test/v1/chat/completions"),"test-model",key);
        assertEquals("test-model",service.current().orElseThrow().model());
        assertArrayEquals(new char[key.length],key);
        service.clear();
        assertTrue(service.current().isEmpty());
    }

    @Test void rejectsPlainHttpEndpoint() {
        var service=new InMemoryNetworkAiSettingsService();
        assertThrows(IllegalArgumentException.class,()->service.configure(URI.create("http://example.test/v1/chat/completions"),"m","secret".toCharArray()));
    }
}
