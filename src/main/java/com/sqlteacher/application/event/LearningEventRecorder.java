package com.sqlteacher.application.event;

public interface LearningEventRecorder {
    /**
     * Persists an application event. Implementations own all storage details and must not
     * require callers to know a table, file, or serialization format.
     */
    void record(LearningEvent event);
}
