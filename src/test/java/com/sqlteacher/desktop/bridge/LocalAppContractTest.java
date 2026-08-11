package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAppContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void machineReadableManifestMustMatchJavaBoundary() throws Exception {
        var manifest = mapper.readTree(Path.of("contracts/ipc/v1/manifest.json").toFile());
        assertEquals(LocalAppContract.VERSION, manifest.path("contractVersion").asText());
        assertEquals(LocalAppContract.MAX_MESSAGE_BYTES, manifest.path("limits").path("maxRequestBytes").asInt());
        assertEquals(LocalAppContract.MAX_CONCURRENT_REQUESTS,
            manifest.path("limits").path("maxConcurrentRequests").asInt());

        Set<String> methods = new HashSet<>();
        manifest.path("methods").forEach(item -> methods.add(item.asText()));
        Set<String> expectedMethods = new HashSet<>(LocalAppContract.API_METHODS);
        expectedMethods.addAll(LocalAppContract.RESERVED_METHODS);
        assertEquals(expectedMethods, methods);

        Set<String> events = new HashSet<>();
        manifest.path("events").forEach(item -> events.add(item.asText()));
        assertEquals(LocalAppContract.EVENT_TYPES, events);

        Set<String> errorCodes = new HashSet<>();
        manifest.path("errorCodes").forEach(item -> errorCodes.add(item.asText()));
        for (LocalAppErrorCode code : LocalAppErrorCode.values()) {
            assertTrue(errorCodes.contains(code.name()), () -> "Missing manifest error: " + code);
        }
    }
}
