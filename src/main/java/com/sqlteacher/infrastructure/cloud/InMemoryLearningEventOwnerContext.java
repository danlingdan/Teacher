package com.sqlteacher.infrastructure.cloud;

import com.sqlteacher.application.event.LearningEventOwnerProvider;

/** Process-local identity boundary used to keep guest and account learning records separated. */
public final class InMemoryLearningEventOwnerContext implements LearningEventOwnerProvider {
    private volatile String ownerId = GUEST_OWNER;

    @Override
    public String currentOwnerId() {
        return ownerId;
    }

    public void useGuest() {
        ownerId = GUEST_OWNER;
    }

    public void useAuthenticatedUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        ownerId = userId.trim();
    }
}
