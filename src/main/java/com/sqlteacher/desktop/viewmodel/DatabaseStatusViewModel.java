package com.sqlteacher.desktop.viewmodel;

import com.sqlteacher.application.database.DatabaseInitializationResult;
import com.sqlteacher.desktop.AppI18n;

/**
 * ViewModel for the SQLite database initialization status shown on the home page.
 *
 * <p>Adapts {@link DatabaseInitializationResult}: {@code Path} values are rendered to strings
 * and a human-readable {@code summary} plus a {@link UiStatusLevel} are derived for the UI.
 */
public record DatabaseStatusViewModel(
    String appDatabasePath,
    String demoDatabasePath,
    boolean appDatabaseCreated,
    boolean demoDatabaseCreated,
    UiStatusLevel statusLevel,
    String summary
) {
    public DatabaseStatusViewModel {
        appDatabasePath = appDatabasePath == null ? "" : appDatabasePath;
        demoDatabasePath = demoDatabasePath == null ? "" : demoDatabasePath;
        statusLevel = statusLevel == null ? UiStatusLevel.UNKNOWN : statusLevel;
        summary = summary == null ? "" : summary;
    }

    public static DatabaseStatusViewModel from(DatabaseInitializationResult result) {
        String appPath = result.appDatabasePath() == null ? "" : result.appDatabasePath().toString();
        String demoPath = result.demoDatabasePath() == null ? "" : result.demoDatabasePath().toString();
        String appState = result.appDatabaseCreated() ? AppI18n.get("db.created") : AppI18n.get("db.exists");
        String demoState = result.demoDatabaseCreated() ? AppI18n.get("db.created") : AppI18n.get("db.exists");
        String summary = AppI18n.format("db.statusSummary", "app.db " + appState, "demo.db " + demoState);
        return new DatabaseStatusViewModel(
            appPath,
            demoPath,
            result.appDatabaseCreated(),
            result.demoDatabaseCreated(),
            UiStatusLevel.SUCCESS,
            summary
        );
    }

    static DatabaseStatusViewModel unknown() {
        return new DatabaseStatusViewModel("", "", false, false, UiStatusLevel.UNKNOWN, "");
    }
}
