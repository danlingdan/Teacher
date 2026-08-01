package com.sqlteacher.application.system;

import org.junit.jupiter.api.Test;

import static com.sqlteacher.application.system.ResourcePolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourcePolicyTest {

    @Test void meteredNetworkAndLowBatteryPauseNonCriticalTasks() {
        Decision decision = ResourcePolicy.evaluate(NetworkMode.METERED, BatteryLevel.LOW, false);
        assertTrue(decision.pausesAnything());
        assertEquals("METERED_AND_LOW_BATTERY", decision.reasonCode());
    }

    @Test void meteredNetworkAlonePausesAllHeavyTasks() {
        Decision decision = ResourcePolicy.evaluate(NetworkMode.METERED, BatteryLevel.NORMAL, false);
        assertTrue(decision.pauseUpdateDownload());
        assertTrue(decision.pauseIndexing());
        assertTrue(decision.pauseBackup());
    }

    @Test void normalConditionsPauseNothing() {
        Decision decision = ResourcePolicy.evaluate(NetworkMode.UNMETERED, BatteryLevel.NORMAL, false);
        assertFalse(decision.pausesAnything());
        assertEquals("NONE", decision.reasonCode());
    }

    @Test void userOverrideAlwaysProceeds() {
        Decision decision = ResourcePolicy.evaluate(NetworkMode.METERED, BatteryLevel.LOW, true);
        assertFalse(decision.pausesAnything());
        assertEquals("USER_OVERRIDE", decision.reasonCode());
    }
}
