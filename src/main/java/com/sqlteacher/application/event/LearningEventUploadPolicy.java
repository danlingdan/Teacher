package com.sqlteacher.application.event;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Upload allowlist for learning-event attributes (v3.7.0 TFB-D1). Only the evidence keys named
 * for each event type leave the device; local bookkeeping keys (owner id, multi-device import
 * marker) never upload. Text values are truncated so a single event stays far below the cloud
 * per-item payload limit. The cloud server applies the same policy to well-formed payloads as
 * defense in depth; unknown event types (newer client syncing to an older server) keep every
 * non-bookkeeping attribute so forward compatibility never loses evidence.
 */
public final class LearningEventUploadPolicy {

    /** Local-only bookkeeping keys that never leave the device. */
    public static final String OWNER_ATTRIBUTE = LearningEventOwnerProvider.OWNER_ATTRIBUTE;
    public static final String CLOUD_ID_ATTRIBUTE = "_cloud_event_id";

    /** Character limits for the free-text evidence fields. */
    public static final int SQL_TEXT_LIMIT = 4_000;
    public static final int AI_SQL_LIMIT = 2_000;
    public static final int QUERY_PREVIEW_LIMIT = 120;
    public static final int QUESTION_PREVIEW_LIMIT = 500;
    /** Bound applied to every allowlisted key without an explicit limit. */
    public static final int DEFAULT_VALUE_LIMIT = 200;

    private static final int LONG_TEXT_THRESHOLD = 500;
    private static final Map<LearningEventType, Map<String, Integer>> ALLOWED = buildAllowlist();

    private LearningEventUploadPolicy() {
    }

    /** Returns only the allowlisted attributes for the event type, truncated to their limits. */
    public static Map<String, String> uploadAttributes(LearningEventType type, Map<String, String> attributes) {
        return filter(type, attributes, true);
    }

    /** Same as {@link #uploadAttributes} but drops long text evidence (payload-size fallback). */
    public static Map<String, String> uploadAttributesWithoutLongText(
        LearningEventType type, Map<String, String> attributes
    ) {
        return filter(type, attributes, false);
    }

