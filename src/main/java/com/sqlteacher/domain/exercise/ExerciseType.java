package com.sqlteacher.domain.exercise;

/**
 * Deterministic exercise kinds evaluated by the Java kernel. QUERY keeps the classic
 * read-only result comparison; STATE grades a single write statement, SCRIPT a
 * multi-statement script, and TRIGGER a CREATE TRIGGER definition, all through the
 * verification-query comparison on isolated sandbox databases.
 */
public enum ExerciseType {
    QUERY,
    STATE,
    SCRIPT,
    TRIGGER
}
