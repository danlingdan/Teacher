package com.sqlteacher.desktop.navigation;

import javafx.scene.Group;
import javafx.scene.Node;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ShellPageCacheTest {
    @Test
    void shouldLoadOnceUntilRouteIsEvicted() {
        ShellPageCache cache = new ShellPageCache();
        AtomicInteger loads = new AtomicInteger();

        Node first = cache.getOrLoad(ShellRoute.HOME, () -> {
            loads.incrementAndGet();
            return new Group();
        });
        Node cached = cache.getOrLoad(ShellRoute.HOME, () -> new Group());

        assertSame(first, cached);
        assertEquals(1, loads.get());
        assertEquals(1, cache.size());

        cache.evict(ShellRoute.HOME);
        Node reloaded = cache.getOrLoad(ShellRoute.HOME, Group::new);
        assertEquals(1, cache.size());
        org.junit.jupiter.api.Assertions.assertNotSame(first, reloaded);
    }
}
