package com.sqlteacher.desktop.navigation;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Replaces pages without exposing an empty host frame. The outgoing page stays visible until the
 * incoming page has completed CSS/layout and cross-fades above it.
 */
public final class PageTransitionCoordinator {
    static final Duration ENTER_DURATION = Duration.millis(180);
    static final Duration EXIT_DURATION = Duration.millis(110);
    static final double ENTER_OFFSET = 10.0;

    private final StackPane host;
    private final BooleanSupplier reducedMotion;
    private Animation running;
    private Node displayedPage;
    private Node transitionTarget;
    private Node outgoingPage;

    public PageTransitionCoordinator(StackPane host, BooleanSupplier reducedMotion) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.reducedMotion = Objects.requireNonNull(reducedMotion, "reducedMotion must not be null");
    }

    public void show(Node page) {
        Objects.requireNonNull(page, "page must not be null");
        finishRunningTransition();

        Node current = displayedPage;
        if (current == null && !host.getChildren().isEmpty()) {
            current = host.getChildren().getLast();
        }
        if (current == page) {
            replaceImmediately(page);
            return;
        }
        if (current == null || host.getScene() == null || reducedMotion.getAsBoolean()) {
            replaceImmediately(page);
            return;
        }

        outgoingPage = current;
        transitionTarget = page;
        displayedPage = page;

        resetVisualState(page);
        page.setOpacity(0.0);
        page.setTranslateX(ENTER_OFFSET);
        page.setMouseTransparent(false);
        current.setMouseTransparent(true);
        host.getChildren().setAll(current, page);
        host.applyCss();
        host.layout();

        FadeTransition fadeOut = new FadeTransition(EXIT_DURATION, current);
        fadeOut.setFromValue(current.getOpacity());
        fadeOut.setToValue(0.0);
        fadeOut.setInterpolator(Interpolator.EASE_OUT);

        FadeTransition fadeIn = new FadeTransition(ENTER_DURATION, page);
        fadeIn.setFromValue(0.0);
        fadeIn.setToValue(1.0);
        fadeIn.setInterpolator(Interpolator.EASE_OUT);

        TranslateTransition slideIn = new TranslateTransition(ENTER_DURATION, page);
        slideIn.setFromX(ENTER_OFFSET);
        slideIn.setToX(0.0);
        slideIn.setInterpolator(Interpolator.EASE_OUT);

        ParallelTransition transition = new ParallelTransition(fadeOut, fadeIn, slideIn);
        running = transition;
        transition.setOnFinished(ignored -> completeTransition());
        transition.playFromStart();
    }

    private void finishRunningTransition() {
        if (running == null) return;
        running.stop();
        completeTransition();
    }

    private void completeTransition() {
        Node target = transitionTarget;
        Node outgoing = outgoingPage;
        running = null;
        transitionTarget = null;
        outgoingPage = null;
        if (target == null) return;

        resetVisualState(target);
        host.getChildren().setAll(target);
        if (outgoing != null && outgoing != target) resetVisualState(outgoing);
        displayedPage = target;
    }

    private void replaceImmediately(Node page) {
        resetVisualState(page);
        host.getChildren().setAll(page);
        displayedPage = page;
        transitionTarget = null;
        outgoingPage = null;
        running = null;
    }

    private static void resetVisualState(Node node) {
        node.setOpacity(1.0);
        node.setTranslateX(0.0);
        node.setMouseTransparent(false);
    }
}
