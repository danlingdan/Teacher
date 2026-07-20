package com.sqlteacher.application.connection;

import java.util.Objects;
import java.util.regex.Pattern;

public record DatabaseConnectionProfile(
    String id,
    String displayName,
    DatabaseConnectionTarget target,
    boolean readOnly,
    boolean enabled,
    boolean builtIn
) {
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public DatabaseConnectionProfile {
        id = requireText(id, "id");
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                "id must contain 1-64 lowercase letters, digits, dots, underscores or hyphens"
            );
        }
        displayName = requireText(displayName, "displayName");
        Objects.requireNonNull(target, "target must not be null");
    }

    public DatabaseDialect dialect() {
        return target.dialect();
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
