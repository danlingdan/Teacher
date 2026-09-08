package com.sqlteacher.server;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AuthRateLimiterTest {

    @Test void locksAfterFiveFailuresAndReleasesAfterLockout() {
        AtomicLong now = new AtomicLong(0);
        AuthRateLimiter limiter = new AuthRateLimiter(now::get);
        String key = "login:user@example.com";

        for (int index = 0; index < AuthRateLimiter.MAX_FAILURES; index++) {
            assertFalse(isLocked(limiter, key), "must not lock before the threshold, attempt " + (index + 1));
            limiter.recordFailure(key);
        }
        assertTrue(isLocked(limiter, key), "fifth failure must lock the key");

        now.addAndGet(AuthRateLimiter.LOCKOUT.toMillis() + 1);
        assertFalse(isLocked(limiter, key), "lockout must expire");
    }

    @Test void clearFailuresRemovesTheLockoutAndCounter() {
        AtomicLong now = new AtomicLong(0);
        AuthRateLimiter limiter = new AuthRateLimiter(now::get);
        String key = "login:user@example.com";
        for (int index = 0; index < AuthRateLimiter.MAX_FAILURES; index++) limiter.recordFailure(key);
        assertTrue(isLocked(limiter, key));

        limiter.clearFailures(key);
        assertFalse(isLocked(limiter, key));
        for (int index = 0; index < AuthRateLimiter.MAX_FAILURES - 1; index++) limiter.recordFailure(key);
        assertFalse(isLocked(limiter, key), "counter must restart after clearFailures");
    }

    @Test void failuresOutsideTheFixedWindowDoNotAccumulate() {
        AtomicLong now = new AtomicLong(0);
        AuthRateLimiter limiter = new AuthRateLimiter(now::get);
        String key = "login:user@example.com";

        for (int index = 0; index < AuthRateLimiter.MAX_FAILURES - 1; index++) limiter.recordFailure(key);
        now.addAndGet(AuthRateLimiter.FAILURE_WINDOW.toMillis() + 1);
        limiter.recordFailure(key);
        assertFalse(isLocked(limiter, key), "window expiry must restart the count");
        for (int index = 0; index < AuthRateLimiter.MAX_FAILURES - 2; index++) limiter.recordFailure(key);
        assertFalse(isLocked(limiter, key), "only failures inside the fresh window count");
        limiter.recordFailure(key);
        assertTrue(isLocked(limiter, key), "fifth failure inside a fresh window must lock");
    }

    @Test void quotaExhaustsAndResetsWithItsWindow() {
        AtomicLong now = new AtomicLong(0);
        AuthRateLimiter limiter = new AuthRateLimiter(now::get);
        String key = "reset:email:user@example.com";
        Duration window = Duration.ofHours(1);

        for (int index = 0; index < 3; index++) {
            limiter.checkQuota(key, 3);
            limiter.recordEvent(key, window);
        }
        assertThrows(AuthRateLimiter.RateLimitedException.class, () -> limiter.checkQuota(key, 3),
            "quota must be exhausted after the limit");

        now.addAndGet(window.toMillis() + 1);
        assertDoesNotThrow(() -> limiter.checkQuota(key, 3), "quota must reset after the window");
    }

    @Test void rateLimitedExceptionCarriesCoarseRetryHint() {
        AtomicLong now = new AtomicLong(0);
        AuthRateLimiter limiter = new AuthRateLimiter(now::get);
        String key = "login:user@example.com";
        for (int index = 0; index < AuthRateLimiter.MAX_FAILURES; index++) limiter.recordFailure(key);

        AuthRateLimiter.RateLimitedException error =
            assertThrows(AuthRateLimiter.RateLimitedException.class, () -> limiter.checkLocked(key));
        assertTrue(error.retryAfterSeconds() > 0 && error.retryAfterSeconds() <= AuthRateLimiter.LOCKOUT.toSeconds(),
            "retry hint must stay within the lockout bounds");
    }

    private static boolean isLocked(AuthRateLimiter limiter, String key) {
        try {
            limiter.checkLocked(key);
            return false;
        } catch (AuthRateLimiter.RateLimitedException error) {
            return true;
        }
    }
}
