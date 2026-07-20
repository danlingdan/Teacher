package com.sqlteacher.application.connection;

/** Tests a connection without exposing JDBC details to desktop callers. */
public interface DatabaseConnectionTestService {
    /**
     * Tests the supplied profile with a transient password.
     *
     * <p>The implementation must not retain, log, or include the password in its result. The caller owns the
     * array and should clear it after this method returns.</p>
     */
    DatabaseConnectionTestResult testConnection(DatabaseConnectionProfile profile, char[] password);
}
