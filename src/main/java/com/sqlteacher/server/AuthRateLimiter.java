package com.sqlteacher.server;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Single-process, in-memory failure lockout and fixed-window rate limiting for the cloud API's
 * unauthenticated authentication endpoints and mail-triggering account operations.
 *
 * <p>State is keyed by opaque caller-supplied keys (a normalized email, a rate-limit client
 * principal, or an account id); keys are never logged by this class. Buckets live in bounded
 * maps that are swept lazily on access, so stale entries cannot accumulate without bound on the
 * long-running single-process server. Nothing is persisted; restarting the process resets all
 * counters, which is acceptable because the persistent audit trail and Nginx-level limiting
 * cover long-horizon abuse.</p>
 */
final class AuthRateLimiter {
    /** Failed logins allowed per fixed window before the key is locked out. */
    static final int MAX_FAILURES = 5;
    /** Fixed window over which consecutive failures are counted. */
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    /** How long a key stays locked once the failure threshold is reached. */
    static final Duration LOCKOUT = Duration.ofMinutes(15);

    private static final long SWEEP_INTERVAL_MILLIS = 60_000;

    private final ConcurrentHashMap<String, Window> failures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Window> events = new ConcurrentHashMap<>();
    private final AtomicLong nextSweepAtMillis;
    private final LongSupplier clock;

    AuthRateLimiter() {
        this(System::currentTimeMillis);
    }

    /** Test-visible constructor that allows driving the clock deterministically. */
    AuthRateLimiter(LongSupplier clock) {
        this.clock = clock;
        this.nextSweepAtMillis = new AtomicLong(clock.getAsLong() + SWEEP_INTERVAL_MILLIS);
    }

    /** Throws {@link RateLimitedException} while the key is locked out from repeated failures. */
    void checkLocked(String key) {
        sweepIfDue();
        Window window = failures.get(key);
        long now = clock.getAsLong();
        if (window != null && window.lockedUntilMillis > now) {
            throw new RateLimitedException(window.lockedUntilMillis - now);
        }
    }

    /**
     * Records one failed authentication for the key. The count uses a fixed window anchored at
     * the first failure; reaching {@link #MAX_FAILURES} locks the key for {@link #LOCKOUT} from
     * that first failure. A locked key stays locked for the full lockout even if further
     * failures arrive.
     */
    void recordFailure(String key) {
        sweepIfDue();
        long now = clock.getAsLong();
        failures.compute(key, (ignored, window) -> {
            if (window == null || now - window.windowStartMillis >= FAILURE_WINDOW.toMillis()) {
                window = new Window(now, FAILURE_WINDOW.toMillis());
            }
            window.count++;
            if (window.count >= MAX_FAILURES) {
                window.lockedUntilMillis = window.windowStartMillis + LOCKOUT.toMillis();
            }
            return window;
        });
    }

    /** Clears the failure state for the key after a successful authentication. */
    void clearFailures(String key) {
        failures.remove(key);
    }

    /** Throws {@link RateLimitedException} when the fixed-window quota for the key is exhausted. */
    void checkQuota(String key, int limit) {
        sweepIfDue();
        Window bucket = events.get(key);
        long now = clock.getAsLong();
        if (bucket != null && now - bucket.windowStartMillis < bucket.windowMillis && bucket.count >= limit) {
            throw new RateLimitedException(bucket.windowStartMillis + bucket.windowMillis - now);
        }
    }

    /** Records one event against the key's fixed window. */
    void recordEvent(String key, Duration window) {
        sweepIfDue();
        long now = clock.getAsLong();
        events.compute(key, (ignored, bucket) -> {
            if (bucket == null || now - bucket.windowStartMillis >= window.toMillis()) {
                bucket = new Window(now, window.toMillis());
            }
            bucket.count++;
            return bucket;
        });
    }

    private void sweepIfDue() {
        long now = clock.getAsLong();
        long deadline = nextSweepAtMillis.get();
        if (now < deadline || !nextSweepAtMillis.compareAndSet(deadline, now + SWEEP_INTERVAL_MILLIS)) return;
        sweep(failures, now);
        sweep(events, now);
    }

    private void sweep(ConcurrentHashMap<String, Window> buckets, long now) {
        Iterator<Map.Entry<String, Window>> entries = buckets.entrySet().iterator();
        while (entries.hasNext()) {
            Window window = entries.next().getValue();
            if (now - window.windowStartMillis >= window.windowMillis && window.lockedUntilMillis <= now) {
                entries.remove();
            }
        }
    }

    /** Rejects a request with 429; exposes only a coarse retry hint, never counter details. */
    static final class RateLimitedException extends RuntimeException {
        private final long retryAfterSeconds;

        RateLimitedException(long retryAfterMillis) {
            super("rate limit exceeded");
            this.retryAfterSeconds = Math.max(1, (long) Math.ceil(retryAfterMillis / 1000.0));
        }

        long retryAfterSeconds() { return retryAfterSeconds; }
    }

    /** Mutable per-key counter; guarded by the {@link ConcurrentHashMap} compute functions. */
    private static final class Window {
        private final long windowStartMillis;
        private final long windowMillis;
        private int count;
        private long lockedUntilMillis;

        private Window(long windowStartMillis, long windowMillis) {
            this.windowStartMillis = windowStartMillis;
            this.windowMillis = windowMillis;
        }
    }
}
