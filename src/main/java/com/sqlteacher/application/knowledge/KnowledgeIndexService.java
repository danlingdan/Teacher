package com.sqlteacher.application.knowledge;

public interface KnowledgeIndexService {
    IndexReport rebuildPending();
    IndexReport rebuildAll();

    /**
     * v3.6.0 KBQ-1/KBX-1: 用当前分块算法重切全部文章的当前修订，再整体重建索引。
     * 分块算法版本升级与知识页「重建检索索引」共用；耗时操作，调用方应安排在后台执行。
     */
    IndexReport rebuildContent();

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
