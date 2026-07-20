package com.sqlteacher.application.connection;

import java.util.List;
import java.util.Optional;

/** Manages non-sensitive connection profiles and the connection selected for application use cases. */
public interface ConnectionManagementService {
    List<DatabaseConnectionProfile> listProfiles();

    Optional<DatabaseConnectionProfile> findProfile(String connectionId);

    DatabaseConnectionProfile saveProfile(DatabaseConnectionProfile profile);

    void removeProfile(String connectionId);

    Optional<DatabaseConnectionProfile> currentProfile();

    DatabaseConnectionProfile selectProfile(String connectionId);
}
