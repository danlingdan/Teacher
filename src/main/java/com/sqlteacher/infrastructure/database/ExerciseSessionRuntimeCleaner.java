package com.sqlteacher.infrastructure.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class ExerciseSessionRuntimeCleaner {
    private static final Pattern SESSION_FILE = Pattern.compile(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.db(?:-wal|-shm)?",
        Pattern.CASE_INSENSITIVE
    );

    private ExerciseSessionRuntimeCleaner() {
    }

    static int closeActiveSessions(Connection connection, Instant completedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "update exercise_sessions set completed_at = ? where completed_at is null"
        )) {
            statement.setString(1, completedAt.toString());
            return statement.executeUpdate();
        }
    }

    static int deleteSessionFiles(Path sessionDirectory) throws IOException {
        Path normalizedDirectory = sessionDirectory.toAbsolutePath().normalize();
        if (Files.notExists(normalizedDirectory)) {
            return 0;
        }
        int deleted = 0;
        try (Stream<Path> entries = Files.list(normalizedDirectory)) {
            for (Path entry : entries.toList()) {
                Path normalizedEntry = entry.toAbsolutePath().normalize();
                if (normalizedEntry.getParent().equals(normalizedDirectory)
                        && Files.isRegularFile(normalizedEntry)
                        && SESSION_FILE.matcher(normalizedEntry.getFileName().toString()).matches()
                        && Files.deleteIfExists(normalizedEntry)) {
                    deleted++;
                }
            }
        }
        return deleted;
    }
}
