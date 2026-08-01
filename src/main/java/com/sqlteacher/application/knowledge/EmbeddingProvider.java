package com.sqlteacher.application.knowledge;

import java.util.List;

public interface EmbeddingProvider {
    EmbeddingBatch embed(List<String> texts);

    record EmbeddingBatch(String provider, String model, List<float[]> vectors) {
        public EmbeddingBatch {
            if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
                throw new IllegalArgumentException("embedding provider and model must not be blank");
            }
            vectors = vectors == null ? List.of() : vectors.stream().map(float[]::clone).toList();
        }

        public int dimensions() {
            return vectors.isEmpty() ? 0 : vectors.getFirst().length;
        }
    }
}
