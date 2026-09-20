package com.sqlteacher.infrastructure.cloud;

import com.sqlteacher.application.collaboration.CloudLearningSyncService;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.CloudSyncPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * v3.7.0 TFB-C2: periodic learning-record sync while a cloud session is signed in.
 * Enabled by default (2026-09-20 product decision) and controlled by
 * {@link CloudSyncPreferences#autoSyncEnabled()}; the user pause wins over everything.
 * Failures degrade to a classified log line and never touch the local learning flow.
 */
public final class LearningAutoSyncService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LearningAutoSyncService.class);
    private static final long INITIAL_DELAY_SECONDS = 120;
    private static final long INTERVAL_SECONDS = 5 * 60;

    private final CloudLearningSyncService syncService;
    private final CloudSessionService sessions;
    private final CloudSyncPreferences preferences;
    private ScheduledExecutorService executor;

    public LearningAutoSyncService(CloudLearningSyncService syncService, CloudSessionService sessions,
            CloudSyncPreferences preferences) {
        this.syncService = syncService;
        this.sessions = sessions;
        this.preferences = preferences;
    }

    /** Starts the daemon scheduler; safe to call once from the wiring. */
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "learning-auto-sync");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tick, INITIAL_DELAY_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    void tick() {
        try {
            if (!preferences.autoSyncEnabled() || preferences.uploadPaused()) {
                return;
            }
            if (sessions.current().isEmpty()) {
                return;
            }
            syncService.synchronize();
        } catch (RuntimeException error) {
            log.info("Automatic learning sync skipped: {}", error.getClass().getSimpleName());
        }
    }

    @Override
    public synchronized void close() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
