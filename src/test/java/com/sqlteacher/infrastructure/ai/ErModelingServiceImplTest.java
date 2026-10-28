package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.application.ai.AiCompletionRequest;
import com.sqlteacher.application.ai.AiCompletionResult;
import com.sqlteacher.application.ai.AiModelProvider;
import com.sqlteacher.application.ai.AiModelSelection;
import com.sqlteacher.application.ai.AiModelSelectionService;
import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.ermodel.ErGenerationStatus;
import com.sqlteacher.application.ermodel.ErModelingRequest;
import com.sqlteacher.application.ermodel.ErModelingResult;
import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.risk.SqlRiskLevel;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.11.0 ERM-3: fail-safe E-R modeling service behavior with a hand-written mock provider:
 * lenient parsing (bare JSON and prose-wrapped JSON), the single repair retry on PARSE_ERROR and
 * VALIDATION_FAILED, MODEL_UNAVAILABLE without any provider call, provider failures, control
 * character normalization, untrusted requirement injection, and learning event recording.
 */
class ErModelingServiceImplTest {

    private static final AiConfiguration CONFIG = new AiConfiguration(
        URI.create("http://localhost:11434"),
        Duration.ofMillis(2000),
        Duration.ofMillis(30000),
        "test-model"
    );

    private static final String VALID_JSON =
        "{\"title\":\"学生选课系统\",\"explanation\":\"学生实体与课程实体\",\"entities\":"
            + "[{\"id\":\"student\",\"name\":\"学生\",\"weak\":false,\"attributes\":"
            + "[{\"name\":\"学号\",\"key\":true,\"composite\":[],\"multivalued\":false,\"derived\":false}]}],"
            + "\"relationships\":[],\"hierarchies\":[]}";

    /** Cardinality "N" is outside the lowercase whitelist, so deterministic validation fails. */
    private static final String INVALID_CARDINALITY_JSON =
        "{\"title\":\"学生选课系统\",\"explanation\":\"学生选修课程\",\"entities\":"
            + "[{\"id\":\"student\",\"name\":\"学生\",\"weak\":false,\"attributes\":[]},"
            + "{\"id\":\"course\",\"name\":\"课程\",\"weak\":false,\"attributes\":[]}],"
            + "\"relationships\":[{\"id\":\"elective\",\"name\":\"选修\",\"identifying\":false,"
            + "\"members\":[{\"entity\":\"student\",\"cardinality\":\"N\",\"total\":false},"
            + "{\"entity\":\"course\",\"cardinality\":\"n\",\"total\":false}],\"attributes\":[]}],"
            + "\"hierarchies\":[]}";

    @Test
    void shouldParseAValidModelIntoAnOkResult() {
        SequencedProvider provider = new SequencedProvider(List.of(AiCompletionResult.success(VALID_JSON, "test-model")));
        RecordingEvents events = new RecordingEvents();
        ErModelingServiceImpl service = new ErModelingServiceImpl(provider, CONFIG, events);

        ErModelingResult result = service.generate(new ErModelingRequest("设计一个学生选课系统"));

        assertEquals(ErGenerationStatus.OK, result.status());
        assertEquals("学生选课系统", result.erModel().title());
        assertEquals(1, result.erModel().entities().size());
        assertEquals("学生", result.erModel().entities().get(0).name());
        assertTrue(result.erModel().entities().get(0).attributes().get(0).key());
        assertEquals("test-model", result.model());
        assertEquals("er-modeling-v1", result.promptVersion());
        assertTrue(result.errors().isEmpty());
        assertTrue(result.message().contains("1 个实体"), result.message());
        assertEquals(1, provider.calls);
        assertTrue(provider.lastRequest.prompt().contains("设计一个学生选课系统"));
        assertTrue(provider.lastRequest.prompt().contains("不可信素材"));
    }

    @Test
    void shouldParseJsonWrappedInProseAndFences() {
        String wrapped = "好的，以下是建模结果：\n```json\n" + VALID_JSON + "\n```\n希望有帮助。";
        SequencedProvider provider = new SequencedProvider(List.of(AiCompletionResult.success(wrapped, "test-model")));
        ErModelingServiceImpl service = new ErModelingServiceImpl(provider, CONFIG, new RecordingEvents());

        ErModelingResult result = service.generate(new ErModelingRequest("设计一个学生选课系统"));

        assertEquals(ErGenerationStatus.OK, result.status());
        assertEquals("学生选课系统", result.erModel().title());
        assertEquals(1, provider.calls);
    }

