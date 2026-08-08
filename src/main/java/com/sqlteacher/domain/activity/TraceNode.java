package com.sqlteacher.domain.activity;

import java.util.Objects;

public record TraceNode(String id, String label, String leftChildId, String rightChildId) {
    public TraceNode {
        id = required(id, "id");
        label = required(label, "label");
        leftChildId = optional(leftChildId);
        rightChildId = optional(rightChildId);
        if (id.equals(leftChildId) || id.equals(rightChildId)) {
            throw new IllegalArgumentException("a node cannot be its own child");
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }
}
