package com.sqlteacher.desktop.mock;

import com.sqlteacher.application.execution.SqlExecutionRequest;
import com.sqlteacher.application.execution.SqlExecutionResult;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.desktop.viewmodel.DesktopConnections;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mock implementation of {@link SqlExecutionService} for offline desktop development.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>{@link MockScenario#NORMAL} - a successful SELECT returning three demo student rows.</li>
 *   <li>{@link MockScenario#EMPTY} - a successful SELECT returning zero rows.</li>
 *   <li>{@link MockScenario#ERROR} - a failed statement ({@code success = false}) with a
 *       readable error message.</li>
 * </ul>
 */
public final class SqlExecutionMockService implements SqlExecutionService {

    private static final List<String> DEMO_COLUMNS = List.of("id", "name", "grade");

    private MockScenario scenario;

    public SqlExecutionMockService() {
        this(MockScenario.NORMAL);
    }

    public SqlExecutionMockService(MockScenario scenario) {
        this.scenario = scenario;
    }

    public void useScenario(MockScenario scenario) {
        this.scenario = scenario;
    }

    public MockScenario scenario() {
        return scenario;
    }

    @Override
    public SqlExecutionResult execute(SqlExecutionRequest request) {
        return switch (scenario) {
            case NORMAL -> normal();
            case EMPTY -> empty();
            case ERROR -> failed();
        };
    }

    public SqlExecutionResult normal() {
        return new SqlExecutionResult(
            true, DEMO_COLUMNS, sampleRows(), 0, false, "查询成功，返回 3 行", Duration.ofMillis(12)
        );
    }

    public SqlExecutionResult empty() {
        return new SqlExecutionResult(
            true, DEMO_COLUMNS, List.of(), 0, false, "查询成功，返回 0 行", Duration.ofMillis(8)
        );
    }

    public SqlExecutionResult failed() {
        return new SqlExecutionResult(
            false, List.of(), List.of(), 0, false, "SQL 执行失败: near \"SELCT\": syntax error", Duration.ofMillis(3)
        );
    }

    /** Builds a demo request bound to the shared {@code demo} connection id. */
    public SqlExecutionRequest demoRequest(String sql) {
        return new SqlExecutionRequest(DesktopConnections.DEMO, sql, 100, Duration.ofSeconds(5));
    }

    private static List<Map<String, Object>> sampleRows() {
        return List.of(
            row(1, "Alice", 92),
            row(2, "Bob", 85),
            row(3, "Cathy", 78)
        );
    }

    private static Map<String, Object> row(int id, String name, int grade) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("grade", grade);
        return row;
    }
}
