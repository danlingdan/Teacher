package com.sqlteacher.application.event;

/**
 * v3.8.0 ACC-D4: local learning records written while signed out (guest identity) and their
 * one-time adoption into a signed-in account. Adoption is explicit — guest records never join
 * the cloud silently.
 */
public interface LocalRecordOwnershipService {

    /** Counts local learning events and SQL history rows still owned by the guest identity. */
    GuestRecordCount countGuestRecords();

    /**
     * Adopts every guest-owned local record into the currently signed-in account and returns
     * the merged row count. Rejects when no account is signed in.
     */
    long mergeGuestRecordsIntoCurrentUser();

    record GuestRecordCount(long learningEvents, long sqlHistory) {
        public long total() {
            return learningEvents + sqlHistory;
        }
    }
}
