package com.sqlteacher.infrastructure.config;

import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.domain.SqlTeacherException;
import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertiesAppConfigurationServiceTest {
    @Test
    void shouldLoadDefaultApplicationProperties() {
        SqlTeacherConfiguration properties = new PropertiesAppConfigurationService().current();

        assertEquals("SQLTeacher", properties.appName());
        assertTrue(properties.database().appDatabasePath().toString().endsWith("app.db"));
        assertTrue(properties.dataDirectory().isAbsolute());
        assertEquals(properties.dataDirectory().resolve("app.db"), properties.database().appDatabasePath());
        assertEquals("http://localhost:11434", properties.ai().ollamaBaseUrl().toString());
    }

    @Test
    void shouldHonorAbsoluteSystemDataDirectoryOverride() {
        String previous = System.getProperty("sqlteacher.data.dir");
        Path override = Path.of("target", "configuration-test-data").toAbsolutePath().normalize();
        try {
            System.setProperty("sqlteacher.data.dir", override.toString());

            SqlTeacherConfiguration configuration = new PropertiesAppConfigurationService().current();

            assertEquals(override, configuration.dataDirectory());
            assertEquals(override.resolve("app.db"), configuration.database().appDatabasePath());
        } finally {
            if (previous == null) {
                System.clearProperty("sqlteacher.data.dir");
            } else {
                System.setProperty("sqlteacher.data.dir", previous);
            }
        }
    }

    @Test
    void shouldWrapInvalidConfigurationValues() {
        Properties raw = new Properties();
        raw.setProperty("sqlteacher.ai.ollama.health-timeout-ms", "0");

        SqlTeacherException exception = assertThrows(
            SqlTeacherException.class,
            () -> new PropertiesAppConfigurationService(raw)
        );

        assertEquals("CONFIG_INVALID", exception.errorCode());
        assertEquals("Invalid value in application.properties", exception.getMessage());
        assertInstanceOf(IllegalArgumentException.class, exception.getCause());
    }
}
