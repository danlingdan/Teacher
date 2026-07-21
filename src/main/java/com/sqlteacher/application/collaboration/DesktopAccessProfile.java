package com.sqlteacher.application.collaboration;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Effective desktop identity and its least-privilege navigation capabilities. */
public record DesktopAccessProfile(
    Kind kind,
    String userId,
    String displayName,
    String email,
    Set<UserRole> serverRoles,
    Set<DesktopCapability> capabilities
) {
    private static final Set<DesktopCapability> GUEST_CAPABILITIES = Set.copyOf(EnumSet.of(
        DesktopCapability.HOME,
        DesktopCapability.SQL_PRACTICE,
        DesktopCapability.STUDENT_EXERCISE,
        DesktopCapability.AI_ASSISTANT,
        DesktopCapability.TABLE_SCHEMA
    ));

    private static final Set<DesktopCapability> STUDENT_CAPABILITIES = union(
        GUEST_CAPABILITIES,
        DesktopCapability.KNOWLEDGE_CENTER,
        DesktopCapability.CLOUD_CENTER
    );

    private static final Set<DesktopCapability> TEACHER_CAPABILITIES = union(
        STUDENT_CAPABILITIES,
        DesktopCapability.EXERCISE_MANAGEMENT,
        DesktopCapability.EXERCISE_PROGRESS,
        DesktopCapability.SETTINGS
    );

    private static final Set<DesktopCapability> ADMIN_CAPABILITIES = Set.copyOf(
        EnumSet.allOf(DesktopCapability.class)
    );

    public DesktopAccessProfile {
        kind = Objects.requireNonNull(kind, "kind must not be null");
        displayName = requireText(displayName, "displayName");
        userId = userId == null ? "" : userId;
        email = email == null ? "" : email;
        serverRoles = Set.copyOf(Objects.requireNonNull(serverRoles, "serverRoles must not be null"));
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
        if (kind != Kind.GUEST && (userId.isBlank() || email.isBlank())) {
            throw new IllegalArgumentException("Authenticated identity requires userId and email");
        }
    }

    public static DesktopAccessProfile guest() {
        return new DesktopAccessProfile(Kind.GUEST, "", "访客", "", Set.of(), GUEST_CAPABILITIES);
    }

    public static DesktopAccessProfile from(CloudAuthenticationService.Session session) {
        Objects.requireNonNull(session, "session must not be null");
        AuthenticatedUser user = session.user();
        Kind kind;
        Set<DesktopCapability> capabilities;
        if (user.hasRole(UserRole.ADMIN)) {
            kind = Kind.ADMIN;
            capabilities = ADMIN_CAPABILITIES;
        } else if (user.hasRole(UserRole.TEACHER)) {
            kind = Kind.TEACHER;
            capabilities = TEACHER_CAPABILITIES;
        } else {
            kind = Kind.STUDENT;
            capabilities = STUDENT_CAPABILITIES;
        }
        return new DesktopAccessProfile(kind, user.id(), user.displayName(), user.email(), user.roles(), capabilities);
    }

    public boolean can(DesktopCapability capability) {
        return capabilities.contains(Objects.requireNonNull(capability, "capability must not be null"));
    }

    public boolean isGuest() {
        return kind == Kind.GUEST;
    }

    public String roleLabel() {
        return switch (kind) {
            case GUEST -> "访客";
            case STUDENT -> "学生";
            case TEACHER -> "教师";
            case ADMIN -> "管理员";
        };
    }

    private static Set<DesktopCapability> union(
        Set<DesktopCapability> base,
        DesktopCapability... additions
    ) {
        EnumSet<DesktopCapability> result = EnumSet.copyOf(base);
        result.addAll(Set.of(additions));
        return Set.copyOf(result);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    public enum Kind {
        GUEST,
        STUDENT,
        TEACHER,
        ADMIN
    }
}
