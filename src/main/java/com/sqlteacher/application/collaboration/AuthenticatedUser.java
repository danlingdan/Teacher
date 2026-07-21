package com.sqlteacher.application.collaboration;

import java.util.Objects;
import java.util.Set;

public record AuthenticatedUser(String id, String email, String displayName, Set<UserRole> roles) {
    public AuthenticatedUser {
        if (id == null || id.isBlank() || email == null || email.isBlank() || displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("User fields must not be blank");
        }
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles must not be null"));
    }

    public boolean hasRole(UserRole role) {
        return roles.contains(Objects.requireNonNull(role, "role must not be null"));
    }
}
