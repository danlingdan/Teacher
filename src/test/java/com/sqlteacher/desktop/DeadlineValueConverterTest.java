package com.sqlteacher.desktop;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeadlineValueConverterTest {
    @Test
    void shouldConvertSelectedLocalDateAndTimeWithoutManualIsoInput() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        var instant = DeadlineValueConverter.toInstant(LocalDate.of(2026, 12, 31), "18:30", zone);

        assertEquals(LocalDate.of(2026, 12, 31), DeadlineValueConverter.datePart(instant, zone));
        assertEquals("18:30", DeadlineValueConverter.timePart(instant, zone));
        assertTrue(DeadlineValueConverter.timeOptions().contains("23:59"));
        assertNull(DeadlineValueConverter.toInstant(null, "18:30", zone));
    }
}
