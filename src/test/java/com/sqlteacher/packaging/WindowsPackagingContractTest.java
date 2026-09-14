package com.sqlteacher.packaging;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WindowsPackagingContractTest {
    @Test
    void shouldDefineTauriOnlyInstallerAndPortablePackage() throws Exception {
        Path script = Path.of("packaging", "package-v3.ps1");
        String content = Files.readString(script);
        String tauriConfig = Files.readString(Path.of("ui-web", "src-tauri", "tauri.conf.json"));
        String sidecarBuild = Files.readString(Path.of("packaging", "build-v3-sidecar.ps1"));
        String rustHost = Files.readString(Path.of("ui-web", "src-tauri", "src", "lib.rs"));
        String rustMain = Files.readString(Path.of("ui-web", "src-tauri", "src", "main.rs"));

        assertTrue(content.contains("npm run tauri build -- --bundles nsis"));
        assertTrue(content.contains("SQLTeacher.exe"));
        assertTrue(content.contains("sidecar\\runtime\\bin\\java.exe"));
        assertTrue(content.contains("sidecar\\sidecar.json"));
        assertTrue(content.contains("Compress-Archive"));
        assertTrue(content.contains("sqlteacher-sbom.json"));
        assertTrue(content.contains("sqlteacher-ui-sbom.json"));
        assertTrue(content.contains("LICENSE.txt"));
        assertTrue(content.contains("THIRD-PARTY-LICENSES.txt"));
        assertTrue(content.contains("PRIVACY.md"));
        assertTrue(content.contains("(?:-(?:alpha|beta|rc)"));
        assertTrue(content.contains("requiredNsisContracts"));
        assertTrue(tauriConfig.contains("\"publisher\": \"SQLTeacher Project\""));
        assertTrue(tauriConfig.contains("\"installMode\": \"perMachine\""));
        // CMP-0（v3.4.0）：v1.0–v2.3 WiX 与 3.0 Alpha/Beta 旧版卸载钩子已移除，
        // 最低支持起点上抬至 v3.x；Tauri 内置的 WiX→NSIS 迁移保持不变。
        assertFalse(tauriConfig.contains("installerHooks"));
        assertTrue(sidecarBuild.contains("clean package dependency:copy-dependencies"));
        assertTrue(sidecarBuild.contains("Java sidecar contains removed desktop or test entries"));
        assertTrue(rustMain.contains("windows_subsystem = \"windows\""));
        assertTrue(rustHost.contains("CREATE_NO_WINDOW"));
        assertTrue(rustHost.contains("command.creation_flags(CREATE_NO_WINDOW)"));
        // BUG-4 (v3.4.0): sidecar stderr 滚动落盘而非丢弃，且由 kill-on-close 作业对象兜底。
        assertTrue(rustHost.contains(".stderr(Stdio::piped())"));
        assertTrue(rustHost.contains("log_sidecar_stderr"));
        assertTrue(rustHost.contains("SidecarJob::create"));
        assertTrue(rustHost.contains("RunEvent::Exit"));
        assertTrue(rustHost.contains("tauri_plugin_single_instance::init"));
        assertTrue(rustHost.contains("tauri_plugin_window_state::Builder"));
        assertTrue(rustHost.contains("SQLTEACHER_E2E_DATA_DIR"));
        assertTrue(Files.exists(Path.of("LICENSE")));
        assertTrue(Files.size(Path.of("ui-web", "src-tauri", "icons", "icon.ico")) > 0);
        assertFalse(Files.exists(Path.of("packaging", "package-stage1.ps1")));
    }

    @Test
    void shouldPublishVersionTagsThroughGitHubActions() throws Exception {
        String workflow = Files.readString(Path.of(".github", "workflows", "release.yml"));

        assertTrue(workflow.contains("tags:"));
        assertTrue(workflow.contains("'v*.*.*'"));
        assertTrue(workflow.contains("mvn -B test"));
        assertTrue(workflow.contains("package-v3.ps1"));
        assertTrue(workflow.contains("test-v3-no-console.ps1"));
        assertFalse(workflow.contains("package-stage1.ps1"));
        assertFalse(workflow.contains("DESKTOP_GENERATION"));
        assertTrue(workflow.contains("gh release create"));
        assertTrue(workflow.contains("gh release upload"));
        assertTrue(workflow.contains("SQLTEACHER_UPDATE_SIGNING_KEY"));
        assertTrue(workflow.contains("UpdateManifestTool.java sign"));
        assertTrue(workflow.contains("RELEASE_PRERELEASE=true"));
        assertTrue(workflow.contains("--prerelease"));
        assertTrue(workflow.contains("--latest=false"));
        assertTrue(workflow.contains("sqlteacher-sbom.json"));
        assertTrue(workflow.contains("update-manifest.json"));
        assertTrue(workflow.contains("--draft=false"));
    }
}
