package com.sqlteacher.application.connection;

import java.util.Optional;

/** Holds database passwords in memory for the current application process only. */
public interface DatabaseCredentialSession extends AutoCloseable {
    void remember(String connectionId, char[] password);

    Optional<char[]> passwordFor(String connectionId);

    void forget(String connectionId);

    @Override
    void close();
}
