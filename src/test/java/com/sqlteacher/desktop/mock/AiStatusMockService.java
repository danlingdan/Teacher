package com.sqlteacher.desktop.mock;

import com.sqlteacher.application.ai.AiAvailability;
import com.sqlteacher.application.ai.AiStatus;
import com.sqlteacher.application.ai.AiStatusService;

/**
 * Mock implementation of {@link AiStatusService} for offline desktop development.
 *
 * <p><b>[改动点 · P1 契约兼容]</b> {@link AiStatus} 的状态字段已从 infrastructure 的
 * {@code VerificationStatus} 迁移到应用层枚举 {@link AiAvailability}（AVAILABLE / UNAVAILABLE）。
 * 本 Mock 全部传参改用 {@link AiAvailability}：可达服务报告 {@link AiAvailability#AVAILABLE}，
 * 不可达服务报告 {@link AiAvailability#UNAVAILABLE}（本应用把缺失模型运行时视为警告而非崩溃）。
 *
 * <ul>
 *   <li>{@link MockScenario#NORMAL} - {@code AVAILABLE}, two models installed.</li>
 *   <li>{@link MockScenario#EMPTY} - {@code AVAILABLE}, reachable but zero models installed.</li>
 *   <li>{@link MockScenario#ERROR} - {@code UNAVAILABLE}, service unreachable.</li>
 * </ul>
 */
public final class AiStatusMockService implements AiStatusService {

    private static final String PROVIDER = "ollama";
    private static final String ENDPOINT = "http://localhost:11434";

    private MockScenario scenario;

    public AiStatusMockService() {
        this(MockScenario.NORMAL);
    }

    public AiStatusMockService(MockScenario scenario) {
        this.scenario = scenario;
    }

    public void useScenario(MockScenario scenario) {
        this.scenario = scenario;
    }

    public MockScenario scenario() {
        return scenario;
    }

    @Override
    public AiStatus checkStatus() {
        return switch (scenario) {
            case NORMAL -> normal();
            case EMPTY -> emptyModels();
            case ERROR -> unavailable();
        };
    }

    public AiStatus normal() {
        return new AiStatus(AiAvailability.AVAILABLE, PROVIDER, ENDPOINT, 2, "Ollama service reachable, models=2");
    }

    public AiStatus emptyModels() {
        return new AiStatus(AiAvailability.AVAILABLE, PROVIDER, ENDPOINT, 0, "Ollama service reachable, models=0");
    }

    public AiStatus unavailable() {
        return new AiStatus(AiAvailability.UNAVAILABLE, PROVIDER, ENDPOINT, 0, "Ollama service unavailable: ConnectException");
    }
}
