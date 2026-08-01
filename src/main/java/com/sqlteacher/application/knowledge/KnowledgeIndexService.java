package com.sqlteacher.application.knowledge;

public interface KnowledgeIndexService {
    IndexReport rebuildPending();
    IndexReport rebuildAll();
    IndexStatus status();

    record IndexReport(int indexedChunks, int failedJobs, String message) {
        public IndexReport {
            if (indexedChunks < 0 || failedJobs < 0) throw new IllegalArgumentException("index counts must not be negative");
            message = message == null ? "" : message.trim();
        }
    }

    record IndexStatus(int pendingJobs, int indexedChunks, int failedChunks, String mode, String message) {
        public IndexStatus {
            if (pendingJobs < 0 || indexedChunks < 0 || failedChunks < 0 || mode == null || mode.isBlank()) {
                throw new IllegalArgumentException("index status values are invalid");
            }
            message = message == null ? "" : message.trim();
        }
    }
}
