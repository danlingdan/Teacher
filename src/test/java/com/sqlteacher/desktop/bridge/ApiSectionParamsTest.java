package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * v3.4.0 REF-8: parameter parsing helpers shared by all sections keep the exact frozen
 * bounds and error messages after the split.
 */
class ApiSectionParamsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode node() {
        return MAPPER.createObjectNode();
    }

    @Test
    void requiredTextTrimsAndEnforcesMaximumLength() {
        ObjectNode params = node().put("value", "  spaced  ");
        assertEquals("spaced", ApiSection.requiredText(params, "value", 16));

        IllegalArgumentException tooLong = assertThrows(IllegalArgumentException.class,
            () -> ApiSection.requiredText(node().put("value", "abcdef"), "value", 5));
        assertEquals("value must contain at most 5 characters", tooLong.getMessage());

        assertThrows(IllegalArgumentException.class,
            () -> ApiSection.requiredText(node().put("value", "   "), "value", 16));
        assertThrows(IllegalArgumentException.class,
            () -> ApiSection.requiredText(node(), "missing", 16));
    }

    @Test
    void requiredRawTextKeepsInteriorSpacesButBoundsLength() {
        assertEquals("a b", ApiSection.requiredRawText(node().put("value", "a b"), "value", 16));
        IllegalArgumentException tooLong = assertThrows(IllegalArgumentException.class,
            () -> ApiSection.requiredRawText(node().put("value", "abc"), "value", 2));
        assertEquals("value must contain at most 2 characters", tooLong.getMessage());
        assertThrows(IllegalArgumentException.class, () ->
            ApiSection.requiredRawText(node().put("value", ""), "value", 16));
    }

    @Test
    void textListRejectsUnboundedOrInvalidEntries() {
        ObjectNode params = node().set("items", MAPPER.createArrayNode().add(" a ").add("b"));
        assertEquals(List.of("a", "b"), ApiSection.textList(params.path("items"), 10, 240));

        assertThrows(IllegalArgumentException.class, () ->
            ApiSection.textList(node().put("items", "not-an-array").path("items"), 10, 240));
        assertThrows(IllegalArgumentException.class, () -> {
            ObjectNode oversize = node();
            oversize.set("items", MAPPER.createArrayNode().add("a").add("b").add("c"));
            ApiSection.textList(oversize.path("items"), 2, 240);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            ObjectNode blank = node();
            blank.set("items", MAPPER.createArrayNode().add(" "));
            ApiSection.textList(blank.path("items"), 10, 240);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            ObjectNode tooLong = node();
            tooLong.set("items", MAPPER.createArrayNode().add("toolong"));
            ApiSection.textList(tooLong.path("items"), 10, 3);
        });
    }

    @Test
    void textMapRejectsOversizedKeysOrValues() {
        ObjectNode artifact = node();
        artifact.putObject("map").put("key", " value ");
        assertEquals(Map.of("key", "value"), ApiSection.textMap(artifact.path("map"), 10, 240));

        assertThrows(IllegalArgumentException.class, () ->
            ApiSection.textMap(node().put("map", 1).path("map"), 10, 240));
        assertThrows(IllegalArgumentException.class, () -> {
            ObjectNode longKey = node();
            longKey.putObject("map").put("k".repeat(129), "v");
            ApiSection.textMap(longKey.path("map"), 10, 240);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            ObjectNode longValue = node();
            longValue.putObject("map").put("k", "v".repeat(11));
            ApiSection.textMap(longValue.path("map"), 10, 10);
        });
    }

    @Test
    void optionalInstantParsesOrReturnsNull() {
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"),
            ApiSection.optionalInstant(node().put("dueAt", "2026-01-02T03:04:05Z"), "dueAt"));
        assertNull(ApiSection.optionalInstant(node(), "dueAt"));
        assertNull(ApiSection.optionalInstant(node().put("dueAt", "  "), "dueAt"));
        assertThrows(java.time.format.DateTimeParseException.class,
            () -> ApiSection.optionalInstant(node().put("dueAt", "not-a-time"), "dueAt"));
    }
}
