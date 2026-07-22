package com.sqlteacher.packaging;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsPackagingContractTest {
    @Test
    void shouldDefineInstallerIdentityIconAndUpgradeBehavior() throws Exception {
        Path script = Path.of("packaging", "package-stage1.ps1");
        String content = Files.readString(script);

        assertTrue(content.contains("--type exe"));
        assertTrue(content.contains("--win-upgrade-uuid"));
        assertTrue(content.contains("--win-per-user-install"));
        assertTrue(content.contains("--install-dir \"SQLTeacher-App\""));
        assertTrue(content.contains("--win-shortcut"));
        assertTrue(content.contains("$wixArchiveHash"));
        assertTrue(content.contains("https://api.sqlteacher.tech"));
        assertTrue(content.contains("-Dsqlteacher.cloud.base-url="));
        assertTrue(content.contains("CloudBaseUrl must be an absolute HTTPS URL"));
        assertTrue(Files.size(Path.of("packaging", "sqlteacher.ico")) > 0);
        assertTrue(Files.size(Path.of("src", "main", "resources", "images", "sqlteacher-icon.png")) > 0);
    }

    @Test
    void shouldPublishVersionTagsThroughGitHubActions() throws Exception {
        String workflow = Files.readString(Path.of(".github", "workflows", "release.yml"));

        assertTrue(workflow.contains("tags:"));
        assertTrue(workflow.contains("'v*.*.*'"));
        assertTrue(workflow.contains("mvn -B test"));
        assertTrue(workflow.contains("package-stage1.ps1"));
        assertTrue(workflow.contains("gh release create"));
        assertTrue(workflow.contains("gh release upload"));
        assertTrue(workflow.contains("--draft=false"));
    }
}
