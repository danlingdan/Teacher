package com.sqlteacher.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Shared JSON plumbing for the cloud server and its store family: one
 * configured mapper (registered modules, ISO-8601 instants instead of numeric
 * timestamps) plus the serialize-or-fail wrapper used when building stored or
 * exported JSON documents. The mapper is thread-safe once configured.
 */
final class CloudJsonStoreSupport {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private CloudJsonStoreSupport() { }

    static ObjectMapper mapper() {
        return JSON;
    }

    static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("JSON serialization failed", error);
        }
    }
}
