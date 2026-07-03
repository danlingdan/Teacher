package com.sqlteacher;

import com.sqlteacher.infrastructure.ai.OllamaHealthClient;
import com.sqlteacher.infrastructure.database.JdbcTechnologyVerifier;
import com.sqlteacher.infrastructure.environment.JavaFxEnvironmentVerifier;
import com.sqlteacher.infrastructure.environment.RuntimeEnvironment;
import com.sqlteacher.infrastructure.environment.VerificationItem;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class TechnologyVerificationApp {
    private TechnologyVerificationApp() {
    }

    public static void main(String[] args) {
        List<VerificationItem> items = new ArrayList<>();
        RuntimeEnvironment environment = RuntimeEnvironment.detect();
        JdbcTechnologyVerifier jdbcVerifier = new JdbcTechnologyVerifier();
        JavaFxEnvironmentVerifier javaFxVerifier = new JavaFxEnvironmentVerifier();
        OllamaHealthClient ollamaHealthClient = new OllamaHealthClient(
            URI.create("http://localhost:11434/api/tags"),
            Duration.ofSeconds(2)
        );

        items.add(environment.javaVersionItem());
        items.add(environment.osItem());
        items.add(javaFxVerifier.verifyRuntime());
        items.add(javaFxVerifier.verifyGraphicsEnvironment());
        items.add(jdbcVerifier.verifySqliteInMemoryQuery());
        items.add(jdbcVerifier.verifyMysqlDriverAvailable());
        items.add(ollamaHealthClient.checkHealth());

        System.out.println("SQLTeacher stage 0 technology verification");
        for (VerificationItem item : items) {
            System.out.printf("[%s] %s - %s%n", item.status(), item.name(), item.detail());
        }
    }
}
