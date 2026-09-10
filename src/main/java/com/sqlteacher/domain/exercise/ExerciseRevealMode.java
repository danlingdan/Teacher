package com.sqlteacher.domain.exercise;

/**
 * Teacher-controlled disclosure of the expected verification result. Expected rows are
 * never part of any payload before a submission; ON_FAIL (the default) reveals them only
 * in the failed comparison view, ALWAYS also after a pass, NEVER keeps them hidden.
 */
public enum ExerciseRevealMode {
    NEVER,
    ON_FAIL,
    ALWAYS;

    public static ExerciseRevealMode parse(String raw) {
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        return valueOf(normalized);
    }
}
