package com.sqlteacher.application.system;

import java.util.Locale;
import java.util.Set;

/**
 * Whitelist gate for Windows native notifications. Notifications only ever contain
 * fixed phrases and a navigation target; feedback text, SQL, prompts and student
 * information must never appear in a native notification body.
 */
public final class NativeNotificationPolicy {
    private static final Set<String> ALLOWED_TITLES = Set.of(
        "更新可用", "更新完成", "备份完成", "备份失败", "任务完成", "任务失败", "反馈已提交", "Update available",
        "Update installed", "Backup complete", "Backup failed", "Task complete", "Task failed", "Feedback submitted");
    private static final Set<String> ALLOWED_TARGETS = Set.of("updates", "tasks", "support");

    private NativeNotificationPolicy() { }

    public static void requireAllowed(String title, String target) {
        String normalized = title == null ? "" : title.strip();
        String normalizedTarget = target == null ? "" : target.strip().toLowerCase(Locale.ROOT);
        if (!ALLOWED_TITLES.contains(normalized)) {
            throw new IllegalArgumentException("native notification title is not on the whitelist");
        }
        if (!ALLOWED_TARGETS.contains(normalizedTarget)) {
            throw new IllegalArgumentException("native notification target is not on the whitelist");
        }
    }

    public static boolean isAllowed(String title, String target) {
        try { requireAllowed(title, target); return true; }
        catch (IllegalArgumentException error) { return false; }
    }
}
