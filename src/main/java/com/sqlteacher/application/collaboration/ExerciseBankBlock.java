package com.sqlteacher.application.collaboration;

import java.util.Objects;

/**
 * One distributed exercise bank block: the text-DSL fragment of a single dataset or
 * exercise together with its version and content hash.
 */
public record ExerciseBankBlock(
    String type,
    String id,
    int version,
    String sha256,
    String content
) {
    public ExerciseBankBlock {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        Objects.requireNonNull(sha256, "sha256 must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }
}
