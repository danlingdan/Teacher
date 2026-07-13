package com.sqlteacher.desktop.viewmodel;

/**
 * Single source of truth for the desktop demo connection identifier.
 *
 * <p>Every ViewModel factory and mock service reuses {@link #DEMO} instead of hard-coding the
 * literal {@code "demo"} in multiple places.
 */
public final class DesktopConnections {

    /** Fixed connection id used by the initial single-database demo. */
    public static final String DEMO = "demo";

    private DesktopConnections() {
    }
}
