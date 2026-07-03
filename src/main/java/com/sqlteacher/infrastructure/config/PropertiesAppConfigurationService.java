package com.sqlteacher.infrastructure.config;

import com.sqlteacher.application.config.AppConfigurationService;
import com.sqlteacher.domain.SqlTeacherException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

public final class PropertiesAppConfigurationService implements AppConfigurationService {
    private static final String CONFIG_RESOURCE = "/application.properties";

    private final SqlTeacherProperties properties;

    public PropertiesAppConfigurationService() {
        this.properties = load();
    }

    @Override
    public SqlTeacherProperties current() {
        return properties;
    }

    private static SqlTeacherProperties load() {
        Properties raw = new Properties();
        try (InputStream input = PropertiesAppConfigurationService.class.getResourceAsStream(CONFIG_RESOURCE)) {
            if (input == null) {
                throw new SqlTeacherException("CONFIG_NOT_FOUND", "Missing application.properties");
            }
            raw.load(input);
        } catch (IOException ex) {
            throw new SqlTeacherException("CONFIG_LOAD_FAILED", "Failed to load application.properties", ex);
        }

        Path dataDirectory = Path.of(raw.getProperty("sqlteacher.data.dir", "app-data"));
        DatabaseProperties database = new DatabaseProperties(
            Path.of(raw.getProperty("sqlteacher.database.app.path", "app-data/app.db")),
            Path.of(raw.getProperty("sqlteacher.database.demo.path", "app-data/demo.db"))
        );
        AiModelProperties ai = new AiModelProperties(
            URI.create(raw.getProperty("sqlteacher.ai.ollama.base-url", "http://localhost:11434")),
            Duration.ofMillis(Long.parseLong(raw.getProperty("sqlteacher.ai.ollama.health-timeout-ms", "2000")))
        );

        return new SqlTeacherProperties(
            raw.getProperty("sqlteacher.app.name", "SQLTeacher"),
            dataDirectory,
            database,
            ai
        );
    }
}
