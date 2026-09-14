package com.sqlteacher.application.collaboration;

import java.util.List;
import java.util.Map;

/** Desktop boundary for the exercise bank distribution channels, manifests, and blocks (v3.4.0 REF-4 port split). */
public interface CloudBankApi {
    default ExerciseBankManifest fetchExerciseBankManifest() {
        return fetchExerciseBankManifest("network");
    }

    default ExerciseBankManifest fetchExerciseBankManifest(String channel) {
        throw new UnsupportedOperationException("Exercise bank distribution is unavailable");
    }

    default ExerciseBankBlock fetchExerciseBankBlock(String type, String id) {
        return fetchExerciseBankBlock("network", type, id);
    }

    default ExerciseBankBlock fetchExerciseBankBlock(String channel, String type, String id) {
        throw new UnsupportedOperationException("Exercise bank distribution is unavailable");
    }

    default int publishExerciseBankPackage(String accessToken, String packageText) {
        return publishExerciseBankPackage(accessToken, "network", packageText);
    }

    default int publishExerciseBankPackage(String accessToken, String channel, String packageText) {
        throw new UnsupportedOperationException("Exercise bank distribution is unavailable");
    }

    /** Rolls the channel's active bank version back for all clients (v3.3 W4.5). */
    default int rollbackExerciseBank(String accessToken, String channel, int bankVersion) {
        throw new UnsupportedOperationException("Exercise bank distribution is unavailable");
    }

    /** Lists server-known channels with active versions; used for client subscriptions. */
    default List<Map<String, Object>> fetchExerciseBankChannels() {
        throw new UnsupportedOperationException("Exercise bank distribution is unavailable");
    }
}
