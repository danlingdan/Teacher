package com.sqlteacher.domain.ermodel;

import java.util.List;

/**
 * One relationship (diamond; double diamond when {@code identifying}) of a Chen-notation E-R
 * model with 2-4 member entity ends and relationship attributes hung on the diamond.
 * Collections are never null (plan §4 lenient parsing).
 */
public record ErRelationship(
    String id,
    String name,
    boolean identifying,
    List<ErRelationshipMember> members,
    List<ErAttribute> attributes
) {
    public ErRelationship {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        members = members == null ? List.of() : List.copyOf(members);
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
    }
}
