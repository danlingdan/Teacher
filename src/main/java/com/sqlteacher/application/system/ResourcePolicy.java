package com.sqlteacher.application.system;

/**
 * Pauses non-critical background work on metered networks or low battery.
 * Pure decision logic; the desktop layer applies it to task scheduling.
 */
public final class ResourcePolicy {
    public enum NetworkMode { UNMETERED, METERED }
    public enum BatteryLevel { NORMAL, LOW }

    public record Decision(boolean pauseUpdateDownload, boolean pauseIndexing, boolean pauseBackup, String reasonCode) {
        public boolean pausesAnything() { return pauseUpdateDownload || pauseIndexing || pauseBackup; }
    }

    private ResourcePolicy() { }

    /**
     * @param userActionOverride user explicitly asked for the task; overrides policy so
     *        safety-critical and user-initiated work always proceeds.
     */
    public static Decision evaluate(NetworkMode network, BatteryLevel battery, boolean userActionOverride) {
        if (userActionOverride) {
            return new Decision(false, false, false, "USER_OVERRIDE");
        }
        boolean metered = network == NetworkMode.METERED;
        boolean lowBattery = battery == BatteryLevel.LOW;
        if (metered && lowBattery) return new Decision(true, true, true, "METERED_AND_LOW_BATTERY");
        if (metered) return new Decision(true, true, true, "METERED_NETWORK");
        if (lowBattery) return new Decision(true, true, true, "LOW_BATTERY");
        return new Decision(false, false, false, "NONE");
    }
}
