package com.sqlteacher.application.collaboration;

/** Desktop boundary for cloud capability negotiation (v3.4.0 REF-4 port split). */
public interface CloudCapabilityApi {
    default CloudCapabilityProfile capabilities() {
        throw new UnsupportedOperationException("Capability negotiation is unavailable");
    }
}
