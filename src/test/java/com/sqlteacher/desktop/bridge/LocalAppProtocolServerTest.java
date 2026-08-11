package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAppProtocolServerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldDispatchTypedRequestAndReturnStructuredResponse() throws Exception {
        String input = request("health-1", "system.health", "{}") + System.lineSeparator()
            + request("shutdown-1", "system.shutdown", "{}") + System.lineSeparator();
        StringWriter output = new StringWriter();
        LocalAppApi api = (method, params, cancellation, events) -> mapper.createObjectNode().put("status", "ready");

        try (var server = new LocalAppProtocolServer(mapper, api, new StringReader(input), output)) {
            server.run();
        }

        List<JsonNode> messages = output.toString().lines().map(this::read).toList();
        assertEquals(2, messages.size());
        JsonNode health = messages.stream().filter(item -> item.path("requestId").asText().equals("health-1"))
            .findFirst().orElseThrow();
        assertEquals("ready", health.path("result").path("status").asText());
        assertEquals(DefaultLocalAppApi.CONTRACT_VERSION, health.path("contractVersion").asText());
    }

    @Test
    void shouldRejectUnsupportedContractBeforeCallingApi() throws Exception {
        String input = "{\"requestId\":\"old\",\"method\":\"system.health\",\"params\":{},\"contractVersion\":\"2\"}\n"
            + request("shutdown-1", "system.shutdown", "{}") + "\n";
        StringWriter output = new StringWriter();
        LocalAppApi api = (method, params, cancellation, events) -> {
            throw new AssertionError("API must not be called for an unsupported contract");
        };

        try (var server = new LocalAppProtocolServer(mapper, api, new StringReader(input), output)) {
            server.run();
        }

        JsonNode error = output.toString().lines().map(this::read)
            .filter(item -> item.path("requestId").asText().equals("old")).findFirst().orElseThrow();
        assertEquals("CONTRACT_VERSION_UNSUPPORTED", error.path("error").path("code").asText());
        assertFalse(error.path("error").path("retryable").asBoolean());
    }

    @Test
    void shouldEmitProgressAndSupportCancellationProtocol() throws Exception {
        LocalAppApi api = new LocalAppApi() {
            @Override
            public JsonNode invoke(String method, JsonNode params, CancellationToken cancellation,
                                   Consumer<LocalAppEvent> events) throws Exception {
                events.accept(new LocalAppEvent("progress", mapper.createObjectNode().put("percent", 25)));
                while (!cancellation.cancelled()) Thread.sleep(2);
                cancellation.throwIfCancelled();
                return mapper.nullNode();
            }
        };
        String input = request("task-1", "task.demo", "{}") + "\n"
            + request("cancel-1", "system.cancel", "{\"targetRequestId\":\"task-1\"}") + "\n"
            + request("shutdown-1", "system.shutdown", "{}") + "\n";
        StringWriter output = new StringWriter();

        try (var server = new LocalAppProtocolServer(mapper, api, new StringReader(input), output)) {
            server.run();
        }

        List<JsonNode> messages = output.toString().lines().map(this::read).toList();
        assertTrue(messages.stream().anyMatch(item -> item.path("requestId").asText().equals("task-1")
            && item.path("error").path("code").asText().equals("CANCELLED")));
        assertTrue(messages.stream().anyMatch(item -> item.path("requestId").asText().equals("cancel-1")
            && item.path("result").path("cancelled").asBoolean()));
    }

    private String request(String id, String method, String params) {
        return "{\"requestId\":\"" + id + "\",\"method\":\"" + method
            + "\",\"params\":" + params + ",\"contractVersion\":\""
            + DefaultLocalAppApi.CONTRACT_VERSION + "\"}";
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
