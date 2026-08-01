package com.sqlteacher.server;

import java.util.List;

interface CloudKnowledgeEmbeddingClient {
    EmbeddingBatch embed(List<String> texts, Purpose purpose);

    boolean ready();

    enum Purpose { QUERY, PASSAGE }

    record EmbeddingBatch(String provider, String model, List<float[]> vectors) {
        public EmbeddingBatch {
            if (provider == null || provider.isBlank() || model == null || model.isBlank()
                || vectors == null || vectors.isEmpty()) {
                throw new IllegalArgumentException("embedding result is invalid");
            }
            vectors = vectors.stream().map(float[]::clone).toList();
        }
    }
}
