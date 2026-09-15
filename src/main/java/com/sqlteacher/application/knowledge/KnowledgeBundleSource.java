package com.sqlteacher.application.knowledge;

import java.util.Locale;

/** Where an official knowledge bundle came from (v3.4.3 OKB). */
public enum KnowledgeBundleSource {
    /** Shipped inside the installer and auto-imported on first run. */
    BUILTIN,
    /** Downloaded from the cloud knowledge-bundle endpoint. */
    CLOUD,
    /** A user-selected local bundle file. */
    MANUAL;

    /** Lowercase wire/DB form matching the knowledge_bundle_state source check constraint. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static KnowledgeBundleSource fromWireName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("bundle source must not be blank");
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
