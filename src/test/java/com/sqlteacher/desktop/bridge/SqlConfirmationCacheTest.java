package com.sqlteacher.desktop.bridge;

import com.sqlteacher.application.execution.SqlExecutionResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v3.4.0 REF-8: the extracted SQL confirmation/result cache keeps its one-shot token semantics. */
class SqlConfirmationCacheTest {

    private static SqlConfirmationCache.Confirmation confirmation(String connectionId, Instant expiresAt) {
        return new SqlConfirmationCache.Confirmation(connectionId, SqlConfirmationCache.hash("SELECT 1"), expiresAt);
    }

    private static SqlExecutionResult result(int seed) {
        return new SqlExecutionResult(
            true, List.of("Sno"), List.of(Map.of("Sno", String.valueOf(seed))), 0, false, "", Duration.ZERO);
    }

    @Test
    void confirmationTokensAreConsumedExactlyOnce() {
        SqlConfirmationCache cache = new SqlConfirmationCache();
        cache.rememberConfirmation("token-1", confirmation("demo", Instant.now().plusSeconds(300)));

        SqlConfirmationCache.Confirmation first = cache.takeConfirmation("token-1");
        assertEquals("demo", first.connectionId());
        assertEquals(SqlConfirmationCache.hash("SELECT 1"), first.sqlHash());
        assertTrue(first.expiresAt().isAfter(Instant.now()));

        // 二次消费同一令牌必须为空：一次性确认语义。
        assertNull(cache.takeConfirmation("token-1"));
    }

    @Test
    void hashIsStableSha256Hex() {
        String hash = SqlConfirmationCache.hash("SELECT 1");
        assertEquals(64, hash.length());
        assertEquals(hash, SqlConfirmationCache.hash("SELECT 1"));
    }

    @Test
    void expiryReportsExpiredConnectionIdsAndKeepsLiveEntries() {
        SqlConfirmationCache cache = new SqlConfirmationCache();
        cache.rememberConfirmation("expired-a", confirmation("conn-a", Instant.now().minusSeconds(1)));
        cache.rememberConfirmation("expired-b", confirmation("conn-b", Instant.now().minusSeconds(5)));
        cache.rememberConfirmation("live", confirmation("conn-c", Instant.now().plusSeconds(300)));
        cache.rememberResult("stale", new SqlConfirmationCache.CachedResult(
            result(1), Instant.now().minusSeconds(1)), () -> { });
        cache.rememberResult("fresh", new SqlConfirmationCache.CachedResult(
            result(2), Instant.now().plusSeconds(600)), () -> { });

        List<String> expired = cache.expire();

        assertEquals(Set.copyOf(List.of("conn-a", "conn-b")), Set.copyOf(expired));
        assertNull(cache.takeConfirmation("expired-a"));
        assertNull(cache.takeConfirmation("expired-b"));
        assertEquals("conn-c", cache.takeConfirmation("live").connectionId());
        assertNull(cache.result("stale"), "expired result must be dropped");
        assertEquals("2", cache.result("fresh").result().rows().get(0).get("Sno"));
    }

    @Test
    void resultCacheStaysBoundedAfterExpireAndForcedEviction() {
        SqlConfirmationCache cache = new SqlConfirmationCache();
        AtomicInteger expireCalls = new AtomicInteger();

        for (int i = 0; i < SqlConfirmationCache.MAX_CACHED_RESULTS; i++) {
            cache.rememberResult("result-" + i, new SqlConfirmationCache.CachedResult(
                result(i), Instant.now().plusSeconds(600)), expireCalls::incrementAndGet);
        }
        assertEquals(0, expireCalls.get(), "no expiry needed while at capacity");

        // Over-capacity put must first trigger expiry (live entries survive), then evict one to fit.
        cache.rememberResult("overflow", new SqlConfirmationCache.CachedResult(
            result(999), Instant.now().plusSeconds(600)), expireCalls::incrementAndGet);

        assertEquals(1, expireCalls.get());
        assertEquals("999", cache.result("overflow").result().rows().get(0).get("Sno"));
        int surviving = 0;
        for (int i = 0; i < SqlConfirmationCache.MAX_CACHED_RESULTS; i++) {
            if (cache.result("result-" + i) != null) {
                surviving++;
            }
        }
        assertEquals(SqlConfirmationCache.MAX_CACHED_RESULTS - 1, surviving,
            "exactly one live entry must have been evicted for the overflow");
    }

    @Test
    void clearDropsEverythingForFacadeClose() {
        SqlConfirmationCache cache = new SqlConfirmationCache();
        cache.rememberConfirmation("token", confirmation("demo", Instant.now().plusSeconds(300)));
        cache.rememberResult("result", new SqlConfirmationCache.CachedResult(
            result(1), Instant.now().plusSeconds(600)), () -> { });

        cache.clear();

        assertNull(cache.takeConfirmation("token"));
        assertNull(cache.result("result"));
        assertTrue(cache.expire().isEmpty());
    }
}
