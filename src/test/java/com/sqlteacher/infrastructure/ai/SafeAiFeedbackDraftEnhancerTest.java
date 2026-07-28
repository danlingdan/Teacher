package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.application.ai.AiCompletionResult;
import com.sqlteacher.application.ai.AiModelProvider;
import com.sqlteacher.application.collaboration.FeedbackDraft;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeAiFeedbackDraftEnhancerTest {
    private static final FeedbackDraft BASE = new FeedbackDraft(
        "确定性模板", List.of("确定性结果：FAILED", "错误码：FILTER_MISMATCH"), false);

    @Test
    void shouldUseConfiguredProviderWithoutChangingEvidenceBoundary() {
        AiModelProvider provider = new AiModelProvider() {
            @Override public AiCompletionResult complete(com.sqlteacher.application.ai.AiCompletionRequest request) {
                assertTrue(request.prompt().contains("FILTER_MISMATCH"));
                assertFalse(request.prompt().contains("password"));
                return AiCompletionResult.success("请重新核对 WHERE 条件，再运行一次。", "network-model");
            }
            @Override public String preferredModel() { return "network-model"; }
        };

        FeedbackDraft enhanced = new SafeAiFeedbackDraftEnhancer(provider).enhance(BASE);

        assertTrue(enhanced.aiGenerated());
        assertEquals("请重新核对 WHERE 条件，再运行一次。", enhanced.text());
        assertTrue(enhanced.evidence().contains("AI model: network-model"));
    }

    @Test
    void shouldFallBackWhenNetworkProviderIsUnavailable() {
        AiModelProvider provider = new AiModelProvider() {
            @Override public AiCompletionResult complete(com.sqlteacher.application.ai.AiCompletionRequest request) {
                throw new IllegalStateException("offline");
            }
            @Override public String preferredModel() { return "network-model"; }
        };

        assertEquals(BASE, new SafeAiFeedbackDraftEnhancer(provider).enhance(BASE));
    }

    @Test
    void shouldNotInvokeLocalProviderWithoutExplicitNetworkModel() {
        AiModelProvider provider = request -> {
            throw new AssertionError("provider must not be called");
        };

        assertEquals(BASE, new SafeAiFeedbackDraftEnhancer(provider).enhance(BASE));
    }
}
