package com.sqlteacher.domain.activity;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record TraceActivitySpecification(
    int formatVersion,
    String prompt,
    String traversal,
    String rootNodeId,
    List<TraceNode> nodes,
    List<String> expectedNodeIds
) implements ActivitySpecification {
    public TraceActivitySpecification {
        if (formatVersion < 1) throw new IllegalArgumentException("formatVersion must be positive");
        prompt = required(prompt, "prompt");
        traversal = required(traversal, "traversal");
        rootNodeId = required(rootNodeId, "rootNodeId");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes must not be null"));
        expectedNodeIds = List.copyOf(Objects.requireNonNull(expectedNodeIds, "expectedNodeIds must not be null"));
        if (nodes.isEmpty() || expectedNodeIds.isEmpty()) throw new IllegalArgumentException("trace nodes must not be empty");
        Set<String> ids = new HashSet<>(nodes.stream().map(TraceNode::id).toList());
        if (ids.size() != nodes.size()) throw new IllegalArgumentException("trace node ids must be unique");
        if (!ids.contains(rootNodeId) || !ids.containsAll(expectedNodeIds) || expectedNodeIds.size() != nodes.size()
                || new HashSet<>(expectedNodeIds).size() != expectedNodeIds.size()) {
            throw new IllegalArgumentException("trace order must contain every node exactly once");
        }
        for (TraceNode node : nodes) {
            if ((!node.leftChildId().isEmpty() && !ids.contains(node.leftChildId()))
                    || (!node.rightChildId().isEmpty() && !ids.contains(node.rightChildId()))) {
                throw new IllegalArgumentException("child ids must reference trace nodes");
            }
        }
    }

    @Override public ActivityType type() { return ActivityType.TRACE; }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
