package com.sqlteacher.infrastructure.system;

import com.sqlteacher.infrastructure.database.ExerciseBankSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in scheduled exercise bank update check (v3.3 W4.3). Runs in the background every
 * six hours; when the checked channel has pending content it only records a display
 * notice (shown on the practice page) — it never applies updates or interrupts the local
 * learning flow. Offline or failing checks degrade silently.
 */
public final class ExerciseBankAutoCheckService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ExerciseBankAutoCheckService.class);
    private static final long INITIAL_DELAY_SECONDS = 90;
    private static final long INTERVAL_SECONDS = 6 * 60 * 60;

    private final ExerciseBankSyncService syncService;
    private final ExerciseBankPreferencesStore preferencesStore;
    private ScheduledExecutorService executor;

    public ExerciseBankAutoCheckService(ExerciseBankSyncService syncService, ExerciseBankPreferencesStore store) {
        this.syncService = syncService;
        this.preferencesStore = store;
    }

    /** Starts the daemon scheduler; safe to call once from the wiring. */
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "exercise-bank-auto-check");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tick, INITIAL_DELAY_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    void tick() {
        try {
            ExerciseBankPreferences preferences = preferencesStore.load();
            if (!preferences.autoCheckEnabled()) {
                return;
            }
            for (String channel : preferences.subscribedChannels()) {
                ExerciseBankSyncService.BankUpdateStatus status = syncService.check(channel);
                if (!status.upToDate() && status.pendingBlocks() > 0) {
                    preferencesStore.save(new ExerciseBankPreferences(
                        preferences.autoCheckEnabled(),
                        preferences.subscribedChannels(),
                        new ExerciseBankPreferences.PendingNotice(
                            channel, status.serverVersion(), Instant.now().toString())
                    ));
                    log.info("Exercise bank channel {} has {} pending blocks (version {})",
                        channel, status.pendingBlocks(), status.serverVersion());
                    return;
                }
            }
        } catch (RuntimeException error) {
            log.info("Scheduled exercise bank check failed: {}", error.getClass().getSimpleName());
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
