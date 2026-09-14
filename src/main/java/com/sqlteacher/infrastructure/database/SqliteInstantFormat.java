package com.sqlteacher.infrastructure.database;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * 时间戳容错解析：新行写 {@link Instant#toString()} 的 ISO-8601，但两类历史值仍是旧格式——
 * learning_events.created_at 由 SQLite {@code current_timestamp} 默认值生成（"yyyy-MM-dd HH:mm:ss"
 * UTC 无 T 分隔），v1.x 时代的 occurred_at 则是 {@link Timestamp#toString()} 本地时区格式
 * （occurred_at 已由迁移 23 归一，created_at 因无法在 SQL 中还原本地时区而保留原样）。先按
 * ISO 解析，失败再按空格分隔格式回退，保证新旧数据都可读。
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
