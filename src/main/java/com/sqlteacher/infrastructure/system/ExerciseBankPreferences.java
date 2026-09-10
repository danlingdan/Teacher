package com.sqlteacher.infrastructure.system;

import java.util.List;

/**
 * Local preferences for exercise bank distribution (v3.3 W4.2/W4.3): the subscribed
 * network channels, the opt-in scheduled update check, and the pending "update available"
 * notice recorded by the background check. The notice is display-only and never blocks
 * the local learning flow.
 */
public record ExerciseBankPreferences(
    boolean autoCheckEnabled,
    List<String> subscribedChannels,
    PendingNotice pendingNotice
) {
    public static final String DEFAULT_CHANNEL = "network";

    public ExerciseBankPreferences {
        subscribedChannels = subscribedChannels == null || subscribedChannels.isEmpty()
            ? List.of(DEFAULT_CHANNEL)
            : List.copyOf(subscribedChannels);
    }

    public static ExerciseBankPreferences defaults() {
        return new ExerciseBankPreferences(false, List.of(DEFAULT_CHANNEL), null);
    }

    public record PendingNotice(String channel, int bankVersion, String detectedAt) {
    }
}
