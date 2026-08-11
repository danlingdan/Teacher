package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.Consumer;

public interface LocalAppApi extends AutoCloseable {
    JsonNode invoke(String method, JsonNode params, CancellationToken cancellation,
                    Consumer<LocalAppEvent> events) throws Exception;

    @Override
    default void close() {
    }
}
