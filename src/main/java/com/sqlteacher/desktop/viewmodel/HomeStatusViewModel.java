package com.sqlteacher.desktop.viewmodel;

import com.sqlteacher.application.ai.AiStatus;
import com.sqlteacher.application.database.DatabaseInitializationResult;

/**
 * Page-level ViewModel for the home / environment status page.
 *
 * <p>Composes the database initialization status and the AI status into a single object the
 * page binds to. {@code appName} and {@code dataDirectory} are supplied as primitives by the
 * caller (extracted from the configuration service) so that this ViewModel does not import the
 * infrastructure configuration types.
 */
public record HomeStatusViewModel(
    String appName,
    String connectionId,
    String dataDirectory,
    DatabaseStatusViewModel database,
    AiStatusViewModel ai
) {
    public HomeStatusViewModel {
        appName = appName == null || appName.isBlank() ? "SQLTeacher" : appName;
        connectionId = connectionId == null || connectionId.isBlank() ? DesktopConnections.DEMO : connectionId;
        dataDirectory = dataDirectory == null ? "" : dataDirectory;
        database = database == null ? DatabaseStatusViewModel.unknown() : database;
        ai = ai == null ? AiStatusViewModel.unknown() : ai;
    }

    public static HomeStatusViewModel from(
        String appName,
        String dataDirectory,
        DatabaseInitializationResult databaseResult,
        AiStatus aiStatus
    ) {
        return new HomeStatusViewModel(
            appName,
            DesktopConnections.DEMO,
            dataDirectory,
            DatabaseStatusViewModel.from(databaseResult),
            AiStatusViewModel.from(aiStatus)
        );
    }
}
