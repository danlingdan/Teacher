package com.sqlteacher.application.collaboration;

import java.time.Instant;
import java.util.Set;

public record AdminUserSummary(String id, String email, String displayName, Set<UserRole> roles,
                               boolean disabled, Instant createdAt) {
    public AdminUserSummary {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
