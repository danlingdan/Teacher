package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.connection.DatabaseConnectionProfile;
import com.sqlteacher.application.connection.DatabaseDialect;
import com.sqlteacher.application.connection.ServerConnectionTarget;
import com.sqlteacher.application.connection.SqliteConnectionTarget;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.mariadb.jdbc.Configuration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;

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

    @Test
    void shouldOpenAProfileWithTimeoutAndReadOnlySettings() throws Exception {
        Path tempDirectory = Files.createTempDirectory("sqlteacher-profile-test");
        Path databasePath = tempDirectory.resolve("readonly.db");
        DatabaseConfiguration configuration = new DatabaseConfiguration(
            tempDirectory.resolve("app.db"),
            tempDirectory.resolve("demo.db")
        );
        JdbcConnectionFactory factory = new JdbcConnectionFactory(configuration);
        DatabaseConnectionProfile writableProfile = new DatabaseConnectionProfile(
            "custom.sqlite",
            "Custom SQLite",
            new SqliteConnectionTarget(databasePath),
            false,
            true,
            false
        );

        try (Connection connection = factory.open(writableProfile, new char[0], Duration.ofSeconds(1))) {
            connection.createStatement().executeUpdate("create table sample(id integer primary key)");
        }

        assertTrue(Files.exists(databasePath));
    }

    @Test
    void shouldRejectDisabledProfileAndInvalidTimeout() throws Exception {
        Path tempDirectory = Files.createTempDirectory("sqlteacher-disabled-test");
        JdbcConnectionFactory factory = new JdbcConnectionFactory(new DatabaseConfiguration(
            tempDirectory.resolve("app.db"),
            tempDirectory.resolve("demo.db")
        ));
        DatabaseConnectionProfile disabled = new DatabaseConnectionProfile(
            "disabled.sqlite",
            "Disabled SQLite",
            new SqliteConnectionTarget(tempDirectory.resolve("disabled.db")),
            false,
            false,
            false
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> factory.open(disabled, new char[0], Duration.ofSeconds(1))
        );
        DatabaseConnectionProfile enabled = new DatabaseConnectionProfile(
            "enabled.sqlite",
            "Enabled SQLite",
            new SqliteConnectionTarget(tempDirectory.resolve("enabled.db")),
            false,
            true,
            false
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> factory.open(enabled, new char[0], Duration.ZERO)
        );
    }

    @Test
    void shouldBuildHardenedMysqlDriverConfiguration() throws Exception {
        ServerConnectionTarget target = new ServerConnectionTarget(
            DatabaseDialect.MYSQL,
            "db.internal",
            3307,
            "course",
            "teacher"
        );

        MysqlDataSource dataSource = JdbcConnectionFactory.mysqlDataSource(
            target,
            "temporary".toCharArray(),
            Duration.ofMillis(1250)
        );

        assertEquals("db.internal", dataSource.getServerName());
        assertEquals(3307, dataSource.getPort());
        assertEquals("course", dataSource.getDatabaseName());
        assertEquals("teacher", dataSource.getUser());
        assertEquals(1250, dataSource.getConnectTimeout());
        assertEquals(1250, dataSource.getSocketTimeout());
        assertFalse(dataSource.getAllowMultiQueries());
        assertFalse(dataSource.getAllowLoadLocalInfile());
    }

    @Test
    void shouldBuildHardenedMariaDbDriverConfiguration() {
        ServerConnectionTarget target = new ServerConnectionTarget(
            DatabaseDialect.MARIADB,
            "maria.internal",
            3308,
            "lesson",
            "student"
        );

        Configuration configuration = JdbcConnectionFactory.mariaDbConfiguration(
            target,
            "temporary".toCharArray(),
            Duration.ofMillis(1750)
        );

        assertEquals("maria.internal", configuration.addresses().getFirst().host);
        assertEquals(3308, configuration.addresses().getFirst().port);
        assertEquals("lesson", configuration.database());
        assertEquals("student", configuration.user());
        assertEquals(1750, configuration.connectTimeout());
        assertEquals(1750, configuration.socketTimeout());
        assertFalse(configuration.allowMultiQueries());
        assertFalse(configuration.allowLocalInfile());
        assertFalse(configuration.dumpQueriesOnException());
    }

    @Test
    void shouldRejectDriverConfigurationForTheWrongDialect() {
        ServerConnectionTarget mariaDb = new ServerConnectionTarget(
            DatabaseDialect.MARIADB,
            "localhost",
            3306,
            "course",
            "teacher"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> JdbcConnectionFactory.mysqlDataSource(
                mariaDb,
                new char[0],
                Duration.ofSeconds(1)
            )
        );
    }
}
