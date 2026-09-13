package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;
import java.util.function.Consumer;

/**
 * v3.4.0 REF-8: one business-domain slice of the frozen {@code 3.0-v1} desktop IPC method set.
 * Sections own a disjoint subset of {@link LocalAppContract#API_METHODS}; DefaultLocalAppApi
 * keeps the whitelist guard, the lazy Spring core, and a registry lookup that forwards here.
 */
interface LocalAppApiSection {

    /** The IPC methods this section handles; must be disjoint from every other section. */
    Set<String> supportedMethods();

    JsonNode handle(String method, JsonNode params, CancellationToken cancellation,
                    Consumer<LocalAppEvent> events) throws Exception;
}
