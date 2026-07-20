package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.connection.DatabaseCredentialSession;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class InMemoryDatabaseCredentialSession implements DatabaseCredentialSession {
    private final Map<String, char[]> passwords = new HashMap<>();

    @Override
    public synchronized void remember(String connectionId, char[] password) {
        String normalizedId = requireConnectionId(connectionId);
        Objects.requireNonNull(password, "password must not be null");
        char[] replacement = Arrays.copyOf(password, password.length);
        char[] previous = passwords.put(normalizedId, replacement);
        wipe(previous);
    }

    @Override
    public synchronized Optional<char[]> passwordFor(String connectionId) {
        char[] password = passwords.get(requireConnectionId(connectionId));
        return password == null ? Optional.empty() : Optional.of(Arrays.copyOf(password, password.length));
    }

    @Override
    public synchronized void forget(String connectionId) {
        wipe(passwords.remove(requireConnectionId(connectionId)));
    }

    @Override
    public synchronized void close() {
        passwords.values().forEach(InMemoryDatabaseCredentialSession::wipe);
        passwords.clear();
    }

    private static String requireConnectionId(String connectionId) {
        Objects.requireNonNull(connectionId, "connectionId must not be null");
        String normalized = connectionId.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("connectionId must not be blank");
        }
        return normalized;
    }

    private static void wipe(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }
}
