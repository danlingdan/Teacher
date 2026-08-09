package com.sqlteacher.application.collaboration;

import java.util.Set;

public record CloudCapabilityProfile(String apiVersion, String minimumClientVersion,
                                     Set<String> capabilities, int maximumSyncBatch,
                                     int maximumSummaryBytes) {
    public CloudCapabilityProfile {
        if (apiVersion == null || apiVersion.isBlank() || minimumClientVersion == null
                || minimumClientVersion.isBlank() || capabilities == null
                || maximumSyncBatch < 1 || maximumSummaryBytes < 1) {
            throw new IllegalArgumentException("Cloud capability profile is invalid");
        }
        capabilities = Set.copyOf(capabilities);
    }

    public boolean supports(String capability) { return capabilities.contains(capability); }
}
