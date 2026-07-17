package com.sqlteacher.desktop.mock;

import com.sqlteacher.application.metadata.DatabaseColumn;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.metadata.DatabaseTable;

import java.util.List;

/**
 * Mock implementation of {@link DatabaseMetadataService} for offline desktop development.
 *
 * <ul>
 *   <li>{@link MockScenario#NORMAL} - returns two demo tables (student, course) with columns.</li>
 *   <li>{@link MockScenario#EMPTY} - returns an empty list (no tables).</li>
 *   <li>{@link MockScenario#ERROR} - throws {@link MockBackendException}.</li>
 * </ul>
 */
public final class DatabaseMetadataMockService implements DatabaseMetadataService {

    private MockScenario scenario;

    public DatabaseMetadataMockService() {
        this(MockScenario.NORMAL);
    }

    public DatabaseMetadataMockService(MockScenario scenario) {
        this.scenario = scenario;
    }

    public void useScenario(MockScenario scenario) {
        this.scenario = scenario;
    }

    public MockScenario scenario() {
        return scenario;
    }

    @Override
    public List<DatabaseTable> listTables(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("connectionId must not be blank");
        }
        return switch (scenario) {
            case NORMAL -> demoTables();
            case EMPTY -> List.of();
            case ERROR -> throw new MockBackendException("表元数据加载失败: 无法访问数据库");
        };
    }

    private static List<DatabaseTable> demoTables() {
        return List.of(
            new DatabaseTable("student", List.of(
                new DatabaseColumn("id", "INTEGER", false, true),
                new DatabaseColumn("name", "TEXT", false, false),
                new DatabaseColumn("grade", "INTEGER", true, false)
            )),
            new DatabaseTable("course", List.of(
                new DatabaseColumn("id", "INTEGER", false, true),
                new DatabaseColumn("title", "TEXT", false, false),
                new DatabaseColumn("credits", "INTEGER", true, false)
            ))
        );
    }
}
