package com.sqlteacher.application.event;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v3.7.0 TFB-D1: upload allowlist keeps local bookkeeping and unknown keys off the wire. */
class LearningEventUploadPolicyTest {

    @Test
    void keepsOnlyAllowlistedKeysForKnownEventTypes() {
        Map<String, String> attributes = Map.of(
            LearningEventUploadPolicy.OWNER_ATTRIBUTE, "user-1",
            LearningEventUploadPolicy.CLOUD_ID_ATTRIBUTE, "device:12",
            "statementType", "SELECT",
            "durationMs", "18",
            "rogueLocalKey", "internal-value"
        );

        Map<String, String> uploaded =
            LearningEventUploadPolicy.uploadAttributes(LearningEventType.SQL_EXECUTION, attributes);

        assertEquals("SELECT", uploaded.get("statementType"));
        assertEquals("18", uploaded.get("durationMs"));
        assertFalse(uploaded.containsKey(LearningEventUploadPolicy.OWNER_ATTRIBUTE));
        assertFalse(uploaded.containsKey(LearningEventUploadPolicy.CLOUD_ID_ATTRIBUTE));
        assertFalse(uploaded.containsKey("rogueLocalKey"));
    }

    @Test
    void truncatesLongTextEvidenceToTheAdvertisedLimits() {
        String longSql = "SELECT " + "x".repeat(10_000);
        String longPreview = "检索词" + "y".repeat(10_000);

        Map<String, String> uploadedSql = LearningEventUploadPolicy.uploadAttributes(
            LearningEventType.SQL_EXECUTION, Map.of("sqlText", longSql));
        Map<String, String> uploadedSearch = LearningEventUploadPolicy.uploadAttributes(
            LearningEventType.KNOWLEDGE_SEARCHED, Map.of("queryPreview", longPreview));

        assertEquals(LearningEventUploadPolicy.SQL_TEXT_LIMIT, uploadedSql.get("sqlText").length());
        assertEquals(LearningEventUploadPolicy.QUERY_PREVIEW_LIMIT, uploadedSearch.get("queryPreview").length());
    }

    @Test
    void longTextFallbackDropsTextButKeepsMetadata() {
        Map<String, String> attributes = Map.of(
            "statementType", "SELECT",
            "sqlText", "SELECT " + "x".repeat(9_000),
            "sqlHash", "abc"
        );

        Map<String, String> metadataOnly =
            LearningEventUploadPolicy.uploadAttributesWithoutLongText(LearningEventType.SQL_EXECUTION, attributes);

        assertEquals("SELECT", metadataOnly.get("statementType"));
        assertEquals("abc", metadataOnly.get("sqlHash"));
        assertFalse(metadataOnly.containsKey("sqlText"));
    }

    @Test
    void unknownEventTypesKeepEvidenceForForwardCompatibility() {
        Map<String, String> uploaded = LearningEventUploadPolicy.uploadAttributes(
            "FUTURE_EVENT",
            Map.of(
                LearningEventUploadPolicy.OWNER_ATTRIBUTE, "user-1",
                LearningEventUploadPolicy.CLOUD_ID_ATTRIBUTE, "device:12",
                "futureEvidence", "kept"
            ));

        assertEquals("kept", uploaded.get("futureEvidence"));
        assertFalse(uploaded.containsKey(LearningEventUploadPolicy.OWNER_ATTRIBUTE));
        assertFalse(uploaded.containsKey(LearningEventUploadPolicy.CLOUD_ID_ATTRIBUTE));
    }

    @Test
    void everyExerciseEvidenceKeyStaysAllowedForSubmissionEvents() {
        Map<String, String> attributes = Map.ofEntries(
            Map.entry("activityId", "query-01"),
            Map.entry("activityType", "SQL"),
            Map.entry("status", "PASSED"),
            Map.entry("durationMs", "25"),
            Map.entry("evaluatorVersion", "v1"),
            Map.entry("evidenceVersion", "v1"),
            Map.entry("reasonCode", "OK"),
            Map.entry("exerciseId", "query-01"),
            Map.entry("errorCode", "OK"),
            Map.entry("score", "88"),
            Map.entry("sqlHash", "abc"),
            Map.entry("sqlText", "SELECT * FROM students")
        );

        Map<String, String> uploaded =
            LearningEventUploadPolicy.uploadAttributes(LearningEventType.EXERCISE_PASSED, attributes);

        assertEquals(attributes.size(), uploaded.size());
        assertTrue(uploaded.containsKey("score"));
        assertTrue(uploaded.containsKey("sqlText"));
    }

    @Test
    void newEventTypeEvidenceKeysStayAllowlisted() {
        Map<String, String> mastery = LearningEventUploadPolicy.uploadAttributes(
            LearningEventType.MASTERY_CHANGED,
            Map.of("knowledgePoint", "WHERE 过滤", "level", "DEVELOPING", "masteryPercent", "55",
                "attempts", "4", "passes", "2", "failures", "2"));
        assertEquals(6, mastery.size());

        Map<String, String> daily = LearningEventUploadPolicy.uploadAttributes(
            LearningEventType.DAILY_ACTIVE, Map.of("activeMinutes", "30"));
        assertEquals("30", daily.get("activeMinutes"));

        Map<String, String> reading = LearningEventUploadPolicy.uploadAttributes(
            LearningEventType.KNOWLEDGE_ARTICLE_READ,
            Map.of("articleId", "a-1", "revision", "3", "progressPercent", "75"));
        assertEquals(3, reading.size());

        String longQuestion = "如何" + "理解".repeat(600);
        Map<String, String> asked = LearningEventUploadPolicy.uploadAttributes(
            LearningEventType.AI_ASSISTANT_ASKED,
            Map.of("questionPreview", longQuestion, "resultCount", "5", "resultCode", "ANSWERED"));
        assertEquals(LearningEventUploadPolicy.QUESTION_PREVIEW_LIMIT, asked.get("questionPreview").length());
    }
}
