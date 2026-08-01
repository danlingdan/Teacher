package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.application.ai.AiTaskErrorCode;
import com.sqlteacher.application.ai.AiTaskRequest;
import com.sqlteacher.application.ai.AiTaskResult;
import com.sqlteacher.application.ai.AiTaskService;
import com.sqlteacher.application.knowledge.CourseKnowledgeArticle;
import com.sqlteacher.application.knowledge.CourseKnowledgeDetail;
import com.sqlteacher.application.knowledge.CourseKnowledgeSearchFilter;
import com.sqlteacher.application.knowledge.CourseKnowledgeService;
import com.sqlteacher.application.knowledge.KnowledgeSearchResult;
import com.sqlteacher.application.knowledge.KnowledgeVisibility;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultGroundedKnowledgeExplanationServiceTest {
    private static final CourseKnowledgeArticle ARTICLE = new CourseKnowledgeArticle(
        "article-1", "document-1", "SQL", "聚合", "分组过滤", KnowledgeVisibility.PUBLISHED,
        2, List.of("HAVING"), "hash", Instant.parse("2026-08-01T00:00:00Z")
    );

    @Test
    void shouldAcceptOnlyCitationsFromThePreparedKnowledgeSet() {
        var service = service(AiTaskResult.success("{\"answer\":\"HAVING 用于分组后过滤。\",\"citations\":[1,99]}", "test-model"));

        var answer = service.explain("HAVING 有什么作用？", CourseKnowledgeSearchFilter.published());

        assertTrue(answer.aiGenerated());
        assertEquals("HAVING 用于分组后过滤。", answer.answer());
        assertEquals(1, answer.citations().size());
        assertEquals(2, answer.citations().get(0).revision());
    }

    @Test
    void shouldFallBackToTraceableSnippetsWhenProviderFails() {
        var service = service(AiTaskResult.failure(AiTaskErrorCode.PROVIDER_UNAVAILABLE, "offline", "test-model"));

        var answer = service.explain("HAVING 有什么作用？", CourseKnowledgeSearchFilter.published());

        assertFalse(answer.aiGenerated());
        assertTrue(answer.answer().contains("[1]"));
        assertEquals("offline", answer.message());
    }

    private static DefaultGroundedKnowledgeExplanationService service(AiTaskResult result) {
        AiTaskService ai = new AiTaskService() {
            @Override public AiTaskResult execute(AiTaskRequest request) { return result; }
            @Override public String preferredModel() { return "test-model"; }
        };
        return new DefaultGroundedKnowledgeExplanationService(new StubKnowledgeService(), ai, new DefaultAiContextPolicy());
    }

    private static final class StubKnowledgeService implements CourseKnowledgeService {
        @Override public CourseKnowledgeArticle importArticle(Path path, String courseTitle, String sectionTitle, List<String> knowledgePoints) { throw new UnsupportedOperationException(); }
        @Override public List<CourseKnowledgeArticle> listArticles() { return List.of(ARTICLE); }
        @Override public CourseKnowledgeDetail getArticle(String articleId) { throw new UnsupportedOperationException(); }
        @Override public CourseKnowledgeArticle reviseArticle(String articleId, Path path, List<String> knowledgePoints) { throw new UnsupportedOperationException(); }
        @Override public CourseKnowledgeArticle changeVisibility(String articleId, KnowledgeVisibility visibility) { throw new UnsupportedOperationException(); }
        @Override public List<KnowledgeSearchResult> search(String query, CourseKnowledgeSearchFilter filter, int limit) {
            return List.of(new KnowledgeSearchResult("document-1", "分组过滤", "having.md", 0,
                "HAVING 用于过滤分组后的结果。", 1.0));
        }
    }
}
