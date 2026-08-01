package com.sqlteacher.application.update;

/**
 * Controlled-rollout metadata attached to a stable update manifest.
 *
 * <p>{@code percentage} is the fraction (0..100) of anonymous buckets that may
 * see the version through an automatic check; {@code paused} freezes visibility
 * entirely until an operator resumes. A missing rollout field is equivalent to
 * {@code new Rollout(100, false)} (fully visible, not paused).</p>
 */
public record Rollout(int percentage, boolean paused) {
    public Rollout {
        if (percentage < 0 || percentage > 100) throw new IllegalArgumentException("rollout percentage must be 0..100");
    }

    public static Rollout fullyVisible() { return new Rollout(100, false); }
}