    @Test
    void shouldReturnParseErrorAfterOneRetryWhenOutputIsNeverJson() {
        SequencedProvider provider = new SequencedProvider(List.of(
            AiCompletionResult.success("not json at all", "test-model"),
            AiCompletionResult.success("still not json", "test-model")));
        ErModelingServiceImpl service = new ErModelingServiceImpl(provider, CONFIG, new RecordingEvents());

        ErModelingResult result = service.generate(new ErModelingRequest("设计一个学生选课系统"));

        assertEquals(ErGenerationStatus.PARSE_ERROR, result.status());
        assertNull(result.erModel());
        assertTrue(result.message().contains("JSON"), result.message());
        assertEquals(2, provider.calls, "exactly one repair retry is allowed");
        assertTrue(provider.lastRequest.prompt().contains("校验错误列表"));
    }

    @Test
    void shouldRetryOnceWithFedBackErrorsAndThenSucceed() {
        SequencedProvider provider = new SequencedProvider(List.of(
            AiCompletionResult.success(INVALID_CARDINALITY_JSON, "test-model"),
            AiCompletionResult.success(VALID_JSON, "test-model")));
        ErModelingServiceImpl service = new ErModelingServiceImpl(provider, CONFIG, new RecordingEvents());

        ErModelingResult result = service.generate(new ErModelingRequest("设计一个学生选课系统"));

        assertEquals(ErGenerationStatus.OK, result.status());
        assertEquals("学生选课系统", result.erModel().title());
        assertEquals(2, provider.calls);
        // The repair retry must carry the aggregated deterministic validation errors.
        assertTrue(provider.lastRequest.prompt().contains("校验错误列表"));
        assertTrue(provider.lastRequest.prompt().contains("成员基数必须是 1、n、m 之一"),
            provider.lastRequest.prompt());
    }

