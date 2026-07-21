package com.sqlteacher.application.event;

/** Supplies the local identity that owns newly recorded learning events. */
@FunctionalInterface
public interface LearningEventOwnerProvider {
    String OWNER_ATTRIBUTE = "_desktop_owner_id";
    String GUEST_OWNER = "guest";

    String currentOwnerId();
}
