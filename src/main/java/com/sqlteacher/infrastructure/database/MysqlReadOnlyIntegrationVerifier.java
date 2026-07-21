package com.sqlteacher.infrastructure.database;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.sqlteacher.application.connection.DatabaseDialect;
import com.sqlteacher.application.connection.ServerConnectionTarget;

import java.io.Console;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Local-only destructive integration verifier for an isolated MySQL database and account.
 * It must be run interactively and always attempts to remove every object it creates.
 */
public final class MysqlReadOnlyIntegrationVerifier {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 3306;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Path STATUS_FILE = Path.of("target", "mysql-readonly-verification.status");

    private MysqlReadOnlyIntegrationVerifier() {
    }

    public static void main(String[] args) {
        writeStatus("RUNNING");
        Console console = System.console();
        if (console == null) {
            writeStatus("FAIL:NO_CONSOLE");
            System.err.println("[FAIL] Interactive console is required; no password was read.");
            System.exit(2);
        }

        String adminUser = args.length == 0 ? "root" : requireSafeAdminUser(args[0]);
        char[] adminPassword = console.readPassword("MySQL admin password for %s@%s:%d: ", adminUser, HOST, PORT);
        if (adminPassword == null || adminPassword.length == 0) {
            clear(adminPassword);
            writeStatus("FAIL:NO_PASSWORD");
            System.err.println("[FAIL] Admin password was not provided.");
            System.exit(2);
        }

        try {
            VerificationResult result = verify(adminUser, adminPassword);
            System.out.println("[PASS] MySQL isolated read-only account verification");
            System.out.println("[PASS] Read query and current-catalog metadata succeeded");
            System.out.println("[PASS] INSERT and mysql.user access were denied by server permissions");
            System.out.println("[PASS] Temporary account and database were removed");
            System.out.printf("[INFO] Server version: %s%n", result.serverVersion());
            writeStatus("PASS:" + result.serverVersion());
        } catch (Exception error) {
            JdbcFailureClassifier.JdbcFailure failure = JdbcFailureClassifier.classify(error);
            System.err.printf(
                "[FAIL] Verification failed safely: type=%s, sqlState=%s, vendorCode=%d%n",
                failure,
                JdbcFailureClassifier.sqlState(error),
                JdbcFailureClassifier.vendorCode(error)
            );
            writeStatus(
                "FAIL:" + failure + ":" + JdbcFailureClassifier.sqlState(error)
                    + ":" + JdbcFailureClassifier.vendorCode(error)
            );
            System.exit(1);
        } finally {
            clear(adminPassword);
        }
    }

    static VerificationResult verify(String adminUser, char[] adminPassword) throws SQLException {
        String suffix = randomSuffix();
        String database = "sqlteacher_verify_" + suffix;
        String username = "sqlteacher_ro_" + suffix;
        char[] limitedPassword = null;
        boolean databaseCreated = false;
        boolean userCreated = false;

        ServerConnectionTarget adminTarget = target("mysql", adminUser);
        try (Connection admin = open(adminTarget, adminPassword)) {
            String serverVersion = admin.getMetaData().getDatabaseProductVersion();
            try {
                databaseCreated = true;
                execute(admin, "CREATE DATABASE " + identifier(database));
                execute(admin, "CREATE TABLE " + identifier(database) + ".lesson_sample ("
                    + "id INTEGER PRIMARY KEY, title VARCHAR(100) NOT NULL)");
                execute(admin, "INSERT INTO " + identifier(database)
                    + ".lesson_sample (id, title) VALUES (1, 'read-only verification')");
                userCreated = true;
                limitedPassword = createUserWithRandomPassword(admin, username);
                execute(admin, "GRANT SELECT ON " + identifier(database) + ".* TO " + account(username));

                verifyLimitedAccount(database, username, limitedPassword);
                limitedPassword = null;
                return new VerificationResult(serverVersion);
            } finally {
                clear(limitedPassword);
                cleanup(admin, username, userCreated, database, databaseCreated);
            }
        }
    }

