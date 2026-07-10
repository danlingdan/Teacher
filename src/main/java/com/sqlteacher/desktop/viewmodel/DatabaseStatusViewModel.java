package com.sqlteacher.desktop.viewmodel;

import com.sqlteacher.application.database.DatabaseInitializationResult;

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
        String summary = "app.db " + (result.appDatabaseCreated() ? "已创建" : "已存在")
            + " / demo.db " + (result.demoDatabaseCreated() ? "已创建" : "已存在");
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
