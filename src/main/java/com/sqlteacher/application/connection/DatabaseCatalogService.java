package com.sqlteacher.application.connection;

/**
 * Lists the selectable databases on a server so the connection form can offer a picker instead
 * of free-text input. The implementation must not retain, log, or include the password in its
 * result; the caller owns the array and clears it after the call.
 */
public interface DatabaseCatalogService {
    DatabaseCatalog listDatabases(DatabaseConnectionProfile profile, char[] password);
}
