package com.sqlteacher.infrastructure.knowledge;

import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.application.knowledge.CourseKnowledgeSearchFilter;
import com.sqlteacher.application.knowledge.CourseKnowledgeService;
import com.sqlteacher.application.knowledge.EmbeddingProvider;
import com.sqlteacher.application.knowledge.HybridKnowledgeRetrievalService;
import com.sqlteacher.application.knowledge.KnowledgeSearchResult;
import com.sqlteacher.application.knowledge.KnowledgeVectorStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DefaultHybridKnowledgeRetrievalService implements HybridKnowledgeRetrievalService {
    private static final double RRF_K = 60.0;
    private final CourseKnowledgeService lexical;
    private final EmbeddingProvider embeddings;
    private final KnowledgeVectorStore vectors;
    private final LearningEventOwnerProvider ownerProvider;

    public DefaultHybridKnowledgeRetrievalService(CourseKnowledgeService lexical, EmbeddingProvider embeddings,
                                                   KnowledgeVectorStore vectors, LearningEventOwnerProvider ownerProvider) {
        this.lexical = lexical;
        this.embeddings = embeddings;
        this.vectors = vectors;
        this.ownerProvider = ownerProvider;
    }

    @Override
    public RetrievalResponse retrieve(String query, CourseKnowledgeSearchFilter requestedFilter, int limit) {
        if (query == null || query.isBlank() || limit < 1 || limit > 50) throw new IllegalArgumentException("invalid retrieval request");
        CourseKnowledgeSearchFilter filter = requestedFilter == null ? CourseKnowledgeSearchFilter.published() : requestedFilter;
        List<KnowledgeSearchResult> keyword = lexical.search(query, filter, Math.min(50, Math.max(limit * 4, limit)));
        Map<String, RankedResult> fused = new LinkedHashMap<>();
        for (int rank = 0; rank < keyword.size(); rank++) add(fused, keyword.get(rank), 1.0 / (RRF_K + rank + 1));
        try {
            float[] queryVector = embeddings.embed(List.of(query.trim())).vectors().getFirst();
            List<KnowledgeVectorStore.VectorSearchHit> semantic = vectors.search(queryVector, filter, ownerProvider.currentOwnerId(),
                Math.min(50, Math.max(limit * 4, limit)));
            for (int rank = 0; rank < semantic.size(); rank++) {
                var chunk = semantic.get(rank).chunk();
                KnowledgeSearchResult result = new KnowledgeSearchResult(chunk.documentId(), chunk.title(),
                    chunk.headingPath().isBlank() ? "本地向量索引" : chunk.headingPath(), chunk.chunkIndex(),
                    snippet(chunk.content()), semantic.get(rank).score());
                add(fused, result, 1.0 / (RRF_K + rank + 1));
            }
            return new RetrievalResponse(sorted(fused, limit), "HYBRID_RRF", false,
                fused.isEmpty() ? "没有足够的本地证据。" : "已融合关键词与语义检索结果。");
        } catch (RuntimeException unavailable) {
            return new RetrievalResponse(keyword.stream().limit(limit).toList(), "FTS5", true,
                "本地向量服务不可用，已自动降级为关键词检索。");
        }
    }

    private static void add(Map<String, RankedResult> fused, KnowledgeSearchResult result, double score) {
        String key = result.documentId() + ":" + result.chunkIndex();
        fused.merge(key, new RankedResult(result, score), (left, right) -> new RankedResult(left.result(), left.score() + right.score()));
    }
    private static List<KnowledgeSearchResult> sorted(Map<String, RankedResult> fused, int limit) {
        return fused.values().stream().sorted(Comparator.comparingDouble(RankedResult::score).reversed())
            .limit(limit).map(value -> new KnowledgeSearchResult(value.result().documentId(), value.result().title(),
                value.result().sourceName(), value.result().chunkIndex(), value.result().snippet(), value.score())).toList();
    }
    private static String snippet(String value) {
        String clean = value.replaceAll("\\s+", " ").trim();
        return clean.length() <= 360 ? clean : clean.substring(0, 357) + "…";
    }
    private record RankedResult(KnowledgeSearchResult result, double score) {}
}
