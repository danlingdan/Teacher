package com.sqlteacher.infrastructure.runner;

import com.sqlteacher.domain.activity.CodeLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsLocalCodeWorkspaceLauncherTest {
    @TempDir Path tempDir;

    @Test
    void shouldExportSourceAndOpenTerminalWithoutExecutingIt() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        var launcher = new WindowsLocalCodeWorkspaceLauncher(tempDir.resolve("workspaces"),
            command -> commands.add(List.copyOf(command)));

        var workspace = launcher.open(CodeLanguage.JAVA,
            "public class Main { public static void main(String[] args) {} }");

        assertTrue(Files.isRegularFile(workspace.directory().resolve("Main.java")));
        assertTrue(Files.readString(workspace.directory().resolve("LOCAL-ENVIRONMENT-WARNING.txt"))
            .contains("normal IDE terminal"));
        assertEquals(1, commands.size());
        assertTrue(commands.getFirst().contains(workspace.directory().toString()));
        assertFalse(commands.getFirst().stream().anyMatch(argument -> argument.contains("javac")));
    }
}
