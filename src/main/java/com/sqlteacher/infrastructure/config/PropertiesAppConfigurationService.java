package com.sqlteacher.infrastructure.config;

import com.sqlteacher.application.config.AppConfigurationService;
import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.domain.SqlTeacherException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

public final class PropertiesAppConfigurationService implements AppConfigurationService {
    private static final String CONFIG_RESOURCE = "/application.properties";

    private final SqlTeacherConfiguration properties;

    public PropertiesAppConfigurationService() {
        this(loadProperties());
    }

    PropertiesAppConfigurationService(Properties raw) {
        this.properties = parse(raw);
    }

    @Override
    public SqlTeacherConfiguration current() {
        return properties;
    }

    private static Properties loadProperties() {
        Properties raw = new Properties();
        try (InputStream input = PropertiesAppConfigurationService.class.getResourceAsStream(CONFIG_RESOURCE)) {
            if (input == null) {
                throw new SqlTeacherException("CONFIG_NOT_FOUND", "Missing application.properties");
            }
            raw.load(input);
        } catch (IOException ex) {
            throw new SqlTeacherException("CONFIG_LOAD_FAILED", "Failed to load application.properties", ex);
        }
        return raw;
    }

    private static SqlTeacherConfiguration parse(Properties raw) {
        try {
            Path dataDirectory = resolveDataDirectory(raw.getProperty("sqlteacher.data.dir", "auto"));
            DatabaseConfiguration database = new DatabaseConfiguration(
                resolveDataPath(dataDirectory, raw.getProperty("sqlteacher.database.app.path", "app.db")),
                resolveDataPath(dataDirectory, raw.getProperty("sqlteacher.database.demo.path", "demo.db"))
            );
            AiConfiguration ai = new AiConfiguration(
                URI.create(raw.getProperty("sqlteacher.ai.ollama.base-url", "http://localhost:11434")),
                Duration.ofMillis(Long.parseLong(raw.getProperty("sqlteacher.ai.ollama.health-timeout-ms", "2000"))),
                Duration.ofMillis(Long.parseLong(raw.getProperty("sqlteacher.ai.ollama.generate-timeout-ms", "30000"))),
                raw.getProperty("sqlteacher.ai.ollama.default-model", "qwen3.5:0.8b")
            );

            return new SqlTeacherConfiguration(
                raw.getProperty("sqlteacher.app.name", "SQLTeacher"),
                dataDirectory,
                database,
                ai
            );
        } catch (IllegalArgumentException ex) {
            throw new SqlTeacherException(
                "CONFIG_INVALID",
                "Invalid value in application.properties",
                ex
            );
        }
    }

    private static Path resolveDataDirectory(String configured) {
        String systemOverride = System.getProperty("sqlteacher.data.dir");
        if (systemOverride != null && !systemOverride.isBlank()) {
            return Path.of(systemOverride).toAbsolutePath().normalize();
        }
        if (configured != null && !configured.isBlank() && !"auto".equalsIgnoreCase(configured.trim())) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        Path root = localAppData == null || localAppData.isBlank()
            ? Path.of(System.getProperty("user.home"), ".sqlteacher")
            : Path.of(localAppData, "SQLTeacher");
        return root.toAbsolutePath().normalize();
    }

    private static Path resolveDataPath(Path dataDirectory, String configured) {
        Path path = Path.of(configured);
        return (path.isAbsolute() ? path : dataDirectory.resolve(path)).toAbsolutePath().normalize();
    }
}
