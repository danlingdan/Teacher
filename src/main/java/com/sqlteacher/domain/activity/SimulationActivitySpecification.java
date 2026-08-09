package com.sqlteacher.domain.activity;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record SimulationActivitySpecification(
    int formatVersion,
    String prompt,
    String initialStateId,
    String goalStateId,
    List<SimulationState> states,
    List<SimulationAction> actions,
    List<SimulationCheckpoint> checkpoints
) implements ActivitySpecification {
    public SimulationActivitySpecification {
        if (formatVersion < 1) throw new IllegalArgumentException("formatVersion must be positive");
        prompt = required(prompt, "prompt");
        initialStateId = required(initialStateId, "initialStateId");
        goalStateId = required(goalStateId, "goalStateId");
        states = List.copyOf(Objects.requireNonNull(states, "states must not be null"));
        actions = List.copyOf(Objects.requireNonNull(actions, "actions must not be null"));
        checkpoints = List.copyOf(Objects.requireNonNull(checkpoints, "checkpoints must not be null"));
        if (states.size() < 2 || actions.isEmpty() || checkpoints.isEmpty()) {
            throw new IllegalArgumentException("simulation requires states, actions, and checkpoints");
        }
        Set<String> stateIds = unique(states.stream().map(SimulationState::id).toList(), "state");
        unique(actions.stream().map(SimulationAction::id).toList(), "action");
        unique(checkpoints.stream().map(SimulationCheckpoint::id).toList(), "checkpoint");
        if (!stateIds.contains(initialStateId) || !stateIds.contains(goalStateId)) {
            throw new IllegalArgumentException("initial and goal states must exist");
        }
        if (actions.stream().anyMatch(action -> !stateIds.contains(action.fromStateId())
                || !stateIds.contains(action.toStateId()))) {
            throw new IllegalArgumentException("simulation actions must reference existing states");
        }
        if (checkpoints.stream().anyMatch(checkpoint -> !stateIds.contains(checkpoint.stateId()))) {
            throw new IllegalArgumentException("simulation checkpoints must reference existing states");
        }
        Set<String> reachable = reachableStates(initialStateId, actions);
        if (!reachable.contains(goalStateId)
                || checkpoints.stream().anyMatch(checkpoint -> !reachable.contains(checkpoint.stateId()))) {
            throw new IllegalArgumentException("goal and checkpoint states must be reachable from the initial state");
        }
    }

    @Override public ActivityType type() { return ActivityType.SIMULATION; }

    public Map<String, SimulationState> statesById() {
        Map<String, SimulationState> result = new HashMap<>();
        states.forEach(state -> result.put(state.id(), state));
        return Map.copyOf(result);
    }

    public Map<String, SimulationAction> actionsById() {
        Map<String, SimulationAction> result = new HashMap<>();
        actions.forEach(action -> result.put(action.id(), action));
        return Map.copyOf(result);
    }

    private static Set<String> reachableStates(String initialStateId, List<SimulationAction> actions) {
        Map<String, List<String>> transitions = new HashMap<>();
        actions.forEach(action -> transitions.computeIfAbsent(action.fromStateId(), ignored -> new java.util.ArrayList<>())
            .add(action.toStateId()));
        Set<String> visited = new HashSet<>();
        var queue = new ArrayDeque<String>();
        queue.add(initialStateId);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) continue;
            transitions.getOrDefault(current, List.of()).forEach(queue::addLast);
        }
        return Set.copyOf(visited);
    }

    private static Set<String> unique(List<String> ids, String kind) {
        Set<String> result = new HashSet<>(ids);
        if (result.size() != ids.size()) throw new IllegalArgumentException(kind + " ids must be unique");
        return Set.copyOf(result);
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
