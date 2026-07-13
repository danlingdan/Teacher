package com.sqlteacher.desktop.mock;

import com.sqlteacher.application.nl2sql.Nl2SqlPlan;
import com.sqlteacher.application.nl2sql.Nl2SqlRequest;
import com.sqlteacher.application.nl2sql.Nl2SqlService;
import com.sqlteacher.desktop.viewmodel.DesktopConnections;

/**
 * Mock implementation of {@link Nl2SqlService} for offline desktop development.
 *
 * <p>The repackaged backend {@link Nl2SqlPlan} carries five fields in this order:
 * {@code sqlDraft}, {@code intent}, {@code explanation}, {@code model}, {@code promptVersion}.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>{@link MockScenario#NORMAL} - a complete draft with a runnable SQL draft, intent,
 *       explanation and model / prompt provenance.</li>
 *   <li>{@link MockScenario#EMPTY} - all fields blank (model returned nothing usable).</li>
 *   <li>{@link MockScenario#ERROR} - throws {@link MockBackendException} because
 *       {@link Nl2SqlPlan} cannot represent a failure inline.</li>
 * </ul>
 */
public final class Nl2SqlMockService implements Nl2SqlService {

    private MockScenario scenario;

    public Nl2SqlMockService() {
        this(MockScenario.NORMAL);
    }

    public Nl2SqlMockService(MockScenario scenario) {
        this.scenario = scenario;
    }

    public void useScenario(MockScenario scenario) {
        this.scenario = scenario;
    }

    public MockScenario scenario() {
        return scenario;
    }

    @Override
    public Nl2SqlPlan generate(Nl2SqlRequest request) {
        return switch (scenario) {
            case NORMAL -> normal();
            case EMPTY -> empty();
            case ERROR -> throw new MockBackendException("NL2SQL 生成失败: 模型返回非法 JSON");
        };
    }

    public Nl2SqlPlan normal() {
        return new Nl2SqlPlan(
                "SELECT id, name, grade FROM student ORDER BY grade DESC LIMIT 10;",
                "query_students_by_grade",
                "生成一条按成绩倒序查询学生的 SELECT 语句，仅返回前若干行。",
                "qwen2.5:0.5b",
                "nl2sql-v1"
        );
    }

    public Nl2SqlPlan empty() {
        return new Nl2SqlPlan("", "", "", "", "");
    }


    /** Builds a demo request bound to the shared {@code demo} connection id. */
    public Nl2SqlRequest demoRequest(String naturalLanguage) {
        return new Nl2SqlRequest(naturalLanguage, DesktopConnections.DEMO);
    }
}
