package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.DatabaseConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

class JdbcConnectionFactoryTest {

    @Test
    void shouldOpenDemoConnection() throws Exception {

        Path tempDirectory = Files.createTempDirectory("sqlteacher-test");

        DatabaseConfiguration configuration =
                new DatabaseConfiguration(
                        tempDirectory.resolve("app.db"),
                        tempDirectory.resolve("demo.db")
                );

        JdbcConnectionFactory factory =
                new JdbcConnectionFactory(configuration);

        try (Connection connection = factory.open("demo")) {

            assertNotNull(connection);
            assertFalse(connection.isClosed());

        }
    }

    @Test
    void shouldRejectUnknownConnection() throws Exception {

        Path tempDirectory = Files.createTempDirectory("sqlteacher-test");

        DatabaseConfiguration configuration =
                new DatabaseConfiguration(
                        tempDirectory.resolve("app.db"),
                        tempDirectory.resolve("demo.db")
                );

        JdbcConnectionFactory factory =
                new JdbcConnectionFactory(configuration);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.open("mysql")
        );

        assertEquals(
                "Unknown connectionId: mysql",
                exception.getMessage()
        );
    }
}