package com.sqlteacher.desktop.mock;

import com.sqlteacher.application.database.DatabaseInitializationResult;
import com.sqlteacher.application.database.DatabaseInitializationService;

import java.nio.file.Path;

/**
 * Mock implementation of {@link DatabaseInitializationService} for offline desktop development.
 *
 * <ul>
 *   <li>{@link MockScenario#NORMAL} - both databases newly created.</li>
 *   <li>{@link MockScenario#EMPTY} - both databases already existed (nothing created).</li>
 *   <li>{@link MockScenario#ERROR} - throws {@link MockBackendException}
 *       ({@link DatabaseInitializationResult} cannot express failure inline).</li>
 * </ul>
 */
public final class DatabaseInitializationMockService implements DatabaseInitializationService {

    private static final Path APP_DATABASE = Path.of("app-data", "app.db");
    private static final Path DEMO_DATABASE = Path.of("app-data", "demo.db");

    private MockScenario scenario;

    public DatabaseInitializationMockService() {
        this(MockScenario.NORMAL);
    }

    public DatabaseInitializationMockService(MockScenario scenario) {
        this.scenario = scenario;
    }

    public void useScenario(MockScenario scenario) {
        this.scenario = scenario;
    }

    public MockScenario scenario() {
        return scenario;
    }

    @Override
    public DatabaseInitializationResult initialize() {
        return switch (scenario) {
            case NORMAL -> created();
            case EMPTY -> alreadyExisted();
            case ERROR -> throw new MockBackendException("SQLite 初始化失败: 无法写入 app-data 目录");
        };
    }

    public DatabaseInitializationResult created() {
        return new DatabaseInitializationResult(APP_DATABASE, DEMO_DATABASE, true, true);
    }

    public DatabaseInitializationResult alreadyExisted() {
        return new DatabaseInitializationResult(APP_DATABASE, DEMO_DATABASE, false, false);
    }
}
