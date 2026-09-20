package com.sqlteacher.infrastructure.system;

import com.sqlteacher.application.event.LearningEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * v3.7.0 TFB-D3: records one DAILY_ACTIVE event per ~30 minutes of app-open time. This is a
 * coarse heart-beat of learning-tool usage — no keyboard, mouse, or screen monitoring; the
 * UTC day is derived from the event timestamp on the teacher side by summing activeMinutes.
 * Recording failures degrade silently and never interrupt local learning.
 */
public final class LearningActivityTracker implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LearningActivityTracker.class);
    private static final long INTERVAL_SECONDS = 30 * 60;
    static final int MINUTES_PER_TICK = 30;

    private final LearningEventService eventService;
    private ScheduledExecutorService executor;

    public LearningActivityTracker(LearningEventService eventService) {
        this.eventService = eventService;
    }

    /** Starts the daemon scheduler; safe to call once from the wiring. */
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "learning-activity-tracker");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tick, INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    void tick() {
        try {
            eventService.recordDailyActive(MINUTES_PER_TICK);
        } catch (RuntimeException error) {
            log.info("Daily active event skipped: {}", error.getClass().getSimpleName());
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
