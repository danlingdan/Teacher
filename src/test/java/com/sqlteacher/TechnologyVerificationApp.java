package com.sqlteacher;

import com.sqlteacher.infrastructure.ai.OllamaHealthClient;
import com.sqlteacher.infrastructure.database.JdbcTechnologyVerifier;
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
        // 端点跟随环境配置，验证脚本不再假定默认地址。
        String ollamaTags = System.getenv().getOrDefault(
            "SQLTEACHER_OLLAMA_TAGS_URL", "http://localhost:11434/api/tags");
        OllamaHealthClient ollamaHealthClient = new OllamaHealthClient(
            URI.create(ollamaTags),
            Duration.ofSeconds(2)
        );

        items.add(environment.javaVersionItem());
        items.add(environment.osItem());
        items.add(jdbcVerifier.verifySqliteInMemoryQuery());
        // MySQL/MariaDB 驱动为可选桌面功能，缺失只降级提示，不算验证失败。
        items.add(jdbcVerifier.verifyMysqlDriverAvailable());
        items.add(jdbcVerifier.verifyMariaDbDriverAvailable());
        items.add(ollamaHealthClient.checkHealth());

        System.out.println("SQLTeacher core technology verification");
        for (VerificationItem item : items) {
            System.out.printf("[%s] %s - %s%n", item.status(), item.name(), item.detail());
        }
    }
}
