package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LocalAppProtocolServer implements AutoCloseable {
    static final int MAX_MESSAGE_CHARS = 1_048_576;
    static final int MAX_CONCURRENT_REQUESTS = 8;
    private static final Set<String> RESERVED_METHODS = Set.of("system.cancel", "system.shutdown");

    private final ObjectMapper mapper;
    private final LocalAppApi api;
    private final BufferedReader input;
    private final BufferedWriter output;
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore concurrency = new Semaphore(MAX_CONCURRENT_REQUESTS);
    private final Map<String, RequestControl> active = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);

    public LocalAppProtocolServer(ObjectMapper mapper, LocalAppApi api, Reader input, Writer output) {
        this.mapper = mapper;
        this.api = api;
        this.input = new BufferedReader(input);
        this.output = new BufferedWriter(output);
    }

    public void run() throws IOException {
        String line;
        while (running.get() && (line = input.readLine()) != null) {
            if (line.length() > MAX_MESSAGE_CHARS) {
                writeError("", "PAYLOAD_TOO_LARGE", "IPC request exceeds the one MiB character limit", false);
                continue;
            }
            accept(line);
        }
        waitForActiveRequests();
    }

    private void accept(String line) {
        JsonNode request;
        try {
            request = mapper.readTree(line);
        } catch (JsonProcessingException error) {
            writeError("", "INVALID_JSON", "IPC request is not valid JSON", false);
            return;
        }
        String requestId = text(request, "requestId");
        String method = text(request, "method");
        String contractVersion = text(request, "contractVersion");
        if (requestId.isBlank() || method.isBlank() || contractVersion.isBlank()) {
            writeError(requestId, "INVALID_REQUEST", "requestId, method, and contractVersion are required", false);
            return;
        }
        if (!DefaultLocalAppApi.CONTRACT_VERSION.equals(contractVersion)) {
            writeError(requestId, "CONTRACT_VERSION_UNSUPPORTED", "Unsupported IPC contract version", false);
            return;
        }
        if (RESERVED_METHODS.contains(method)) {
            handleReserved(requestId, method, request.path("params"));
            return;
        }
        if (active.containsKey(requestId)) {
            writeError(requestId, "DUPLICATE_REQUEST", "requestId is already active", false);
            return;
        }
        if (!concurrency.tryAcquire()) {
            writeError(requestId, "BUSY", "Local application request limit reached", true);
            return;
        }
        AtomicBoolean cancelled = new AtomicBoolean(false);
        RequestControl control = new RequestControl(cancelled);
        active.put(requestId, control);
        Future<?> future = requests.submit(() -> execute(requestId, method, request.path("params"), control));
        control.future = future;
    }

    private void execute(String requestId, String method, JsonNode params, RequestControl control) {
        try {
            JsonNode result = api.invoke(method, params, control.cancelled::get,
                event -> writeEvent(requestId, event));
            writeResult(requestId, result);
        } catch (LocalAppCancelledException | InterruptedException error) {
            Thread.currentThread().interrupt();
            writeError(requestId, "CANCELLED", "Local application request was cancelled", false);
        } catch (IllegalArgumentException error) {
            writeError(requestId, "INVALID_METHOD_OR_PARAMS", safeMessage(error), false);
        } catch (Exception error) {
            writeError(requestId, "LOCAL_APP_FAILURE", "Local application operation failed", true);
        } finally {
            active.remove(requestId);
            concurrency.release();
        }
    }

    private void handleReserved(String requestId, String method, JsonNode params) {
        if ("system.cancel".equals(method)) {
            String targetRequestId = text(params, "targetRequestId");
            RequestControl target = active.get(targetRequestId);
            boolean cancelled = target != null;
            if (target != null) {
                target.cancelled.set(true);
            }
            writeResult(requestId, mapper.createObjectNode().put("cancelled", cancelled)
                .put("targetRequestId", targetRequestId));
            return;
        }
        running.set(false);
        writeResult(requestId, mapper.createObjectNode().put("shuttingDown", true));
    }

    private void writeResult(String requestId, JsonNode result) {
        ObjectNode response = baseMessage(requestId, "response");
        response.set("result", result == null ? mapper.nullNode() : result);
        write(response);
    }

    private void writeEvent(String requestId, LocalAppEvent event) {
        ObjectNode response = baseMessage(requestId, "event");
        response.put("event", event.type());
        response.set("payload", event.payload());
        write(response);
    }

    private void writeError(String requestId, String code, String message, boolean retryable) {
        ObjectNode response = baseMessage(requestId, "response");
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        error.put("retryable", retryable);
        write(response);
    }

    private ObjectNode baseMessage(String requestId, String type) {
        ObjectNode response = mapper.createObjectNode();
        response.put("type", type);
        response.put("requestId", requestId);
        response.put("contractVersion", DefaultLocalAppApi.CONTRACT_VERSION);
        return response;
    }

    private synchronized void write(JsonNode response) {
        try {
            output.write(mapper.writeValueAsString(response));
            output.newLine();
            output.flush();
        } catch (IOException error) {
            running.set(false);
            throw new IllegalStateException("Unable to write IPC response", error);
        }
    }

    private void waitForActiveRequests() {
        active.values().forEach(control -> {
            Future<?> future = control.future;
            if (future == null) return;
            try {
                future.get();
            } catch (Exception ignored) {
                // The request has already emitted its structured result or cancellation response.
            }
        });
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) return "";
        return node.path(field).asText("").trim();
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "Invalid local application request" : message;
    }

    @Override
    public void close() {
        running.set(false);
        active.values().forEach(control -> {
            control.cancelled.set(true);
            if (control.future != null) control.future.cancel(true);
        });
        requests.shutdownNow();
        api.close();
    }

    private static final class RequestControl {
        private final AtomicBoolean cancelled;
        private volatile Future<?> future;

        private RequestControl(AtomicBoolean cancelled) {
            this.cancelled = cancelled;
        }
    }
}
