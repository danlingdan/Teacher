package com.sqlteacher.application.collaboration;

import java.util.List;
import java.util.Objects;

/**
 * Server-side exercise bank manifest: the bank version plus one reference per distributed
 * block. The client computes the pending diff locally; the check request carries no
 * student or personal data.
 */
public record ExerciseBankManifest(
    int bankVersion,
    List<BlockRef> datasets,
    List<BlockRef> exercises
) {
    public ExerciseBankManifest {
        bankVersion = normalizeVersion(bankVersion);
        datasets = List.copyOf(Objects.requireNonNull(datasets, "datasets must not be null"));
        exercises = List.copyOf(Objects.requireNonNull(exercises, "exercises must not be null"));
    }

    public record BlockRef(String id, int version, String sha256) {
        public BlockRef {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id must not be blank");
            }
            version = normalizeVersion(version);
            Objects.requireNonNull(sha256, "sha256 must not be null");
        }
    }

    private static int normalizeVersion(int version) {
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        return version;
    }
}
