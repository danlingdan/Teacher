package com.sqlteacher.domain.ermodel;

import java.util.List;

/**
 * One entity (rectangle; double rectangle when {@code weak}) of a Chen-notation E-R model.
 * Collections are never null; missing input becomes an empty list (plan §4 lenient parsing).
 */
public record ErEntity(
    String id,
    String name,
    boolean weak,
    List<ErAttribute> attributes
) {
    public ErEntity {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
    }
}
