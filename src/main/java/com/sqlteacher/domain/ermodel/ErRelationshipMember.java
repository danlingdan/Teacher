package com.sqlteacher.domain.ermodel;

/**
 * One entity end of a relationship: the referenced entity id, the textbook cardinality
 * {@code 1}/{@code n}/{@code m} (validated against the whitelist, lowercase), and whether the
 * entity participates totally (double line) in this relationship.
 */
public record ErRelationshipMember(
    String entity,
    String cardinality,
    boolean total
) {
    public ErRelationshipMember {
        entity = entity == null ? "" : entity;
        cardinality = cardinality == null ? "" : cardinality;
    }
}
