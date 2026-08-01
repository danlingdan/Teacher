package com.sqlteacher.application.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RolloutDeciderTest {

    @Test void nullOrFullyVisibleRolloutAlwaysVisible() {
        assertTrue(RolloutDecider.visible("install-1", "1.11.0", "windows", null));
        assertTrue(RolloutDecider.visible("install-1", "1.11.0", "windows", new Rollout(100, false)));
    }

    @Test void pausedOrZeroPercentageHidesEverything() {
        assertFalse(RolloutDecider.visible("install-1", "1.11.0", "windows", new Rollout(100, true)));
        assertFalse(RolloutDecider.visible("install-1", "1.11.0", "windows", new Rollout(0, false)));
    }

    @Test void bucketIsDeterministicForSameInputs() {
        assertEquals(RolloutDecider.bucket("install-a", "1.11.0", "windows"), RolloutDecider.bucket("install-a", "1.11.0", "windows"));
    }

    @Test void bucketChangesAcrossVersions() {
        assertNotEquals(RolloutDecider.bucket("install-a", "1.11.0", "windows"), RolloutDecider.bucket("install-a", "1.11.1", "windows"));
    }

    @Test void differentInstallationsSpreadAcrossBuckets() {
        int[] seen = new int[100];
        for (int index = 0; index < 200; index++) seen[RolloutDecider.bucket("install-" + index, "1.11.0", "windows")]++;
        int used = 0;
        for (int value : seen) if (value > 0) used++;
        assertTrue(used >= 80, "buckets should be well distributed, used=" + used);
    }

    @Test void rejectsOutOfRangePercentage() {
        assertThrows(IllegalArgumentException.class, () -> new Rollout(101, false));
        assertThrows(IllegalArgumentException.class, () -> new Rollout(-1, false));
    }

    @Test void restrictedRolloutHidesSomeInstallationsAndRevealsSome() {
        Rollout twenty = new Rollout(20, false);
        boolean anyVisible = false, anyHidden = false;
        for (int index = 0; index < 100; index++) {
            if (RolloutDecider.visible("install-" + index, "1.11.0", "windows", twenty)) anyVisible = true;
            else anyHidden = true;
        }
        assertTrue(anyVisible);
        assertTrue(anyHidden);
    }
}
