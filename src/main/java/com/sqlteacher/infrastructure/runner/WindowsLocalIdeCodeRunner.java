package com.sqlteacher.infrastructure.runner;

import com.sqlteacher.application.activity.ActivityResourceUsage;
import com.sqlteacher.application.runner.CodeRunRequest;
import com.sqlteacher.application.runner.CodeRunResult;
import com.sqlteacher.application.runner.LocalCodeRunner;
import com.sqlteacher.application.runner.RunnerCancellation;
import com.sqlteacher.application.runner.RunnerCapability;
import com.sqlteacher.application.runner.RunnerFailureReason;
import com.sqlteacher.domain.activity.CodeLanguage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Direct IDE runner. Student programs inherit the user's environment, filesystem and network access. */
public final class WindowsLocalIdeCodeRunner implements LocalCodeRunner {
    private final Path workspaceRoot;
    private final Toolchains toolchains;

    public WindowsLocalIdeCodeRunner(Path workspaceRoot) {
        this(workspaceRoot, Toolchains.detect());
    }

    WindowsLocalIdeCodeRunner(Path workspaceRoot, Toolchains toolchains) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null")
            .toAbsolutePath().normalize();
        this.toolchains = Objects.requireNonNull(toolchains, "toolchains must not be null");
    }

    @Override
    public List<RunnerCapability> capabilities() {
        return Arrays.stream(CodeLanguage.values()).map(language -> {
            boolean available = switch (language) {
                case JAVA -> toolchains.java() != null && toolchains.javac() != null;
                case PYTHON -> toolchains.wsl() != null;
                case C, CPP -> toolchains.msvcDeveloperShell() != null;
            };
            return new RunnerCapability(language, available, available ? "" : "LOCAL_TOOLCHAIN_UNAVAILABLE");
        }).toList();
    }

    @Override
    public CodeRunResult run(CodeRunRequest request, RunnerCancellation cancellation) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        if (!capability(request.language()).available()) {
            return result(RunnerFailureReason.TOOLCHAIN_UNAVAILABLE, -1, "", "LOCAL_TOOLCHAIN_UNAVAILABLE",
                Duration.ZERO);
        }
        long started = System.nanoTime();
        Path workspace = null;
        try {
            Files.createDirectories(workspaceRoot);
            workspace = workspaceRoot.resolve("run-" + UUID.randomUUID()).normalize();
            if (!workspace.startsWith(workspaceRoot)) throw new IOException("Workspace escaped root");
            Files.createDirectories(workspace);
            Path source = workspace.resolve(request.language().sourceFileName());
            Path input = workspace.resolve("stdin.txt");
            Files.writeString(source, request.sourceCode(), StandardCharsets.UTF_8);
            Files.writeString(input, request.standardInput(), StandardCharsets.UTF_8);

            List<String> compile = compileCommand(request.language(), workspace, source);
            if (!compile.isEmpty()) {
                Execution compiled = execute(compile, workspace, null, request, cancellation,
                    Duration.ofSeconds(30));
                if (compiled.cancelled()) return result(RunnerFailureReason.CANCELLED, compiled.exitCode(),
                    compiled.stdout(), compiled.stderr(), elapsed(started));
                if (compiled.outputExceeded()) return result(RunnerFailureReason.OUTPUT_LIMIT, compiled.exitCode(),
                    compiled.stdout(), compiled.stderr(), elapsed(started));
                if (compiled.timedOut()) return result(RunnerFailureReason.TIME_LIMIT, compiled.exitCode(),
                    compiled.stdout(), compiled.stderr(), elapsed(started));
                if (compiled.exitCode() != 0) return result(RunnerFailureReason.COMPILE_ERROR,
                    compiled.exitCode(), compiled.stdout(), compiled.stderr(), elapsed(started));
            }

            Execution executed = execute(runCommand(request.language(), workspace), workspace, input, request,
                cancellation, request.limits().wallTime());
            RunnerFailureReason reason = executed.cancelled() ? RunnerFailureReason.CANCELLED
                : executed.outputExceeded() ? RunnerFailureReason.OUTPUT_LIMIT
                : executed.timedOut() ? RunnerFailureReason.TIME_LIMIT
                : executed.exitCode() == 0 ? RunnerFailureReason.NONE : RunnerFailureReason.RUNTIME_ERROR;
            return result(reason, executed.exitCode(), executed.stdout(), executed.stderr(), elapsed(started));
        } catch (IOException error) {
            return result(RunnerFailureReason.INTERNAL_ERROR, -1, "", "LOCAL_IDE_RUNNER_START_FAILED",
                elapsed(started));
        } finally {
            deleteWorkspace(workspace);
        }
    }

    private List<String> compileCommand(CodeLanguage language, Path workspace, Path source) throws IOException {
        return switch (language) {
            case JAVA -> List.of(toolchains.javac().toString(), "-encoding", "UTF-8", "-d",
                workspace.toString(), source.toString());
            case PYTHON -> List.of();
            case C, CPP -> {
                Path script = workspace.resolve("compile.cmd");
                String standard = language == CodeLanguage.C ? "/std:c17" : "/std:c++20 /EHsc";
                Files.writeString(script, "@echo off\r\ncall \"" + toolchains.msvcDeveloperShell()
                    + "\" -arch=x64 -no_logo\r\nif errorlevel 1 exit /b %errorlevel%\r\ncl /nologo "
                    + standard + " /Fe:\"" + workspace.resolve("program.exe") + "\" \"" + source + "\"\r\n",
                    StandardCharsets.UTF_8);
                yield List.of(systemExecutable("cmd.exe").toString(), "/d", "/c", script.toString());
            }
        };
    }

    private List<String> runCommand(CodeLanguage language, Path workspace) throws IOException {
        return switch (language) {
            case JAVA -> List.of(toolchains.java().toString(), "-cp", workspace.toString(), "Main");
            case PYTHON -> List.of(toolchains.wsl().toString(), "-d", "Ubuntu", "--cd", toWslPath(workspace),
                "--", "python3", "main.py");
            case C, CPP -> List.of(workspace.resolve("program.exe").toString());
        };
    }

    private static Execution execute(List<String> command, Path directory, Path input, CodeRunRequest request,
                                     RunnerCancellation cancellation, Duration timeout) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        if (input != null) builder.redirectInput(input.toFile());
        Process process = builder.start();
        AtomicBoolean exceeded = new AtomicBoolean();
        AtomicLong total = new AtomicLong();
        try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> stdout = readers.submit(() -> readBounded(process.getInputStream(),
                request.limits().outputBytes(), total, exceeded));
            Future<byte[]> stderr = readers.submit(() -> readBounded(process.getErrorStream(),
                request.limits().outputBytes(), total, exceeded));
            long deadline = System.nanoTime() + timeout.toNanos();
            boolean timedOut = false;
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                if (cancellation.isCancelled() || exceeded.get() || System.nanoTime() >= deadline) {
                    timedOut = !cancellation.isCancelled() && !exceeded.get();
                    terminateTree(process);
                    break;
                }
            }
            int exitCode = process.waitFor();
            try {
                return new Execution(exitCode, decode(stdout.get()), decode(stderr.get()),
                    timedOut, exceeded.get(), cancellation.isCancelled());
            } catch (ExecutionException error) {
                throw new IOException("Local runner output failed", error);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            terminateTree(process);
            return new Execution(-1, "", "", false, false, true);
        }
    }

    private static byte[] readBounded(InputStream input, long limit, AtomicLong total,
                                      AtomicBoolean exceeded) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(limit, 8192));
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            int allowed = reserve(total, limit, read);
            if (allowed > 0) output.write(buffer, 0, allowed);
            if (allowed < read) exceeded.set(true);
        }
        return output.toByteArray();
    }

    private static int reserve(AtomicLong total, long limit, int requested) {
        while (true) {
            long current = total.get();
            int allowed = (int) Math.min(requested, Math.max(0, limit - current));
            if (total.compareAndSet(current, current + allowed)) return allowed;
        }
    }

    private static void terminateTree(Process process) {
        process.descendants().forEach(handle -> {
            handle.destroy();
            if (handle.isAlive()) handle.destroyForcibly();
        });
        process.destroy();
        if (process.isAlive()) process.destroyForcibly();
    }

    private static CodeRunResult result(RunnerFailureReason reason, int exitCode, String stdout, String stderr,
                                        Duration elapsed) {
        long bytes = stdout.getBytes(StandardCharsets.UTF_8).length + stderr.getBytes(StandardCharsets.UTF_8).length;
        return new CodeRunResult(reason, exitCode, stdout, stderr,
            new ActivityResourceUsage(elapsed, bytes, 0));
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }

    private static String decode(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).replace("\u0000", "");
    }

    private static String toWslPath(Path path) throws IOException {
        String windows = path.toAbsolutePath().normalize().toString();
        if (windows.length() < 3 || windows.charAt(1) != ':' || windows.charAt(2) != '\\') {
            throw new IOException("Workspace must use a local Windows drive");
        }
        return "/mnt/" + Character.toLowerCase(windows.charAt(0)) + "/"
            + windows.substring(3).replace('\\', '/');
    }

    private static Path systemExecutable(String name) {
        return Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", name);
    }

    private static void deleteWorkspace(Path workspace) {
        if (workspace == null || !Files.exists(workspace)) return;
        try (var paths = Files.walk(workspace)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    record Toolchains(Path java, Path javac, Path wsl, Path msvcDeveloperShell) {
        static Toolchains detect() {
            Path javaHome = Path.of(System.getProperty("java.home"));
            Path java = executable(javaHome.resolve("bin/java.exe"));
            Path javac = executable(javaHome.resolve("bin/javac.exe"));
            Path wsl = executable(systemExecutable("wsl.exe"));
            return new Toolchains(java, javac, wsl, WindowsLocalCodeWorkspaceLauncher.visualStudioDeveloperShell());
        }

        private static Path executable(Path path) {
            return Files.isRegularFile(path) ? path : null;
        }
    }

    private record Execution(int exitCode, String stdout, String stderr, boolean timedOut,
                             boolean outputExceeded, boolean cancelled) { }
}
