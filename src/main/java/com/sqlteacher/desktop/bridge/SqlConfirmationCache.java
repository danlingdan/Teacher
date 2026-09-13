package com.sqlteacher.desktop.bridge;

import com.sqlteacher.application.execution.SqlExecutionResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v3.4.0 REF-8: SQL 确认令牌与结果缓存，从 DefaultLocalAppApi 拆出的独立组件，由门面持有并与
 * DataSqlApiSection 共用。沿用 ConcurrentHashMap 与惰性到期清理，语义与拆分前一致；确认令牌
 * 一次性消费，高风险 SQL 只有持有效令牌才可执行。
 */
final class SqlConfirmationCache {

    /** 结果缓存上限：长会话中防止内存无界增长（与拆分前的门面常量一致）。 */
    static final int MAX_CACHED_RESULTS = 64;

    /** One signed confirmation: binds the token to a connection id and the exact SQL hash. */
    record Confirmation(String connectionId, String sqlHash, Instant expiresAt) { }

    /** One executed result kept for paging/export until it expires. */
    record CachedResult(SqlExecutionResult result, Instant expiresAt) { }

    private final Map<String, Confirmation> confirmations = new ConcurrentHashMap<>();
    private final Map<String, CachedResult> results = new ConcurrentHashMap<>();

    void rememberConfirmation(String token, Confirmation confirmation) {
        confirmations.put(token, confirmation);
    }

    /** Removes and returns the pending confirmation so every token can be consumed at most once. */
    Confirmation takeConfirmation(String token) {
        return confirmations.remove(token);
    }

    /**
     * 结果缓存有上限：过期清理后仍满时移除一个条目，避免长会话内存无界增长。{@code expireAction}
     * 先行触发与拆分前相同的到期清理（含审计事件），再判断是否仍需强制驱逐。
     */
    void rememberResult(String resultId, CachedResult cached, Runnable expireAction) {
        if (results.size() >= MAX_CACHED_RESULTS) {
            expireAction.run();
            if (results.size() >= MAX_CACHED_RESULTS) {
                results.keySet().stream().findFirst().ifPresent(results::remove);
            }
        }
        results.put(resultId, cached);
    }

    CachedResult result(String resultId) {
        return results.get(resultId);
    }

    /**
     * Drops expired confirmations and results, returning the connection ids whose confirmation
     * tokens expired so the caller can record audit events (keeps this component event-free).
     */
    List<String> expire() {
        Instant now = Instant.now();
        List<String> expiredConnections = new ArrayList<>();
        confirmations.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().expiresAt().isBefore(now);
            if (expired) {
                expiredConnections.add(entry.getValue().connectionId());
            }
            return expired;
        });
        results.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        return expiredConnections;
    }

    void clear() {
        confirmations.clear();
        results.clear();
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
