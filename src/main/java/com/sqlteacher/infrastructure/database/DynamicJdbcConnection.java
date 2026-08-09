package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.connection.GenericJdbcConnectionTarget;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;

/** Loads one user-selected JDBC driver without adding it to the application class path. */
final class DynamicJdbcConnection {
    private DynamicJdbcConnection() { }

    static Connection open(GenericJdbcConnectionTarget target, char[] password) throws SQLException {
        if (!java.nio.file.Files.isRegularFile(target.driverJar())) {
            throw new SQLException("JDBC driver JAR does not exist");
        }
        URLClassLoader loader = null;
        try {
            loader = new URLClassLoader(
                new java.net.URL[]{target.driverJar().toUri().toURL()},
                DynamicJdbcConnection.class.getClassLoader()
            );
            Class<?> driverType = Class.forName(target.driverClass(), true, loader);
            if (!Driver.class.isAssignableFrom(driverType)) {
                throw new SQLException("Configured JDBC driver class does not implement java.sql.Driver");
            }
            Driver driver = (Driver) driverType.getDeclaredConstructor().newInstance();
            Properties properties = new Properties();
            if (!target.username().isBlank()) properties.setProperty("user", target.username());
            if (password.length > 0) properties.setProperty("password", new String(password));
            Connection delegate = driver.connect(target.jdbcUrl(), properties);
            if (delegate == null) throw new SQLException("Configured JDBC driver did not accept the URL");
            URLClassLoader ownedLoader = loader;
            loader = null;
            return (Connection) Proxy.newProxyInstance(
                DynamicJdbcConnection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if ("close".equals(method.getName())) ownedLoader.close();
                        return result;
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                }
            );
        } catch (SQLException error) {
            throw error;
        } catch (Exception error) {
            throw new SQLException("Unable to load configured JDBC driver", error);
        } finally {
            if (loader != null) try { loader.close(); } catch (java.io.IOException ignored) { }
        }
    }
}
