package com.sqlteacher.desktop.navigation;

import javafx.scene.Group;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class PageTransitionCoordinatorTest {
    @Test
    void shouldNeverExposeAnEmptyHostBeforeTheSceneIsAttached() {
        StackPane host = new StackPane();
        PageTransitionCoordinator transitions = new PageTransitionCoordinator(host, () -> false);
        Group first = new Group();
        Group second = new Group();

        transitions.show(first);
        transitions.show(second);

        assertEquals(1, host.getChildren().size());
        assertSame(second, host.getChildren().getFirst());
        assertEquals(1.0, second.getOpacity());
        assertEquals(0.0, second.getTranslateX());
        assertFalse(second.isMouseTransparent());
    }

    @Test
    void shouldReplaceImmediatelyWhenReducedMotionIsRequested() {
        StackPane host = new StackPane();
        PageTransitionCoordinator transitions = new PageTransitionCoordinator(host, () -> true);
        Group page = new Group();
        page.setOpacity(0.2);
        page.setTranslateX(40);
        page.setMouseTransparent(true);

        transitions.show(page);

        assertSame(page, host.getChildren().getFirst());
        assertEquals(1.0, page.getOpacity());
        assertEquals(0.0, page.getTranslateX());
        assertFalse(page.isMouseTransparent());
    }
}