    @Test
    void shouldReturnValidationFailedWithAllErrorsWhenTheRetryAlsoFails() {
        SequencedProvider provider = new SequencedProvider(List.of(
            AiCompletionResult.success(INVALID_CARDINALITY_JSON, "test-model"),
            AiCompletionResult.success(INVALID_CARDINALITY_JSON, "test-model")));
        RecordingEvents events = new RecordingEvents();
        ErModelingServiceImpl service = new ErModelingServiceImpl(provider, CONFIG, events);

        ErModelingResult result = service.generate(new ErModelingRequest("设计一个学生选课系统"));

        assertEquals(ErGenerationStatus.VALIDATION_FAILED, result.status());
        assertNull(result.erModel());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("成员基数")), result.errors().toString());
        assertTrue(result.message().contains("未通过校验"), result.message());
        assertEquals(2, provider.calls);
        assertEquals(1, events.records.size());
        EventRecord record = events.records.get(0);
        assertEquals(false, record.successful());
        assertEquals("VALIDATION_FAILED", record.errorCode());
        assertEquals("er-modeling-v1", record.promptVersion());
    }

    @Test
    void mustNotCallTheProviderWhenNoModelIsSelected() {
        AiModelSelectionService emptySelection = new AiModelSelectionService() {
            @Override
            public AiModelSelection refresh() {
                return new AiModelSelection(List.of(), "", "Ollama is running, but no local model is installed");
            }

            @Override
            public AiModelSelection current() {
                return new AiModelSelection(List.of(), "", "Models have not been detected");
            }

            @Override
            public AiModelSelection select(String model) {
                throw new IllegalArgumentException("model is not installed: " + model);
            }
        };
        SequencedProvider provider = new SequencedProvider(List.of());
        RecordingEvents events = new RecordingEvents();
        ErModelingServiceImpl service = new ErModelingServiceImpl(
            provider, CONFIG, emptySelection, events);

        ErModelingResult result = service.generate(new ErModelingRequest("设计一个学生选课系统"));

        assertEquals(ErGenerationStatus.MODEL_UNAVAILABLE, result.status());
        assertNull(result.erModel());
        assertTrue(result.message().contains("AI 模型"), result.message());
        assertEquals(0, provider.calls, "an unavailable model must never consume a provider call");
        assertEquals(1, events.records.size());
        assertEquals("MODEL_UNAVAILABLE", events.records.get(0).errorCode());
    }

    @Test
    void shouldReturnProviderErrorWithoutRetryWhenTheProviderThrows() {
        SequencedProvider provider = SequencedProvider.throwing(new IllegalStateException("connection refused"));
        ErModelingServiceImpl service = new ErModelingServiceImpl(provider, CONFIG, new RecordingEvents());

        ErModelingResult result = service.generate(new ErModelingRequest("设计一个学生选课系统"));

        assertEquals(ErGenerationStatus.PROVIDER_ERROR, result.status());
        assertNull(result.erModel());
        assertEquals(1, provider.calls, "provider failures are terminal and never retried");
    }

    @Test
    void shouldReturnProviderErrorWhenTheProviderReturnsAFailure() {
        SequencedProvider provider = new SequencedProvider(List.of(
            AiCompletionResult.failure("Ollama unavailable", "test-model")));
        ErModelingServiceImpl service = new ErModelingServiceImpl(provider, CONFIG, new RecordingEvents());

        ErModelingResult result = service.generate(new ErModelingRequest("设计一个学生选课系统"));

        assertEquals(ErGenerationStatus.PROVIDER_ERROR, result.status());
        assertNull(result.erModel());
        assertTrue(result.message().contains("Ollama unavailable"), result.message());
        assertEquals(1, provider.calls);
    }

    @Test
    void shouldNormalizeControlCharactersOutOfTheModelText() {
        String dirty = "{\"title\":\"学\\u0001生选课\",\"explanation\":\"说\\n明\",\"entities\":"
            + "[{\"id\":\"student\",\"name\":\"学\\t生\",\"weak\":false,\"attributes\":"
            + "[{\"name\":\"学\\n号\",\"key\":true,\"composite\":[],\"multivalued\":false,\"derived\":false}]}],"
            + "\"relationships\":[],\"hierarchies\":[]}";
        SequencedProvider provider = new SequencedProvider(List.of(AiCompletionResult.success(dirty, "test-model")));
        ErModelingServiceImpl service = new ErModelingServiceImpl(provider, CONFIG, new RecordingEvents());

        ErModelingResult result = service.generate(new ErModelingRequest("设计一个学生选课系统"));

        assertEquals(ErGenerationStatus.OK, result.status());
        assertEquals("学生选课", result.erModel().title());
        assertEquals("学生", result.erModel().entities().get(0).name());
        assertEquals("学号", result.erModel().entities().get(0).attributes().get(0).name());
    }

    @Test
    void mustRejectBlankOrOversizedRequirements() {
        ErModelingServiceImpl service = new ErModelingServiceImpl(
            new SequencedProvider(List.of()), CONFIG, new RecordingEvents());

        assertThrows(IllegalArgumentException.class, () -> service.generate(new ErModelingRequest("   ")));
        assertThrows(IllegalArgumentException.class,
            () -> service.generate(new ErModelingRequest("需".repeat(2001))));
    }

    @Test
    void shouldRecordASuccessfulLearningEventWithoutRequirementText() {
        SequencedProvider provider = new SequencedProvider(List.of(AiCompletionResult.success(VALID_JSON, "test-model")));
        RecordingEvents events = new RecordingEvents();
        ErModelingServiceImpl service = new ErModelingServiceImpl(provider, CONFIG, events);

        service.generate(new ErModelingRequest("设计一个学生选课系统"));

        assertEquals(1, events.records.size());
        EventRecord record = events.records.get(0);
        assertTrue(record.successful());
        assertNull(record.errorCode());
        assertEquals("test-model", record.model());
        assertEquals("er-modeling-v1", record.promptVersion());
    }

    /** Queue-based provider: every call consumes the next result; extra calls fail the test. */
    private static final class SequencedProvider implements AiModelProvider {
        private final List<AiCompletionResult> results;
        private final boolean throwOnComplete;
        private int calls;
        private AiCompletionRequest lastRequest;

        SequencedProvider(List<AiCompletionResult> results) {
            this.results = results;
            this.throwOnComplete = false;
        }

        private SequencedProvider(boolean throwOnComplete) {
            this.results = List.of();
            this.throwOnComplete = throwOnComplete;
        }

        static SequencedProvider throwing(RuntimeException ignored) {
            return new SequencedProvider(true);
        }

        @Override
        public AiCompletionResult complete(AiCompletionRequest request) {
            lastRequest = request;
            calls++;
            if (throwOnComplete) {
                throw new IllegalStateException("connection refused");
            }
            if (calls > results.size()) {
                throw new AssertionError("unexpected extra provider call");
            }
            return results.get(calls - 1);
        }

        @Override
        public String preferredModel() {
            return "";
        }
    }

    private record EventRecord(
        boolean successful,
        String model,
        String promptVersion,
        String errorCode
    ) { }

    private static final class RecordingEvents implements LearningEventService {
        private final List<EventRecord> records = new ArrayList<>();

        @Override
        public void recordSqlExecution(String connectionId, boolean successful, String statementType,
                                       Duration duration, int resultCount, String errorCode) {
            throw new UnsupportedOperationException("ER modeling must not record SQL execution");
        }

        @Override
        public void recordSqlRiskBlocked(String connectionId, String statementType,
                                         SqlRiskLevel riskLevel, boolean multiStatement) {
            throw new UnsupportedOperationException("ER modeling must not record SQL risk events");
        }

        @Override
        public void recordAiGeneration(String connectionId, boolean successful, String model,
                                       String promptVersion, String errorCode) {
            records.add(new EventRecord(successful, model, promptVersion, errorCode));
        }
    }
}
