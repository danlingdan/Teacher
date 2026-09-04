package com.sqlteacher.infrastructure.database;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * learning_events 表的历史行把 occurred_at 存成了 {@link Timestamp#toString()} 的本地时区格式
 * （"yyyy-MM-dd HH:mm:ss.fff"），而其余表使用 {@link Instant#toString()} 的 ISO-8601。读取时
 * 统一走这里的容错解析：先按 ISO 解析，失败再按 Timestamp 本地格式回退，保证旧数据可读。
 */
final class SqliteInstantFormat {
    private SqliteInstantFormat() {
    }

    static Instant parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("timestamp value must not be blank");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return Timestamp.valueOf(value).toInstant();
        }
    }
}
