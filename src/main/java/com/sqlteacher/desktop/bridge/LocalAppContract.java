package com.sqlteacher.desktop.bridge;

import java.util.Set;

public final class LocalAppContract {
    public static final String VERSION = "3.0-v1";
    public static final int MAX_MESSAGE_BYTES = 1_048_576;
    public static final int MAX_CONCURRENT_REQUESTS = 8;
    public static final int REQUEST_TIMEOUT_MILLIS = 30_000;
    public static final Set<String> API_METHODS = Set.of(
        "system.health", "session.current", "home.summary", "home.action.dismiss",
        "course.workspace", "activity.definition", "activity.submit", "knowledge.article", "knowledge.search",
        "knowledge.read.mark", "knowledge.index.status", "knowledge.index.rebuild", "knowledge.article.import",
        "knowledge.article.revise", "knowledge.article.visibility", "knowledge.article.delete",
        "knowledge.import.preview", "knowledge.import.execute",
        "practice.catalog", "practice.preview", "practice.start", "practice.run", "practice.submit",
        "practice.hint", "practice.reset", "practice.close",
        "runner.capabilities", "runner.run", "data.connections", "data.connection.save",
        "data.connection.test", "data.connection.select", "data.connection.delete", "data.schema",
        "sql.analyze", "sql.execute", "sql.result.page", "ai.knowledge.ask", "ai.sql.preview", "ai.sql.generate",
        "account.login", "account.register", "account.logout", "account.password.change",
        "account.password.reset.request", "account.sessions", "account.session.revoke",
        "account.export.request", "account.export.get", "account.deletion.request",
        "account.deletion.cancel", "account.deletion.status", "teaching.workspace", "teaching.exercise.toggle",
        "teaching.exercise.detail", "teaching.exercise.save", "teaching.exercise.copy",
        "teaching.exercise.import", "teaching.exercise.parse", "teaching.exercise.draft",
        "teaching.exercise.export", "teaching.analytics",
        "teaching.interventions", "teaching.intervention.update",
        "cloud.workspace", "cloud.sync", "cloud.class.create", "cloud.class.member.add",
        "cloud.assignments", "cloud.assignment.create", "cloud.assignment.update",
        "cloud.assignment.copy", "cloud.assignment.status", "cloud.class.analytics",
        "cloud.class.analytics.export", "cloud.assignment.analytics", "cloud.assignment.analytics.export",
        "cloud.assignment.snapshot", "cloud.assignment.submit", "cloud.feedback.list",
        "cloud.feedback.save", "cloud.feedback.draft", "cloud.mastery",
        "cloud.notifications", "cloud.notification.read", "learning.portfolio", "learning.portfolio.export",
        "cloud.courses", "cloud.course.create", "cloud.course.content", "cloud.course.section.create",
        "cloud.course.knowledge.create", "cloud.course.exercise.publish", "cloud.assignment.create-versioned",
        "cloud.course.export", "cloud.course.import", "cloud.course.package.preview", "cloud.course.package.import",
        "settings.workspace", "settings.preferences", "settings.environment", "settings.storage",
        "settings.update", "settings.component.install", "settings.component.cancel",
        "settings.backups", "settings.backup.create", "settings.backup.restore", "settings.demo.restore",
        "settings.learning.reset", "settings.cache.clear", "settings.update.check", "settings.notifications.read",
        "settings.help", "editor.languages"
    );
    public static final Set<String> RESERVED_METHODS = Set.of("system.cancel", "system.shutdown");
    public static final Set<String> EVENT_TYPES = Set.of("progress", "import.progress", "runner.progress", "ai.delta");

    private LocalAppContract() {
    }
}
