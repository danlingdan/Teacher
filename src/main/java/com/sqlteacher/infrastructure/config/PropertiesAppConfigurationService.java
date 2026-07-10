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
            Path dataDirectory = Path.of(raw.getProperty("sqlteacher.data.dir", "app-data"));
            DatabaseConfiguration database = new DatabaseConfiguration(
                Path.of(raw.getProperty("sqlteacher.database.app.path", "app-data/app.db")),
                Path.of(raw.getProperty("sqlteacher.database.demo.path", "app-data/demo.db"))
            );
            AiConfiguration ai = new AiConfiguration(
                URI.create(raw.getProperty("sqlteacher.ai.ollama.base-url", "http://localhost:11434")),
                Duration.ofMillis(Long.parseLong(raw.getProperty("sqlteacher.ai.ollama.health-timeout-ms", "2000")))
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
}
