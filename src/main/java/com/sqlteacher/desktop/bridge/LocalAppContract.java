package com.sqlteacher.desktop.bridge;

import java.util.Set;

public final class LocalAppContract {
    public static final String VERSION = "3.0-v1";
    public static final int MAX_MESSAGE_BYTES = 1_048_576;
    public static final int MAX_CONCURRENT_REQUESTS = 8;
    public static final int REQUEST_TIMEOUT_MILLIS = 30_000;
    public static final Set<String> API_METHODS = Set.of(
        "system.health", "session.current", "home.summary", "knowledge.sample",
        "course.workspace", "knowledge.article", "knowledge.search",
        "knowledge.import.preview", "knowledge.import.execute",
        "practice.catalog", "practice.preview", "practice.start", "practice.run", "practice.submit",
        "runner.capabilities", "runner.run", "data.connections", "data.schema",
        "sql.analyze", "sql.execute", "sql.result.page", "ai.knowledge.ask",
        "account.login", "account.logout", "teaching.workspace", "teaching.exercise.toggle",
        "cloud.workspace", "cloud.sync", "cloud.class.create",
        "settings.workspace", "settings.update", "migration.status",
        "editor.languages", "benchmark.echo", "task.demo"
    );
    public static final Set<String> RESERVED_METHODS = Set.of("system.cancel", "system.shutdown");
    public static final Set<String> EVENT_TYPES = Set.of("progress", "import.progress", "runner.progress", "ai.delta");

    private LocalAppContract() {
    }
}
