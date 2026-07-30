package com.sqlteacher.desktop;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class DeadlineValueConverter {
    public static final String DEFAULT_TIME = "23:59";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final List<String> TIME_OPTIONS = createTimeOptions();

    private DeadlineValueConverter() {
    }

    public static List<String> timeOptions() {
        return TIME_OPTIONS;
    }

    public static Instant toInstant(LocalDate date, String time, ZoneId zone) {
        if (date == null) return null;
        Objects.requireNonNull(zone, "zone must not be null");
        String normalized = time == null || time.isBlank() ? DEFAULT_TIME : time.trim();
        try {
            return date.atTime(LocalTime.parse(normalized, TIME_FORMAT)).atZone(zone).toInstant();
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("截止时间必须从列表中选择", error);
        }
    }

    public static LocalDate datePart(Instant instant, ZoneId zone) {
        return instant == null ? null : instant.atZone(zone).toLocalDate();
    }

    public static String timePart(Instant instant, ZoneId zone) {
        return instant == null ? DEFAULT_TIME : TIME_FORMAT.format(instant.atZone(zone));
    }

    private static List<String> createTimeOptions() {
        List<String> values = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            values.add("%02d:00".formatted(hour));
            values.add("%02d:30".formatted(hour));
        }
        values.add(DEFAULT_TIME);
        return List.copyOf(values);
    }
}
