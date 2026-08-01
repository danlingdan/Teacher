package com.sqlteacher.desktop.viewmodel;

import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.connection.DatabaseConnectionProfile;
import com.sqlteacher.domain.SqlTeacherException;

import java.util.Objects;

/**
 * Single source of truth for the desktop demo connection identifier.
 *
 * <p>Every ViewModel factory and mock service reuses {@link #DEMO} instead of hard-coding the
 * literal {@code "demo"} in multiple places.
 */
public final class DesktopConnections {

    /** Fixed connection id used by the initial single-database demo. */
    public static final String DEMO = "demo";

    public static String currentId(ConnectionManagementService connectionManagementService) {
        return currentProfile(connectionManagementService).id();
    }

    public static DatabaseConnectionProfile currentProfile(ConnectionManagementService connectionManagementService) {
        Objects.requireNonNull(connectionManagementService, "connectionManagementService must not be null");
        return connectionManagementService.currentProfile()
            .filter(profile -> profile.enabled())
            .orElseThrow(() -> new SqlTeacherException(
                "DATABASE_CONNECTION_NOT_FOUND",
                com.sqlteacher.desktop.AppI18n.get("db.connectionNotFound")
            ));
    }

    private DesktopConnections() {
    }
}
