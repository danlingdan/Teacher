package com.sqlteacher.infrastructure.config;

import com.sqlteacher.application.config.SqlTeacherConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertiesAppConfigurationServiceTest {
    @Test
    void shouldLoadDefaultApplicationProperties() {
        SqlTeacherConfiguration properties = new PropertiesAppConfigurationService().current();

        assertEquals("SQLTeacher", properties.appName());
        assertTrue(properties.database().appDatabasePath().toString().endsWith("app.db"));
        assertEquals("http://localhost:11434", properties.ai().ollamaBaseUrl().toString());
    }
}
