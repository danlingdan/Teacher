package com.sqlteacher.application.knowledge;

import java.time.Instant;
import java.util.Optional;

public interface KnowledgeReadStateService {
    ReadState save(String articleId, int revision, int progressPercent);
    Optional<ReadState> find(String articleId);

    record ReadState(String articleId, int revision, int progressPercent, Instant lastReadAt) {
        public ReadState {
            if (articleId == null || articleId.isBlank() || revision < 1 || progressPercent < 0
                || progressPercent > 100 || lastReadAt == null) throw new IllegalArgumentException("read state values are invalid");
        }
    }
}
