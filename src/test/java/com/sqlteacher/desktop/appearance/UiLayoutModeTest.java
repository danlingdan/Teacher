package com.sqlteacher.desktop.appearance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UiLayoutModeTest {
    @Test
    void shouldClassifySupportedDesktopWidthsAtStableBreakpoints() {
        assertEquals(UiLayoutMode.COMPACT, UiLayoutMode.forWidth(840));
        assertEquals(UiLayoutMode.COMPACT, UiLayoutMode.forWidth(1039));
        assertEquals(UiLayoutMode.MEDIUM, UiLayoutMode.forWidth(1040));
        assertEquals(UiLayoutMode.MEDIUM, UiLayoutMode.forWidth(1359));
        assertEquals(UiLayoutMode.WIDE, UiLayoutMode.forWidth(1360));
    }

    @Test
    void shouldRejectInvalidWidths() {
        assertThrows(IllegalArgumentException.class, () -> UiLayoutMode.forWidth(-1));
        assertThrows(IllegalArgumentException.class, () -> UiLayoutMode.forWidth(Double.NaN));
    }
}
