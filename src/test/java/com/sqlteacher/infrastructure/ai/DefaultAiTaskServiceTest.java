package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.application.ai.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DefaultAiTaskServiceTest {
    @Test void shouldRetryOneTransientFailureAndRecordMetadataWithoutBody() {
        AtomicInteger calls = new AtomicInteger();
        AiModelProvider provider = request -> calls.incrementAndGet() == 1
            ? AiCompletionResult.failure("timed out", "model-a")
            : AiCompletionResult.success("{\"sqlDraft\":\"SELECT 1\"}", "model-a");
        RecordingHistory history = new RecordingHistory();
        DefaultAiTaskService service = new DefaultAiTaskService(provider,
            new AiUsagePolicy(1_000, 1_000, 10, Duration.ofSeconds(2)), history);

        AiTaskResult result = service.execute(request("prompt"));

        assertTrue(result.success());
        assertEquals(2, calls.get());
        assertEquals(1, history.entries.size());
        assertEquals("", history.entries.get(0).savedDraft());
    }

    @Test void shouldRejectMalformedStructuredOutput() {
        AiModelProvider provider = request -> AiCompletionResult.success("not-json", "model-a");
        DefaultAiTaskService service = new DefaultAiTaskService(provider,
            new AiUsagePolicy(1_000, 1_000, 10, Duration.ofSeconds(2)), new RecordingHistory());
        AiTaskResult result = service.execute(request("prompt"));
        assertFalse(result.success());
        assertEquals(AiTaskErrorCode.MALFORMED_OUTPUT, result.errorCode());
    }

    private static AiTaskRequest request(String prompt) {
        return new AiTaskRequest(AiTaskType.NL2SQL, "model-a", prompt, "test-v1",
            new AiContextPreview(AiTaskType.NL2SQL, Set.of(AiContextCategory.USER_REQUEST),
                List.of("test"), prompt.length(), List.of()));
    }

    private static final class RecordingHistory implements AiTaskHistoryService {
        private final List<AiTaskHistoryEntry> entries = new ArrayList<>();
        @Override public List<AiTaskHistoryEntry> recent() { return List.copyOf(entries); }
        @Override public void record(AiTaskHistoryEntry entry) { entries.add(entry); }
        @Override public void favorite(String id, boolean favorite, String draftContent) { }
        @Override public int requestsToday() { return entries.size(); }
    }
}
