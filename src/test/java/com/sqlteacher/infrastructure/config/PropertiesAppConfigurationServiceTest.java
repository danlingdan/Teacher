package com.sqlteacher.infrastructure.config;

import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.domain.SqlTeacherException;
import org.junit.jupiter.api.Test;

import java.util.Properties;

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
        assertEquals("http://localhost:11434", properties.ai().ollamaBaseUrl().toString());
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
