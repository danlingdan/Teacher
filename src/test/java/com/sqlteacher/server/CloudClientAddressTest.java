package com.sqlteacher.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Covers the rate-limit principal resolution for loopback-bound deployments behind Nginx. */
class CloudClientAddressTest {

    @Test void loopbackPeerUsesLastForwardedForEntry() {
        assertEquals("198.51.100.7",
            SqlTeacherCloudServer.resolveClientAddress(true, "127.0.0.1", "203.0.113.4, 198.51.100.7"));
        assertEquals("198.51.100.7",
            SqlTeacherCloudServer.resolveClientAddress(true, "127.0.0.1", "198.51.100.7"));
        assertEquals("203.0.113.9",
            SqlTeacherCloudServer.resolveClientAddress(true, "::1", "203.0.113.4, 198.51.100.7,203.0.113.9"),
            "the last entry is the address observed by the trusted proxy");
    }

    @Test void loopbackPeerWithoutUsableHeaderFallsBackToUnknown() {
        assertEquals("unknown", SqlTeacherCloudServer.resolveClientAddress(true, "127.0.0.1", null));
        assertEquals("unknown", SqlTeacherCloudServer.resolveClientAddress(true, "127.0.0.1", "  "));
        assertEquals("unknown", SqlTeacherCloudServer.resolveClientAddress(true, "127.0.0.1", " , , "));
    }

    @Test void nonLoopbackPeerIgnoresForwardedForAndKeepsDirectAddress() {
        assertEquals("203.0.113.4",
            SqlTeacherCloudServer.resolveClientAddress(false, "203.0.113.4", "198.51.100.7"));
        assertEquals("203.0.113.4",
            SqlTeacherCloudServer.resolveClientAddress(false, "203.0.113.4", null));
    }
}
