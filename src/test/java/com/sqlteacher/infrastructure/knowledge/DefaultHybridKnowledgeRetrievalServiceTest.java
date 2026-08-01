package com.sqlteacher.infrastructure.knowledge;

import com.sqlteacher.application.knowledge.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultHybridKnowledgeRetrievalServiceTest {
    @Test
    void fusesKeywordAndVectorHitsAndKeepsAuthorizationFilter() {
        CourseKnowledgeService lexical = new StubCourseKnowledgeService();
        EmbeddingProvider embeddings = texts -> new EmbeddingProvider.EmbeddingBatch("test", "model", List.of(new float[]{1, 0}));
        KnowledgeChunkRecord chunk = new KnowledgeChunkRecord("c", "doc", "a", "r", 1, 0, "SELECT",
            "SQL", "查询", List.of("SELECT"), "查询", "SELECT reads rows", KnowledgeVisibility.PUBLISHED, "teacher");
        KnowledgeVectorStore vectors = new KnowledgeVectorStore() {
            public void replaceRevision(String id, List<KnowledgeChunkRecord> c, List<float[]> v) { }
            public List<VectorSearchHit> search(float[] q, CourseKnowledgeSearchFilter f, String o, int l) { return List.of(new VectorSearchHit(chunk, .9)); }
            public void deleteArticle(String id) { } public void clear() { }
        };
        var response = new DefaultHybridKnowledgeRetrievalService(lexical, embeddings, vectors, () -> "student")
            .retrieve("SELECT", CourseKnowledgeSearchFilter.published(), 5);
        assertEquals("HYBRID_RRF", response.mode());
        assertEquals(1, response.results().size());
        assertTrue(response.results().getFirst().relevance() > 0);
    }

    @Test
    void degradesToFtsWhenEmbeddingFails() {
        EmbeddingProvider unavailable = texts -> { throw new IllegalStateException("offline"); };
        var response = new DefaultHybridKnowledgeRetrievalService(new StubCourseKnowledgeService(), unavailable,
            new EmptyVectors(), () -> "guest").retrieve("SELECT", CourseKnowledgeSearchFilter.published(), 5);
        assertTrue(response.degraded()); assertEquals("FTS5", response.mode()); assertEquals(1, response.results().size());
    }

    private static final class EmptyVectors implements KnowledgeVectorStore {
        public void replaceRevision(String id, List<KnowledgeChunkRecord> c, List<float[]> v) { }
        public List<VectorSearchHit> search(float[] q, CourseKnowledgeSearchFilter f, String o, int l) { return List.of(); }
        public void deleteArticle(String id) { } public void clear() { }
    }
    private static final class StubCourseKnowledgeService implements CourseKnowledgeService {
        public CourseKnowledgeArticle importArticle(java.nio.file.Path p,String c,String s,List<String> k){throw new UnsupportedOperationException();}
        public List<CourseKnowledgeArticle> listArticles(){return List.of(new CourseKnowledgeArticle("a","doc","SQL","查询","SELECT",KnowledgeVisibility.PUBLISHED,1,List.of("SELECT"),"hash", Instant.now()));}
        public CourseKnowledgeDetail getArticle(String id){throw new UnsupportedOperationException();}
        public CourseKnowledgeArticle reviseArticle(String id,java.nio.file.Path p,List<String> k){throw new UnsupportedOperationException();}
        public CourseKnowledgeArticle changeVisibility(String id,KnowledgeVisibility v){throw new UnsupportedOperationException();}
        public List<KnowledgeSearchResult> search(String q,CourseKnowledgeSearchFilter f,int l){return List.of(new KnowledgeSearchResult("doc","SELECT","local",0,"SELECT reads rows",1));}
    }
}
