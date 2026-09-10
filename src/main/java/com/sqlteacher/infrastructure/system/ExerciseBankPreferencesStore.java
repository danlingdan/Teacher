package com.sqlteacher.infrastructure.system;

import java.nio.file.Path;
import java.util.List;

/**
 * File-backed store for {@link ExerciseBankPreferences}; failures fall back to defaults so
 * the practice flow never depends on preferences being readable.
 */
public final class ExerciseBankPreferencesStore {
    private final Path file;

    public ExerciseBankPreferencesStore(Path dataDirectory) {
        this.file = dataDirectory.toAbsolutePath().normalize()
            .resolve("support").resolve("exercise-bank-preferences.json");
    }

    public ExerciseBankPreferences load() {
        try {
            ExerciseBankPreferences value = AtomicJsonFile.read(file, ExerciseBankPreferences.class, null);
            if (value == null) {
                return ExerciseBankPreferences.defaults();
            }
            List<String> channels = value.subscribedChannels().isEmpty()
                ? List.of(ExerciseBankPreferences.DEFAULT_CHANNEL)
                : value.subscribedChannels();
            return new ExerciseBankPreferences(value.autoCheckEnabled(), channels, value.pendingNotice());
        } catch (RuntimeException error) {
            return ExerciseBankPreferences.defaults();
        }
    }

    public void save(ExerciseBankPreferences preferences) {
        try {
            AtomicJsonFile.write(file, preferences);
        } catch (RuntimeException error) {
            throw new IllegalStateException("Failed to save exercise bank preferences", error);
        }
    }
}
