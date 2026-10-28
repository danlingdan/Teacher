package com.sqlteacher.domain.ermodel;

import java.util.List;

/**
 * One attribute of an entity or relationship in a Chen-notation E-R model (v3.11.0 plan §3/§4).
 * Lenient by construction: missing booleans are {@code false} and missing collections are empty,
 * so AI output can be mapped straight into this record before deterministic validation.
 */
public record ErAttribute(
    String name,
    boolean key,
    List<String> composite,
    boolean multivalued,
    boolean derived
) {
    public ErAttribute {
        name = name == null ? "" : name;
        composite = composite == null ? List.of() : List.copyOf(composite);
    }

    /** Simple attribute without composite children or notation flags. */
    public ErAttribute(String name, boolean key) {
        this(name, key, List.of(), false, false);
    }
}
