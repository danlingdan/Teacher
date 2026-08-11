package com.sqlteacher.infrastructure.runner;

import com.sqlteacher.application.runner.LocalCodeWorkspace;
import com.sqlteacher.application.runner.LocalCodeWorkspaceLauncher;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.domain.activity.CodeLanguage;
import com.sqlteacher.infrastructure.environment.WindowsToolchainDiscovery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Exports source and opens the IDE's local terminal. It never auto-executes student source. */
public final class WindowsLocalCodeWorkspaceLauncher implements LocalCodeWorkspaceLauncher {
    private final Path workspaceRoot;
    private final TerminalStarter terminalStarter;

    public WindowsLocalCodeWorkspaceLauncher(Path workspaceRoot) {
        this(workspaceRoot, command -> new ProcessBuilder(command).start());
    }

    WindowsLocalCodeWorkspaceLauncher(Path workspaceRoot, TerminalStarter terminalStarter) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null")
            .toAbsolutePath().normalize();
        this.terminalStarter = Objects.requireNonNull(terminalStarter, "terminalStarter must not be null");
    }

    @Override
    public LocalCodeWorkspace open(CodeLanguage language, String sourceCode) {
        Objects.requireNonNull(language, "language must not be null");
        Objects.requireNonNull(sourceCode, "sourceCode must not be null");
        if (sourceCode.isBlank() || sourceCode.length() > 256 * 1024) {
            throw new IllegalArgumentException("sourceCode must contain at most 256 KiB");
        }
        Path workspace = workspaceRoot.resolve(language.name().toLowerCase() + "-"
            + UUID.randomUUID().toString().substring(0, 8)).normalize();
        if (!workspace.startsWith(workspaceRoot)) {
            throw new IllegalStateException("Local workspace escaped its configured root");
        }
        try {
            Files.createDirectories(workspace);
            Files.writeString(workspace.resolve(language.sourceFileName()), sourceCode, StandardCharsets.UTF_8);
            Files.writeString(workspace.resolve("LOCAL-ENVIRONMENT-WARNING.txt"), warning(language),
                StandardCharsets.UTF_8);
            Launch launch = launch(language, workspace);
            terminalStarter.start(launch.command());
            return new LocalCodeWorkspace(workspace, launch.environmentName());
        } catch (IOException error) {
            throw new SqlTeacherException("LOCAL_CODE_WORKSPACE_FAILED",
                "Failed to open the local development workspace", error);
        }
    }

    private Launch launch(CodeLanguage language, Path workspace) throws IOException {
        Path terminal = windowsTerminal();
        List<String> command = new ArrayList<>();
        command.add(terminal.toString());
        if (language == CodeLanguage.PYTHON && Files.isRegularFile(wslExecutable())) {
            command.addAll(List.of("wsl.exe", "-d", "Ubuntu", "--cd", toWslPath(workspace)));
            return new Launch(List.copyOf(command), "WSL Ubuntu（本地联网、非隔离）");
        }
        command.addAll(List.of("-d", workspace.toString()));
        if (language == CodeLanguage.C || language == CodeLanguage.CPP) {
            Path developerShell = visualStudioDeveloperShell();
            if (developerShell != null) {
                command.addAll(List.of("cmd.exe", "/k", "call \"" + developerShell + "\" -arch=x64"));
                return new Launch(List.copyOf(command), "MSVC x64 Developer Shell（本地联网、非隔离）");
            }
        }
        return new Launch(List.copyOf(command), language == CodeLanguage.JAVA
            ? "本地 JDK 终端（本地联网、非隔离）" : "本地终端（本地联网、非隔离）");
    }

    private static Path windowsTerminal() throws IOException {
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            Path candidate = Path.of(local, "Microsoft", "WindowsApps", "wt.exe");
            if (Files.exists(candidate)) return candidate;
        }
        Path system = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "wt.exe");
        if (Files.exists(system)) return system;
        return Path.of("wt.exe");
    }

    static Path visualStudioDeveloperShell() {
        return WindowsToolchainDiscovery.visualStudioDeveloperShell();
    }

    private static Path wslExecutable() {
        return Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "wsl.exe");
    }

    private static String toWslPath(Path path) throws IOException {
        String windows = path.toAbsolutePath().normalize().toString();
        if (windows.length() < 3 || windows.charAt(1) != ':' || windows.charAt(2) != '\\') {
            throw new IOException("Local workspace must use a local Windows drive");
        }
        return "/mnt/" + Character.toLowerCase(windows.charAt(0)) + "/"
            + windows.substring(3).replace('\\', '/');
    }

    private static String warning(CodeLanguage language) {
        return "SQLTeacher local development workspace\r\n"
            + "Language: " + language + "\r\n\r\n"
            + "This is a normal IDE terminal, not an isolated sandbox. Programs can access the network, local files, "
            + "credentials, and devices with your Windows/WSL account permissions. You are responsible for code you "
            + "run. Use Safe evaluation separately when a course requires reproducible restricted tests.\r\n";
    }

    @FunctionalInterface
    interface TerminalStarter {
        void start(List<String> command) throws IOException;
    }

    private record Launch(List<String> command, String environmentName) { }
}
