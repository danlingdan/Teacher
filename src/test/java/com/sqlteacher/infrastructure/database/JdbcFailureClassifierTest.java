package com.sqlteacher.infrastructure.database;

import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdbcFailureClassifierTest {
    @Test
    void shouldClassifyMysqlAuthenticationAndPermissionFailures() {
        assertEquals(
            JdbcFailureClassifier.JdbcFailure.AUTHENTICATION,
            JdbcFailureClassifier.classify(new SQLException("sensitive", "28000", 1045))
        );
        assertEquals(
            JdbcFailureClassifier.JdbcFailure.PERMISSION,
            JdbcFailureClassifier.classify(new SQLException("sensitive", "42000", 1142))
        );
    }

    @Test
    void shouldClassifyTimeoutsAndConnectionFailuresThroughCauseChains() {
        assertEquals(
            JdbcFailureClassifier.JdbcFailure.TIMEOUT,
            JdbcFailureClassifier.classify(new SQLTimeoutException("sensitive", "HYT00"))
        );
        assertEquals(
            JdbcFailureClassifier.JdbcFailure.TIMEOUT,
            JdbcFailureClassifier.classify(new SQLException("wrapper", new SocketTimeoutException("address")))
        );
        SQLException communicationsFailure = new SQLException("wrapper", "08S01");
        communicationsFailure.initCause(new SocketTimeoutException("address"));
        assertEquals(
            JdbcFailureClassifier.JdbcFailure.TIMEOUT,
            JdbcFailureClassifier.classify(communicationsFailure)
        );
        assertEquals(
            JdbcFailureClassifier.JdbcFailure.CONNECTION,
            JdbcFailureClassifier.classify(new SQLException("sensitive", "08S01"))
        );
    }

    @Test
    void shouldLeaveSyntaxErrorsAsSqlFailures() {
        assertEquals(
            JdbcFailureClassifier.JdbcFailure.SQL,
            JdbcFailureClassifier.classify(new SQLException("syntax details", "42000", 1064))
        );
    }
}
