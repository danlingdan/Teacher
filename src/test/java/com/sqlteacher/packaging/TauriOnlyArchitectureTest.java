package com.sqlteacher.packaging;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TauriOnlyArchitectureTest {
    @Test
    void productionTreeHasNoLegacyJavaFxDesktop() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertFalse(pom.toLowerCase().contains("javafx"));
        assertFalse(pom.contains("atlantafx"));
        assertFalse(pom.contains("controlsfx"));
        assertFalse(pom.contains("richtextfx"));
        assertFalse(pom.contains("ikonli"));

        Path desktopRoot = Path.of("src", "main", "java", "com", "sqlteacher", "desktop");
        try (var entries = Files.walk(desktopRoot)) {
            assertTrue(entries.filter(Files::isRegularFile)
                .allMatch(path -> path.startsWith(desktopRoot.resolve("bridge"))));
        }

        assertFalse(Files.exists(Path.of("src", "main", "resources", "fxml")));
        assertFalse(Files.exists(Path.of("src", "main", "resources", "css")));
        assertFalse(Files.exists(Path.of("src", "main", "resources", "i18n")));
        Path productionMocks = Path.of("src", "main", "java", "com", "sqlteacher", "application", "mock");
        if (Files.exists(productionMocks)) try (var entries = Files.list(productionMocks)) {
            assertTrue(entries.noneMatch(Files::isRegularFile));
        }
        assertFalse(Files.exists(Path.of("src", "main", "java", "com", "sqlteacher", "infrastructure", "spring", "MockApplicationServiceConfig.java")));
        assertFalse(Files.exists(Path.of("packaging", "package-stage0.ps1")));
        assertFalse(Files.exists(Path.of("packaging", "package-stage1.ps1")));

        String cargo = Files.readString(Path.of("ui-web", "src-tauri", "Cargo.toml"));
        String rust = Files.readString(Path.of("ui-web", "src-tauri", "src", "lib.rs"));
        String capability = Files.readString(Path.of("ui-web", "src-tauri", "capabilities", "default.json"));
        assertTrue(cargo.contains("tauri-plugin-single-instance"));
        assertTrue(cargo.contains("tauri-plugin-window-state"));
        assertTrue(cargo.contains("tauri-plugin-notification"));
        assertTrue(rust.contains("tauri_plugin_notification::init()"));
        assertTrue(capability.contains("notification:default"));
    }
}
