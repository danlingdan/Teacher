package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.domain.SqlTeacherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LocalAppProtocolServer implements AutoCloseable {
    static final int MAX_MESSAGE_BYTES = LocalAppContract.MAX_MESSAGE_BYTES;
    static final int MAX_CONCURRENT_REQUESTS = LocalAppContract.MAX_CONCURRENT_REQUESTS;
    private static final Logger log = LoggerFactory.getLogger(LocalAppProtocolServer.class);
    private static final Set<String> REQUEST_FIELDS = Set.of("requestId", "method", "params", "contractVersion");
    private static final long DRAIN_TIMEOUT_MILLIS = LocalAppContract.REQUEST_TIMEOUT_MILLIS + 10_000;

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
        Line line;
        while (running.get() && (line = readLineBounded()) != null) {
            if (line.tooLarge()) {
                writeError("", LocalAppErrorCode.PAYLOAD_TOO_LARGE,
                    "IPC request exceeds the one MiB UTF-8 limit", false);
                continue;
            }
            if (line.text().isEmpty()) continue;
            accept(line.text());
        }
        waitForActiveRequests();
    }

    /** 有界读行：超长的行在读取过程中即被拒绝，不会先整行物化成字符串再检查。 */
    private Line readLineBounded() throws IOException {
        StringBuilder line = new StringBuilder(512);
        long bytes = 0;
        int read;
        while ((read = input.read()) >= 0) {
            char current = (char) read;
            if (current == '\n' || current == '\r') return new Line(line.toString(), false);
            bytes += current < 0x80 ? 1 : current < 0x800 ? 2 : 3;
            if (bytes > MAX_MESSAGE_BYTES) {
                while ((read = input.read()) >= 0 && read != '\n' && read != '\r') {
                    // 丢弃剩余内容，让下一条消息仍能被解析。
                }
                return new Line("", true);
            }
            line.append(current);
        }
        return line.isEmpty() ? null : new Line(line.toString(), false);
    }

    private record Line(String text, boolean tooLarge) { }

    private void accept(String line) {
        JsonNode request;
        try {
            request = mapper.readTree(line);
        } catch (JsonProcessingException error) {
            writeError("", LocalAppErrorCode.INVALID_JSON, "IPC request is not valid JSON", false);
            return;
        }
        String requestId = text(request, "requestId");
        String method = text(request, "method");
        String contractVersion = text(request, "contractVersion");
        if (!validEnvelope(request, requestId, method, contractVersion)) {
            writeError(requestId, LocalAppErrorCode.INVALID_REQUEST,
                "Request must match the frozen v1 envelope", false);
            return;
        }
        if (!LocalAppContract.VERSION.equals(contractVersion)) {
            writeError(requestId, LocalAppErrorCode.CONTRACT_VERSION_UNSUPPORTED,
                "Unsupported IPC contract version", false);
            return;
        }
        if (LocalAppContract.RESERVED_METHODS.contains(method)) {
            handleReserved(requestId, method, request.path("params"));
            return;
        }
        if (active.containsKey(requestId)) {
            writeError(requestId, LocalAppErrorCode.DUPLICATE_REQUEST, "requestId is already active", false);
            return;
        }
        if (!concurrency.tryAcquire()) {
            writeError(requestId, LocalAppErrorCode.BUSY, "Local application request limit reached", true);
            return;
        }
        AtomicBoolean cancelled = new AtomicBoolean(false);
        RequestControl control = new RequestControl(cancelled);
        active.put(requestId, control);
        Future<?> future = requests.submit(() -> execute(requestId, method, request.path("params"), control));
        control.future = future;
    }

    private void execute(String requestId, String method, JsonNode params, RequestControl control) {
        control.worker = Thread.currentThread();
        try {
            JsonNode result = api.invoke(method, params, control.cancelled::get,
                event -> writeEvent(requestId, event));
            writeResult(requestId, result);
        } catch (LocalAppCancelledException | InterruptedException error) {
            Thread.currentThread().interrupt();
            writeError(requestId, LocalAppErrorCode.CANCELLED, "Local application request was cancelled", false);
        } catch (IllegalArgumentException error) {
            writeError(requestId, LocalAppErrorCode.INVALID_METHOD_OR_PARAMS, safeMessage(error), false);
        } catch (SqlTeacherException error) {
            // 领域异常携带结构化错误码：保留给前端做失败分类，同时记录堆栈供诊断。
            log.error("IPC request failed: requestId={}, method={}, code={}",
                requestId, method, error.errorCode(), error);
            writeError(requestId, error.errorCode(), safeMessage(error), true);
        } catch (SecurityException error) {
            log.warn("IPC request rejected: requestId={}, method={}", requestId, method, error);
            writeError(requestId, "SECURITY_REJECTED", safeMessage(error), false);
        } catch (Exception error) {
            log.error("IPC request failed: requestId={}, method={}", requestId, method, error);
            writeError(requestId, LocalAppErrorCode.LOCAL_APP_FAILURE,
                "Local application operation failed", true);
        } finally {
            control.worker = null;
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
                Thread worker = target.worker;
                if (worker != null) worker.interrupt();
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
        if (!LocalAppContract.EVENT_TYPES.contains(event.type())) {
            throw new IllegalArgumentException("Unknown local application event type: " + event.type());
        }
        ObjectNode response = baseMessage(requestId, "event");
        response.put("event", event.type());
        response.set("payload", event.payload());
        write(response);
    }

    private void writeError(String requestId, LocalAppErrorCode code, String message, boolean retryable) {
        writeError(requestId, code.name(), message, retryable);
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
        response.put("contractVersion", LocalAppContract.VERSION);
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
        // 排空在途请求，但设置上限：卡死的 JDBC/网络任务不能拖住进程退出。
        for (Map.Entry<String, RequestControl> entry : active.entrySet()) {
            Future<?> future = entry.getValue().future;
            if (future == null) continue;
            try {
                future.get(DRAIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException error) {
                log.warn("IPC drain timed out for requestId={}, cancelling", entry.getKey());
                future.cancel(true);
            } catch (Exception ignored) {
                // The request has already emitted its structured result or cancellation response.
            }
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) return "";
        return node.path(field).asText("").trim();
    }

    private static boolean validEnvelope(JsonNode request, String requestId, String method, String contractVersion) {
        if (!request.isObject() || requestId.isBlank() || requestId.length() > 128
            || method.isBlank() || method.length() > 128 || contractVersion.isBlank()
            || !request.path("params").isObject()) {
            return false;
        }
        var fields = request.fieldNames();
        while (fields.hasNext()) {
            if (!REQUEST_FIELDS.contains(fields.next())) return false;
        }
        return true;
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
        private volatile Thread worker;

        private RequestControl(AtomicBoolean cancelled) {
            this.cancelled = cancelled;
        }
    }
}