    private static void verifyLimitedAccount(
            String database,
            String username,
            char[] password
    ) throws SQLException {
        try (Connection limited = open(target(database, username), password)) {
            try (Statement statement = limited.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM lesson_sample")) {
                if (!rows.next() || rows.getInt(1) != 1) {
                    throw new SQLException("Unexpected isolated query result");
                }
            }

            verifyCurrentCatalogMetadata(limited, database);
            expectPermissionDenied(
                limited,
                "INSERT INTO lesson_sample (id, title) VALUES (2, 'must be denied')"
            );
            expectPermissionDenied(limited, "SELECT COUNT(*) FROM mysql.user");
        } finally {
            clear(password);
        }
    }

    private static void verifyCurrentCatalogMetadata(Connection connection, String database) throws SQLException {
        if (!database.equals(connection.getCatalog())) {
            throw new SQLException("Connection catalog does not match isolated database");
        }
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet tables = metadata.getTables(database, null, "lesson_sample", new String[]{"TABLE"})) {
            if (!tables.next() || !"lesson_sample".equals(tables.getString("TABLE_NAME"))) {
                throw new SQLException("Isolated metadata did not contain lesson_sample");
            }
        }
    }

    private static void expectPermissionDenied(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            throw new SQLException("Read-only account unexpectedly executed a forbidden statement");
        } catch (SQLException error) {
            if (JdbcFailureClassifier.classify(error) != JdbcFailureClassifier.JdbcFailure.PERMISSION) {
                throw error;
            }
        }
    }

    private static Connection open(ServerConnectionTarget target, char[] password) throws SQLException {
        MysqlDataSource dataSource = JdbcConnectionFactory.mysqlDataSource(target, password, TIMEOUT);
        return dataSource.getConnection();
    }

    private static ServerConnectionTarget target(String database, String username) {
        return new ServerConnectionTarget(DatabaseDialect.MYSQL, HOST, PORT, database, username);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static char[] createUserWithRandomPassword(Connection connection, String username)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            boolean hasResult = statement.execute(
                "CREATE USER " + account(username) + " IDENTIFIED BY RANDOM PASSWORD"
            );
            if (!hasResult) {
                throw new SQLException("MySQL did not return a generated account password");
            }
            try (ResultSet result = statement.getResultSet()) {
                if (!result.next()) {
                    throw new SQLException("MySQL returned an empty generated-password result");
                }
                char[] buffer = new char[256];
                try (Reader passwordReader = result.getCharacterStream("generated password")) {
                    if (passwordReader == null) {
                        throw new SQLException("MySQL returned an empty generated password");
                    }
                    int length = 0;
                    int read;
                    while (length < buffer.length
                            && (read = passwordReader.read(buffer, length, buffer.length - length)) != -1) {
                        length += read;
                    }
                    if (length == 0 || passwordReader.read() != -1) {
                        throw new SQLException("MySQL returned an invalid generated password");
                    }
                    return Arrays.copyOf(buffer, length);
                } catch (IOException error) {
                    throw new SQLException("Could not read the generated account password", error);
                } finally {
                    Arrays.fill(buffer, '\0');
                }
            }
        }
    }

    private static void cleanup(
            Connection connection,
            String username,
            boolean userCreated,
            String database,
            boolean databaseCreated
    ) throws SQLException {
        SQLException failure = null;
        if (userCreated) {
            try {
                execute(connection, "DROP USER IF EXISTS " + account(username));
            } catch (SQLException error) {
                failure = error;
            }
        }
        if (databaseCreated) {
            try {
                execute(connection, "DROP DATABASE IF EXISTS " + identifier(database));
            } catch (SQLException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    static String identifier(String value) {
        if (!value.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Unsafe generated identifier");
        }
        return "`" + value + "`";
    }

    private static String account(String username) {
        return "'" + username + "'@'localhost'";
    }

    private static String randomSuffix() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String requireSafeAdminUser(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{1,32}")) {
            throw new IllegalArgumentException("Admin username contains unsupported characters");
        }
        return value;
    }

    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    private static void writeStatus(String status) {
        try {
            Files.createDirectories(STATUS_FILE.getParent());
            Files.writeString(STATUS_FILE, status + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            System.err.println("[WARNING] Could not write the local verification status file.");
        }
    }

    record VerificationResult(String serverVersion) {
    }
}
