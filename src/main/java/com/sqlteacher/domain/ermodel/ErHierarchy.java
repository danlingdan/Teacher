package com.sqlteacher.domain.ermodel;

import java.util.List;

/**
 * One generalization/specialization (ISA triangle) of a Chen-notation E-R model: a superclass
 * entity id and at least one subclass entity id. Textbook ISA is a tree, which the validator
 * enforces (an entity may be a subclass in at most one hierarchy).
 */
public record ErHierarchy(
    String id,
    String name,
    String superclass,
    List<String> subclasses
) {
    public ErHierarchy {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        superclass = superclass == null ? "" : superclass;
        subclasses = subclasses == null ? List.of() : List.copyOf(subclasses);
    }
}
