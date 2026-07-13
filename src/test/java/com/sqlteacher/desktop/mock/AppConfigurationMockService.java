package com.sqlteacher.desktop.mock;

import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.AppConfigurationService;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Mock implementation of {@link AppConfigurationService} for offline desktop development.
 *
 * <p><b>[改动点 · P1 契约兼容]</b> 应用层配置契约已从 infrastructure 下的
 * {@code SqlTeacherProperties / DatabaseProperties / AiModelProperties} 迁移到
 * {@link SqlTeacherConfiguration} / {@link DatabaseConfiguration} / {@link AiConfiguration}
 * （{@code com.sqlteacher.application.config} 包）。本 Mock 仅调用应用层新契约，
 * 不再引用任何已废弃的 infrastructure 配置类型。
 *
 * <ul>
 *   <li>{@link MockScenario#NORMAL} - 完整应用配置。</li>
 *   <li>{@link MockScenario#EMPTY} - 仅默认值的合法配置。<b>[改动点]</b>
 *       新契约 {@link SqlTeacherConfiguration} 的紧凑构造禁止 {@code appName} 为空，
 *       原「空 appName」写法已非法，改为返回一份仅含默认值的合法配置。</li>
 *   <li>{@link MockScenario#ERROR} - 抛出 {@link MockBackendException}
 *       （{@link SqlTeacherConfiguration} 无法以内联字段表达加载失败）。</li>
 * </ul>
 */
public final class AppConfigurationMockService implements AppConfigurationService {

    /** EMPTY 场景下回退使用的默认应用名（新契约禁止空 appName）。 */
    private static final String DEFAULT_APP_NAME = "SQLTeacher";
    private static final Path DATA_DIRECTORY = Path.of("app-data");
    private static final Path APP_DATABASE = Path.of("app-data", "app.db");
    private static final Path DEMO_DATABASE = Path.of("app-data", "demo.db");
    private static final URI OLLAMA_BASE_URL = URI.create("http://localhost:11434");
    private static final Duration HEALTH_TIMEOUT = Duration.ofMillis(2000);

    private MockScenario scenario;

    public AppConfigurationMockService() {
        this(MockScenario.NORMAL);
    }

    public AppConfigurationMockService(MockScenario scenario) {
        this.scenario = scenario;
    }

    public void useScenario(MockScenario scenario) {
        this.scenario = scenario;
    }

    public MockScenario scenario() {
        return scenario;
    }

    @Override
    public SqlTeacherConfiguration current() {
        return switch (scenario) {
            case NORMAL -> normal();
            case EMPTY -> minimal();
            case ERROR -> throw new MockBackendException("配置加载失败: Missing application.properties");
        };
    }

    public SqlTeacherConfiguration normal() {
        return build(DEFAULT_APP_NAME);
    }

    /**
     * EMPTY 场景：仅默认值的合法配置。
     * <b>[改动点]</b> 新契约禁止空 appName，故回退到 {@link #DEFAULT_APP_NAME}。
     */
    public SqlTeacherConfiguration minimal() {
        return build(DEFAULT_APP_NAME);
    }

    private static SqlTeacherConfiguration build(String appName) {
        return new SqlTeacherConfiguration(
            appName,
            DATA_DIRECTORY,
            new DatabaseConfiguration(APP_DATABASE, DEMO_DATABASE),
            new AiConfiguration(OLLAMA_BASE_URL, HEALTH_TIMEOUT)
        );
    }
}
