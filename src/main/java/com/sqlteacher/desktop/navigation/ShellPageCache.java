package com.sqlteacher.desktop.navigation;

import javafx.scene.Node;

import java.util.EnumMap;
import java.util.Objects;
import java.util.function.Supplier;

/** Explicit page lifecycle cache keyed by stable shell routes. */
public final class ShellPageCache {
    private final EnumMap<ShellRoute, Node> pages = new EnumMap<>(ShellRoute.class);

    public Node getOrLoad(ShellRoute route, Supplier<Node> loader) {
        Objects.requireNonNull(route);
        Objects.requireNonNull(loader);
        return pages.computeIfAbsent(route, ignored -> Objects.requireNonNull(loader.get(), "loaded page"));
    }

    public void evict(ShellRoute route) {
        pages.remove(Objects.requireNonNull(route));
    }

    public void clear() {
        pages.clear();
    }

    public int size() {
        return pages.size();
    }
}
