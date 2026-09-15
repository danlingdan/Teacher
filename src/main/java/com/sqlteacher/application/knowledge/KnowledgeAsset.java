package com.sqlteacher.application.knowledge;

/** A local knowledge-bundle image asset, resolved for rendering (v3.4.3 OKB-7). */
public record KnowledgeAsset(String contentType, byte[] data) {
    public KnowledgeAsset {
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        data = data.clone();
    }

    public byte[] data() {
        return data.clone();
    }
}
