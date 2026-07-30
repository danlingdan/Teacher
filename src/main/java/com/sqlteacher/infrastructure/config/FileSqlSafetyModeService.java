package com.sqlteacher.infrastructure.config;

import com.sqlteacher.application.risk.SqlSafetyModeService;
import com.sqlteacher.domain.SqlTeacherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Properties;

/** Persists the device-local SQL safety choice under the application data directory. */
public final class FileSqlSafetyModeService implements SqlSafetyModeService {
    private static final Logger log = LoggerFactory.getLogger(FileSqlSafetyModeService.class);
    private static final String KEY = "unrestricted-mode";

    private final Path settingsFile;
    private volatile boolean unrestrictedModeEnabled;

    public FileSqlSafetyModeService(Path settingsFile) {
        this.settingsFile = Objects.requireNonNull(settingsFile, "settingsFile must not be null")
            .toAbsolutePath().normalize();
        this.unrestrictedModeEnabled = load();
    }

    @Override
    public boolean isUnrestrictedModeEnabled() {
        return unrestrictedModeEnabled;
    }

    @Override
    public synchronized void setUnrestrictedModeEnabled(boolean enabled) {
        Properties properties = new Properties();
        properties.setProperty(KEY, Boolean.toString(enabled));
        Path parent = settingsFile.getParent();
        Path temporary = settingsFile.resolveSibling(settingsFile.getFileName() + ".tmp");
        try {
            if (parent != null) Files.createDirectories(parent);
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "SQLTeacher local SQL safety settings");
            }
            try {
                Files.move(temporary, settingsFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, settingsFile, StandardCopyOption.REPLACE_EXISTING);
            }
            unrestrictedModeEnabled = enabled;
        } catch (IOException error) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw new SqlTeacherException(
                "SQL_SAFETY_SETTINGS_SAVE_FAILED",
                "无法保存 SQL 安全设置，请检查应用数据目录是否可写。",
                error
            );
        }
    }

    private boolean load() {
        if (!Files.isRegularFile(settingsFile)) return false;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(settingsFile)) {
            properties.load(input);
            return Boolean.parseBoolean(properties.getProperty(KEY, "false"));
        } catch (IOException error) {
            log.warn("Failed to load SQL safety settings; falling back to standard mode", error);
            return false;
        }
    }
}
