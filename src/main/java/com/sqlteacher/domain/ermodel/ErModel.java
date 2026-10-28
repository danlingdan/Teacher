package com.sqlteacher.domain.ermodel;

import java.util.List;

/**
 * Complete Chen-notation E-R model produced by AI drafting and validated by
 * {@link ErModelValidator} before it may cross the IPC bridge (v3.11.0 plan §4).
 * Collections are never null; failed generations never carry a model.
 */
public record ErModel(
    String title,
    String explanation,
    List<ErEntity> entities,
    List<ErRelationship> relationships,
    List<ErHierarchy> hierarchies
) {
    public ErModel {
        title = title == null ? "" : title;
        explanation = explanation == null ? "" : explanation;
        entities = entities == null ? List.of() : List.copyOf(entities);
        relationships = relationships == null ? List.of() : List.copyOf(relationships);
        hierarchies = hierarchies == null ? List.of() : List.copyOf(hierarchies);
    }
}
