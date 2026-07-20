package com.sqlteacher.infrastructure.database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryDatabaseCredentialSessionTest {
    @Test
    void shouldKeepDefensiveCopiesAndClearCredentials() {
        InMemoryDatabaseCredentialSession session = new InMemoryDatabaseCredentialSession();
        char[] input = "secret".toCharArray();

        session.remember("course.mysql", input);
        input[0] = 'X';
        char[] firstRead = session.passwordFor("course.mysql").orElseThrow();
        assertArrayEquals("secret".toCharArray(), firstRead);

        firstRead[0] = 'Y';
        assertArrayEquals("secret".toCharArray(), session.passwordFor("course.mysql").orElseThrow());

        session.close();
        assertTrue(session.passwordFor("course.mysql").isEmpty());
    }
}