    /**
     * Server-side convergence for a raw event type name. Unknown event types keep all
     * non-bookkeeping attributes so a newer client can still sync to an older server.
     */
    public static Map<String, String> uploadAttributes(String eventTypeName, Map<String, String> attributes) {
        LearningEventType type = parse(eventTypeName);
        if (type == null) {
            Map<String, String> uploaded = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                if (!isLocalOnly(entry.getKey())) {
                    uploaded.put(entry.getKey(), entry.getValue());
                }
            }
            return uploaded;
        }
        return filter(type, attributes, true);
    }

    /** True for keys that are local bookkeeping only and must never leave the device. */
    public static boolean isLocalOnly(String key) {
        return OWNER_ATTRIBUTE.equals(key) || CLOUD_ID_ATTRIBUTE.equals(key);
    }

    private static Map<String, String> filter(
        LearningEventType type, Map<String, String> attributes, boolean includeLongText
    ) {
        Map<String, Integer> allowed = ALLOWED.getOrDefault(type, Map.of());
        Map<String, String> uploaded = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String key = entry.getKey();
            if (isLocalOnly(key)) continue;
            Integer limit = allowed.get(key);
            if (limit == null) continue;
            if (!includeLongText && limit > LONG_TEXT_THRESHOLD) continue;
            uploaded.put(key, truncate(entry.getValue(), limit));
        }
        return uploaded;
    }

    private static LearningEventType parse(String eventTypeName) {
        if (eventTypeName == null) return null;
        try {
            return LearningEventType.valueOf(eventTypeName);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        return value.substring(0, limit);
    }

    private static Map<LearningEventType, Map<String, Integer>> buildAllowlist() {
        Map<LearningEventType, Map<String, Integer>> allowed = new EnumMap<>(LearningEventType.class);
        allowed.put(LearningEventType.SQL_EXECUTION, Map.of(
            "statementType", DEFAULT_VALUE_LIMIT,
            "durationMs", DEFAULT_VALUE_LIMIT,
            "resultCount", DEFAULT_VALUE_LIMIT,
            "errorCode", DEFAULT_VALUE_LIMIT,
            "dialect", DEFAULT_VALUE_LIMIT,
            "sqlHash", DEFAULT_VALUE_LIMIT,
            "sqlText", SQL_TEXT_LIMIT
        ));
        allowed.put(LearningEventType.SQL_RISK_BLOCKED, Map.of(
            "statementType", DEFAULT_VALUE_LIMIT,
            "riskLevel", DEFAULT_VALUE_LIMIT,
            "multiStatement", DEFAULT_VALUE_LIMIT,
            "sqlHash", DEFAULT_VALUE_LIMIT,
            "sqlText", SQL_TEXT_LIMIT
        ));
        allowed.put(LearningEventType.SQL_CONFIRMATION_ISSUED, Map.of(
            "statementType", DEFAULT_VALUE_LIMIT,
            "sqlHash", DEFAULT_VALUE_LIMIT,
            "sqlText", SQL_TEXT_LIMIT
        ));
        allowed.put(LearningEventType.SQL_CONFIRMED, Map.of(
            "statementType", DEFAULT_VALUE_LIMIT,
            "sqlHash", DEFAULT_VALUE_LIMIT,
            "sqlText", SQL_TEXT_LIMIT
        ));
        allowed.put(LearningEventType.SQL_CONFIRMATION_CANCELLED, Map.of(
            "reason", DEFAULT_VALUE_LIMIT
        ));
        allowed.put(LearningEventType.AI_SQL_GENERATED, Map.of(
            "model", DEFAULT_VALUE_LIMIT,
            "promptVersion", DEFAULT_VALUE_LIMIT,
            "sqlHash", DEFAULT_VALUE_LIMIT,
            "sqlText", AI_SQL_LIMIT
        ));
        allowed.put(LearningEventType.AI_GENERATION_FAILED, Map.of(
            "model", DEFAULT_VALUE_LIMIT,
            "promptVersion", DEFAULT_VALUE_LIMIT,
            "errorCode", DEFAULT_VALUE_LIMIT
        ));
        Map<String, Integer> exerciseKeys = Map.ofEntries(
            Map.entry("activityId", DEFAULT_VALUE_LIMIT),
            Map.entry("activityType", DEFAULT_VALUE_LIMIT),
            Map.entry("status", DEFAULT_VALUE_LIMIT),
            Map.entry("durationMs", DEFAULT_VALUE_LIMIT),
            Map.entry("evaluatorVersion", DEFAULT_VALUE_LIMIT),
            Map.entry("evidenceVersion", DEFAULT_VALUE_LIMIT),
            Map.entry("reasonCode", DEFAULT_VALUE_LIMIT),
            Map.entry("exerciseId", DEFAULT_VALUE_LIMIT),
            Map.entry("errorCode", DEFAULT_VALUE_LIMIT),
            Map.entry("score", DEFAULT_VALUE_LIMIT),
            Map.entry("sqlHash", DEFAULT_VALUE_LIMIT),
            Map.entry("sqlText", SQL_TEXT_LIMIT)
        );
        allowed.put(LearningEventType.EXERCISE_ATTEMPT, exerciseKeys);
        allowed.put(LearningEventType.EXERCISE_PASSED, exerciseKeys);
        allowed.put(LearningEventType.EXERCISE_FAILED, exerciseKeys);
        Map<String, Integer> activityKeys = Map.of(
            "activityId", DEFAULT_VALUE_LIMIT,
            "activityType", DEFAULT_VALUE_LIMIT,
            "status", DEFAULT_VALUE_LIMIT,
            "durationMs", DEFAULT_VALUE_LIMIT,
            "evaluatorVersion", DEFAULT_VALUE_LIMIT,
            "evidenceVersion", DEFAULT_VALUE_LIMIT,
            "reasonCode", DEFAULT_VALUE_LIMIT
        );
        allowed.put(LearningEventType.ACTIVITY_ATTEMPT, activityKeys);
        allowed.put(LearningEventType.ACTIVITY_PASSED, activityKeys);
        allowed.put(LearningEventType.ACTIVITY_FAILED, activityKeys);
        allowed.put(LearningEventType.EXERCISE_HINT_USED, Map.of(
            "exerciseId", DEFAULT_VALUE_LIMIT,
            "hintLevel", DEFAULT_VALUE_LIMIT
        ));
        allowed.put(LearningEventType.KNOWLEDGE_SEARCHED, Map.of(
            "queryLength", DEFAULT_VALUE_LIMIT,
            "resultCount", DEFAULT_VALUE_LIMIT,
            "queryPreview", QUERY_PREVIEW_LIMIT
        ));
        allowed.put(LearningEventType.MASTERY_CHANGED, Map.of(
            "knowledgePoint", DEFAULT_VALUE_LIMIT,
            "level", DEFAULT_VALUE_LIMIT,
            "masteryPercent", DEFAULT_VALUE_LIMIT,
            "attempts", DEFAULT_VALUE_LIMIT,
            "passes", DEFAULT_VALUE_LIMIT,
            "failures", DEFAULT_VALUE_LIMIT
        ));
        allowed.put(LearningEventType.DAILY_ACTIVE, Map.of(
            "activeMinutes", DEFAULT_VALUE_LIMIT
        ));
        allowed.put(LearningEventType.KNOWLEDGE_ARTICLE_READ, Map.of(
            "articleId", DEFAULT_VALUE_LIMIT,
            "revision", DEFAULT_VALUE_LIMIT,
            "progressPercent", DEFAULT_VALUE_LIMIT
        ));
        allowed.put(LearningEventType.AI_ASSISTANT_ASKED, Map.of(
            "questionPreview", QUESTION_PREVIEW_LIMIT,
            "resultCount", DEFAULT_VALUE_LIMIT,
            "resultCode", DEFAULT_VALUE_LIMIT
        ));
        return Map.copyOf(allowed);
    }
}
