package com.sqlteacher.desktop.mock;

import com.sqlteacher.application.ai.AiStatus;
import com.sqlteacher.application.ai.AiStatusService;
import com.sqlteacher.infrastructure.environment.VerificationStatus;

/**
 * Mock implementation of {@link AiStatusService} for offline desktop development.
 *
 * <p>Behaviour mirrors {@code OllamaAiStatusService}: a reachable service reports {@code PASS}
 * and an unreachable service reports {@code WARNING} (this app treats a missing model runtime
 * as a warning, never a hard crash).
 *
 * <ul>
 *   <li>{@link MockScenario#NORMAL} - {@code PASS}, two models installed.</li>
 *   <li>{@link MockScenario#EMPTY} - {@code PASS}, reachable but zero models installed.</li>
 *   <li>{@link MockScenario#ERROR} - {@code WARNING}, service unreachable.</li>
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
        return new AiStatus(VerificationStatus.PASS, PROVIDER, ENDPOINT, 2, "Ollama service reachable, models=2");
    }

    public AiStatus emptyModels() {
        return new AiStatus(VerificationStatus.PASS, PROVIDER, ENDPOINT, 0, "Ollama service reachable, models=0");
    }

    public AiStatus unavailable() {
        return new AiStatus(VerificationStatus.WARNING, PROVIDER, ENDPOINT, 0, "Ollama service unavailable: ConnectException");
    }
}
