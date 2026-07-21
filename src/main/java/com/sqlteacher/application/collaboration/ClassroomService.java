package com.sqlteacher.application.collaboration;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public interface ClassroomService {
    Classroom create(AuthenticatedUser actor, String name);

    Classroom addMember(AuthenticatedUser actor, String classroomId, String userId, UserRole role);

    List<Classroom> listVisibleTo(AuthenticatedUser actor);

    record Classroom(String id, String name, Instant createdAt, List<Member> members) {
        public Classroom {
            if (id == null || id.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("Classroom fields must not be blank");
            }
            Objects.requireNonNull(createdAt, "createdAt must not be null");
            members = List.copyOf(Objects.requireNonNull(members, "members must not be null"));
        }
    }

    record Member(String userId, UserRole role) {
        public Member {
            if (userId == null || userId.isBlank()) {
                throw new IllegalArgumentException("userId must not be blank");
            }
            Objects.requireNonNull(role, "role must not be null");
            if (role == UserRole.ADMIN) {
                throw new IllegalArgumentException("ADMIN is not a classroom membership role");
            }
        }
    }
}
