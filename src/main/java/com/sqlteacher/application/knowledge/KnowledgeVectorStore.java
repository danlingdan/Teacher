package com.sqlteacher.application.knowledge;

import java.util.List;

public interface KnowledgeVectorStore {
    void replaceRevision(String revisionId, List<KnowledgeChunkRecord> chunks, List<float[]> vectors);
    List<VectorSearchHit> search(float[] queryVector, CourseKnowledgeSearchFilter filter, String ownerId, int limit);
    void deleteArticle(String articleId);
    void clear();

    record VectorSearchHit(KnowledgeChunkRecord chunk, double score) {
        public VectorSearchHit {
            if (chunk == null || !Double.isFinite(score)) {
                throw new IllegalArgumentException("vector hit values are invalid");
            }
        }
    }
}
