package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.DatabaseConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class JdbcConnectionFactoryTest {

    @TempDir
    Path tempDir;

    private DatabaseConfiguration configuration;
    private JdbcConnectionFactory factory;

    @BeforeEach
    void setUp() {
        configuration = new DatabaseConfiguration(
                tempDir.resolve("app.db"),
                tempDir.resolve("demo.db")
        );
        factory = new JdbcConnectionFactory(configuration);
    }

    @Test
    void shouldOpenDemoConnection() throws Exception {
        try (Connection connection = factory.open("demo")) {
            assertNotNull(connection);
            assertFalse(connection.isClosed());
        }
    }

    @Test
    void shouldOpenAppConnection() throws Exception {
        try (Connection connection = factory.open("app")) {
            assertNotNull(connection);
            assertFalse(connection.isClosed());
        }
    }

    @Test
    void shouldRejectUnknownConnection() {
        assertThrows(IllegalArgumentException.class, () -> factory.open("mysql"));
    }

    @Test
    void shouldRejectNullConnectionId() {
        assertThrows(NullPointerException.class, () -> factory.open(null));
    }
}