package com.sqlteacher.infrastructure.database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MysqlReadOnlyIntegrationVerifierTest {
    @Test
    void shouldQuoteOnlyGeneratedSafeIdentifiers() {
        assertEquals(
            "`sqlteacher_verify_a1b2`",
            MysqlReadOnlyIntegrationVerifier.identifier("sqlteacher_verify_a1b2")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> MysqlReadOnlyIntegrationVerifier.identifier("unsafe`; DROP DATABASE mysql")
        );
    }

    @Test
    void shouldRejectUnsafeAdminUserBeforeReadingDatabaseObjects() {
        assertThrows(
            IllegalArgumentException.class,
            () -> MysqlReadOnlyIntegrationVerifier.requireSafeAdminUser("root'@'%")
        );
    }
}
