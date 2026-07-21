package com.sqlteacher.infrastructure.database;

import com.sqlteacher.infrastructure.environment.VerificationStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdbcTechnologyVerifierTest {
    private final JdbcTechnologyVerifier verifier = new JdbcTechnologyVerifier();

    @Test
    void shouldExecuteSqliteInMemoryQuery() {
        assertEquals(VerificationStatus.PASS, verifier.verifySqliteInMemoryQuery().status());
    }

    @Test
    void shouldLoadMysqlDriver() {
        assertEquals(VerificationStatus.PASS, verifier.verifyMysqlDriverAvailable().status());
    }

    @Test
    void shouldLoadMariaDbDriver() {
        assertEquals(VerificationStatus.PASS, verifier.verifyMariaDbDriverAvailable().status());
    }
}
