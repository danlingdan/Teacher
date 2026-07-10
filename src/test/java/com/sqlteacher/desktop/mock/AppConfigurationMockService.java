package com.sqlteacher.desktop.mock;

import com.sqlteacher.application.config.AppConfigurationService;
import com.sqlteacher.infrastructure.config.AiModelProperties;
import com.sqlteacher.infrastructure.config.DatabaseProperties;
import com.sqlteacher.infrastructure.config.SqlTeacherProperties;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Mock implementation of {@link AppConfigurationService} for offline desktop development.
 *
 * <ul>
 *   <li>{@link MockScenario#NORMAL} - full application properties.</li>
 *   <li>{@link MockScenario#EMPTY} - properties with a blank app name (missing optional config).</li>
 *   <li>{@link MockScenario#ERROR} - throws {@link MockBackendException}
 *       ({@link SqlTeacherProperties} cannot express a load failure inline).</li>
 * </ul>
 */
public final class AppConfigurationMockService implements AppConfigurationService {

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
    public SqlTeacherProperties current() {
        return switch (scenario) {
            case NORMAL -> normal();
            case EMPTY -> minimal();
            case ERROR -> throw new MockBackendException("配置加载失败: Missing application.properties");
        };
    }

    public SqlTeacherProperties normal() {
        return build("SQLTeacher");
    }

    public SqlTeacherProperties minimal() {
        return build("");
    }

    private static SqlTeacherProperties build(String appName) {
        return new SqlTeacherProperties(
            appName,
            DATA_DIRECTORY,
            new DatabaseProperties(APP_DATABASE, DEMO_DATABASE),
            new AiModelProperties(OLLAMA_BASE_URL, HEALTH_TIMEOUT)
        );
    }
}
