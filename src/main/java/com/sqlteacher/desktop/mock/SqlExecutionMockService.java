package com.sqlteacher.desktop.mock;

import com.sqlteacher.application.execution.SqlExecutionRequest;
import com.sqlteacher.application.execution.SqlExecutionResult;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.application.mock.MockSqlRiskAnalysisService;
import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
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
 *
 * <p><b>[改动点 · P2 SQL Mock 安全校验]</b> 在返回场景数据之前，先复用应用层
 * {@link SqlRiskAnalysisService}（默认实现 {@link MockSqlRiskAnalysisService}）做前置安全闸，
 * 防止 Mock 绕过风控造成验收失真：
 * <ol>
 *   <li>不可执行（{@code !executable}）——空 SQL、多语句、DROP / TRUNCATE 等高危 / 非 SELECT
 *       语句——直接返回失败 DTO（{@code success = false}），message 汇总风险原因；</li>
 *   <li>需二次确认（{@code confirmationRequired}）且请求未确认（{@code !riskConfirmed}）——
 *       返回「需二次确认」失败 DTO，要求用户显式确认后携带 {@code riskConfirmed = true} 重试；</li>
 *   <li>通过安全闸后再按 {@link MockScenario} 返回原有 normal / empty / failed 数据。</li>
 * </ol>
 *
 * <p>Lives in the {@code src/main} source set so the offline desktop launcher can inject it into
 * {@code SqlPracticeController} via constructor injection ({@code FXMLLoader.setControllerFactory}).
 * The desktop contract test in {@code src/test} keeps referencing this class from the same package
 * through the classpath (the build is non-modular, so a split package is legal).
 */
public final class SqlExecutionMockService implements SqlExecutionService {

    private static final List<String> DEMO_COLUMNS = List.of("id", "name", "grade");

    private MockScenario scenario;

    /** 应用层 SQL 风险分析服务（前置安全闸）；默认复用应用层 {@link MockSqlRiskAnalysisService}。 */
    private final SqlRiskAnalysisService riskAnalysisService;

    public SqlExecutionMockService() {
        this(MockScenario.NORMAL);
    }

    public SqlExecutionMockService(MockScenario scenario) {
        this(scenario, new MockSqlRiskAnalysisService());
    }

    /**
     * <b>[改动点]</b> 允许注入自定义 {@link SqlRiskAnalysisService}，便于单元测试覆盖
     * 「需二次确认」等边界分支（默认 {@link MockSqlRiskAnalysisService} 不会返回
     * {@code confirmationRequired = true}）。
     */
    public SqlExecutionMockService(MockScenario scenario, SqlRiskAnalysisService riskAnalysisService) {
        this.scenario = scenario;
        this.riskAnalysisService = riskAnalysisService;
    }

    public void useScenario(MockScenario scenario) {
        this.scenario = scenario;
    }

    public MockScenario scenario() {
        return scenario;
    }

    @Override
    public SqlExecutionResult execute(SqlExecutionRequest request) {
        // [改动点 · P2] 前置安全闸：先做风险分析，再决定是否落到场景数据。
        SqlRiskAnalysis analysis = riskAnalysisService.analyze(request.sql());
        if (!analysis.executable()) {
            return blockedResult(analysis);
        }
        if (analysis.confirmationRequired() && !request.riskConfirmed()) {
            return confirmationRequiredResult(analysis);
        }
        return switch (scenario) {
            case NORMAL -> normal();
            case EMPTY -> empty();
            case ERROR -> failed();
        };
    }

    /** 被安全闸拦截（不可执行）时的失败 DTO：message 汇总风险原因。 */
    private static SqlExecutionResult blockedResult(SqlRiskAnalysis analysis) {
        return new SqlExecutionResult(false, List.of(), List.of(), joinReasons(analysis, "SQL 被安全校验拦截"));
    }

    /** 需二次确认但请求未确认时的失败 DTO。 */
    private static SqlExecutionResult confirmationRequiredResult(SqlRiskAnalysis analysis) {
        String detail = joinReasons(analysis, "该 SQL 属于高危操作");
        return new SqlExecutionResult(false, List.of(), List.of(), "需二次确认后才能执行: " + detail);
    }

    private static String joinReasons(SqlRiskAnalysis analysis, String fallback) {
        List<String> reasons = analysis.reasons();
        return reasons.isEmpty() ? fallback : String.join("; ", reasons);
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
