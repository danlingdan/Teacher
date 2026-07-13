package com.sqlteacher.desktop.mock;

/**
 * Simulated backend response scenarios used by the desktop mock services.
 *
 * <ul>
 *   <li>{@link #NORMAL} - full, valid payload.</li>
 *   <li>{@link #EMPTY} - structurally valid but empty payload (no rows, no models, ...).</li>
 *   <li>{@link #ERROR} - simulated interface failure. For DTOs that can express failure inline
 *       (e.g. {@code SqlExecutionResult.success = false}, a non-PASS {@code AiStatus}) a failure
 *       DTO is returned; for DTOs that cannot express failure a {@code MockBackendException} is
 *       thrown.</li>
 * </ul>
 *
 * <p>Lives in the {@code src/main} source set so the offline desktop launcher (and its controllers)
 * can construct the mock services for the demo runtime. The remaining mock services and the
 * contract test share this enum from the same package via the classpath (non-modular build).
 */
public enum MockScenario {
    NORMAL,
    EMPTY,
    ERROR
}
