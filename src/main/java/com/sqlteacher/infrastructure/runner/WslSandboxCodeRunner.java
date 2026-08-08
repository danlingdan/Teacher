package com.sqlteacher.infrastructure.runner;

import com.sqlteacher.application.activity.ActivityResourceUsage;
import com.sqlteacher.application.runner.CodeRunRequest;
import com.sqlteacher.application.runner.CodeRunResult;
import com.sqlteacher.application.runner.CodeRunner;
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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs untrusted code in a disposable WSL2 user/PID/mount/network namespace, wrapped by a
 * systemd user cgroup. The implementation fails closed when any isolation primitive is absent.
 */
public final class WslSandboxCodeRunner implements CodeRunner {
    private static final String SCRIPT_RESOURCE = "/runner/wsl-sandbox-runner.sh";
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(5);
    private final Path wslExecutable;
    private final String distribution;
    private volatile List<RunnerCapability> cachedCapabilities;

    public WslSandboxCodeRunner() {
        this(defaultWslExecutable(), System.getProperty("sqlteacher.runner.wsl.distribution", "Ubuntu"));
    }

    public WslSandboxCodeRunner(Path wslExecutable, String distribution) {
        this.wslExecutable = Objects.requireNonNull(wslExecutable, "wslExecutable must not be null")
            .toAbsolutePath().normalize();
        this.distribution = required(distribution, "distribution");
    }

    @Override
    public List<RunnerCapability> capabilities() {
        List<RunnerCapability> snapshot = cachedCapabilities;
        if (snapshot != null) return snapshot;
        synchronized (this) {
            if (cachedCapabilities == null) cachedCapabilities = probeCapabilities();
            return cachedCapabilities;
        }
    }

    @Override
    public CodeRunResult run(CodeRunRequest request, RunnerCancellation cancellation) {
        Objects.requireNonNull(request, "request must not be null");
        cancellation = Objects.requireNonNull(cancellation, "cancellation must not be null");
        RunnerCapability capability = capability(request.language());
        if (!capability.available()) {
            return unavailable(capability.reasonCode());
        }
        if (cancellation.isCancelled()) {
            return failed(RunnerFailureReason.CANCELLED, -1, "", "", Duration.ZERO, 0);
        }

        long started = System.nanoTime();
        Path workspace = null;
        String unit = "sqlteacher-runner-" + UUID.randomUUID().toString().replace("-", "");
        Process process = null;
        try {
            workspace = Files.createTempDirectory("sqlteacher-runner-request-");
            Path script = copyScript(workspace);
            Path source = workspace.resolve(request.language().sourceFileName());
            Path input = workspace.resolve("stdin.txt");
            Files.writeString(source, request.sourceCode(), StandardCharsets.UTF_8);
            Files.writeString(input, request.standardInput(), StandardCharsets.UTF_8);

            List<String> command = command(unit, toWslPath(script), request, toWslPath(source), toWslPath(input));
            ProcessBuilder builder = new ProcessBuilder(command);
            minimalWindowsEnvironment(builder.environment());
            process = builder.start();
            Process running = process;

            AtomicBoolean outputExceeded = new AtomicBoolean();
            AtomicLong outputBytes = new AtomicLong();
            ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
            Future<byte[]> stdout = readers.submit(() -> readBounded(running.getInputStream(),
                request.limits().outputBytes(), outputBytes, outputExceeded));
            Future<byte[]> stderr = readers.submit(() -> readBounded(running.getErrorStream(),
                request.limits().outputBytes(), outputBytes, outputExceeded));
            try (readers) {
                while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                    if (cancellation.isCancelled() || outputExceeded.get()) {
                        stopUnit(unit);
                        terminateTree(process);
                        break;
                    }
                }
                int exitCode = process.waitFor();
                String out = decode(stdout.get());
                String error = redact(decode(stderr.get()), workspace);
                Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
                if (cancellation.isCancelled()) {
                    return failed(RunnerFailureReason.CANCELLED, exitCode, out, error, elapsed,
                        byteCount(out, error));
                }
                if (outputExceeded.get()) {
                    return failed(RunnerFailureReason.OUTPUT_LIMIT, exitCode, out, error, elapsed,
                        byteCount(out, error));
                }
                return resultFor(exitCode, out, error, elapsed);
            }
        } catch (IOException error) {
            return failed(RunnerFailureReason.SANDBOX_UNAVAILABLE, -1, "", "RUNNER_START_FAILED",
                Duration.ofNanos(System.nanoTime() - started), 0);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            if (process != null) {
                stopUnit(unit);
                terminateTree(process);
            }
            return failed(RunnerFailureReason.CANCELLED, -1, "", "", Duration.ofNanos(
                System.nanoTime() - started), 0);
        } catch (ExecutionException error) {
            if (process != null) terminateTree(process);
            return failed(RunnerFailureReason.INTERNAL_ERROR, -1, "", "RUNNER_OUTPUT_FAILED",
                Duration.ofNanos(System.nanoTime() - started), 0);
        } finally {
            deleteWorkspace(workspace);
        }
    }

    private List<RunnerCapability> probeCapabilities() {
        EnumMap<CodeLanguage, RunnerCapability> result = new EnumMap<>(CodeLanguage.class);
        if (!Files.isRegularFile(wslExecutable)) {
            return unavailableAll("WSL_NOT_INSTALLED");
        }
        CommandOutput distributionProbe = control(List.of("-d", distribution, "--", "/bin/bash", "-lc",
            "set -eu; grep -E '^(ID|VERSION_ID)=' /etc/os-release; printf 'KERNEL='; uname -r"));
        if (distributionProbe.exitCode() != 0) {
            return unavailableAll("WSL_DISTRIBUTION_UNAVAILABLE");
        }
        String platform = distributionProbe.stdout();
        if (!platform.matches("(?s).*\\n?ID=ubuntu(?:\\R|$).*")
                && !platform.startsWith("ID=ubuntu")) {
            return unavailableAll("WSL_UBUNTU_REQUIRED");
        }
        var version = java.util.regex.Pattern.compile("(?m)^VERSION_ID=\\\"?(\\d+)\\.(\\d+)\\\"?$")
            .matcher(platform);
        if (!version.find() || Integer.parseInt(version.group(1)) < 24) {
            return unavailableAll("WSL_UBUNTU_VERSION_UNSUPPORTED");
        }
        if (!platform.toLowerCase(java.util.Locale.ROOT).contains("microsoft-standard-wsl2")) {
            return unavailableAll("WSL2_REQUIRED");
        }
        String probe = "set -eu; command -v unshare >/dev/null; command -v systemd-run >/dev/null; "
            + "command -v setpriv >/dev/null; command -v prlimit >/dev/null; "
            + "test \"$(ps -p 1 -o comm= | tr -d ' ')\" = systemd; "
            + "unshare --user --map-root-user --mount --pid --net --fork /bin/true; "
            + "systemd-run --user --quiet --wait --collect -p TasksMax=2 -p MemoryMax=32M /bin/true";
        CommandOutput output = control(List.of("-d", distribution, "--", "/bin/bash", "-lc", probe));
        if (output.exitCode() != 0) {
            return unavailableAll("WSL_ISOLATION_UNAVAILABLE");
        }
        Map<String, Boolean> tools = Map.of(
            "java", toolPresent("java"),
            "python3", toolPresent("python3"),
            "gcc", toolPresent("gcc"),
            "g++", toolPresent("g++")
        );
        result.put(CodeLanguage.JAVA, capability(CodeLanguage.JAVA, tools.get("java")
            && toolPresent("javac"), "WSL_JAVA_TOOLCHAIN_UNAVAILABLE"));
        result.put(CodeLanguage.PYTHON, capability(CodeLanguage.PYTHON, tools.get("python3"),
            "WSL_PYTHON_TOOLCHAIN_UNAVAILABLE"));
        result.put(CodeLanguage.C, capability(CodeLanguage.C, tools.get("gcc"),
            "WSL_C_TOOLCHAIN_UNAVAILABLE"));
        result.put(CodeLanguage.CPP, capability(CodeLanguage.CPP, tools.get("g++"),
            "WSL_CPP_TOOLCHAIN_UNAVAILABLE"));
        return List.copyOf(result.values());
    }

    private boolean toolPresent(String tool) {
        return control(List.of("-d", distribution, "--", "/bin/bash", "-lc",
            "command -v " + tool + " >/dev/null 2>&1")).exitCode() == 0;
    }

    private static RunnerCapability capability(CodeLanguage language, boolean available, String reason) {
        return new RunnerCapability(language, available, available ? "" : reason);
    }

    private List<String> command(String unit, String script, CodeRunRequest request,
                                 String source, String input) {
        long memory = request.limits().memoryBytes();
        long wall = Math.max(1, request.limits().wallTime().toSeconds());
        long cpu = Math.max(1, request.limits().cpuTime().toSeconds());
        int toolchainTasks = request.language() == CodeLanguage.JAVA ? 96 : 16;
        List<String> command = new ArrayList<>(List.of(wslExecutable.toString(), "-d", distribution, "--",
            "systemd-run", "--user", "--quiet", "--pipe", "--wait", "--collect", "--unit", unit,
            "-p", "KillMode=control-group", "-p", "OOMPolicy=continue",
            "-p", "MemoryMax=" + memory, "-p", "MemorySwapMax=0",
            "-p", "TasksMax=" + (request.limits().processes() + toolchainTasks),
            "-p", "RuntimeMaxSec=" + (wall + 3),
            "/bin/bash", script, request.language().name(), source, input,
            Long.toString(wall), Long.toString(cpu), Long.toString(memory),
            Long.toString(request.limits().workspaceBytes()), Integer.toString(request.limits().files()),
            Integer.toString(request.limits().processes())));
        return command;
    }

    private static String toWslPath(Path path) throws IOException {
        String windows = path.toAbsolutePath().normalize().toString();
        if (windows.length() < 3 || !Character.isLetter(windows.charAt(0))
                || windows.charAt(1) != ':' || windows.charAt(2) != '\\') {
            throw new IOException("Runner workspace must use a local Windows drive");
        }
        return "/mnt/" + Character.toLowerCase(windows.charAt(0)) + "/"
            + windows.substring(3).replace('\\', '/');
    }

    private void stopUnit(String unit) {
        control(List.of("-d", distribution, "--", "systemctl", "--user", "stop", unit));
    }

    private CommandOutput control(List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(wslExecutable.toString());
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command);
        minimalWindowsEnvironment(builder.environment());
        try {
            Process process = builder.start();
            boolean finished = process.waitFor(CONTROL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                terminateTree(process);
                return new CommandOutput(-1, "", "timeout");
            }
            return new CommandOutput(process.exitValue(), decode(process.getInputStream().readAllBytes()),
                decode(process.getErrorStream().readAllBytes()));
        } catch (IOException error) {
            return new CommandOutput(-1, "", "start failed");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new CommandOutput(-1, "", "interrupted");
        }
    }

    private Path copyScript(Path workspace) throws IOException {
        Path target = workspace.resolve("runner.sh");
        try (InputStream source = WslSandboxCodeRunner.class.getResourceAsStream(SCRIPT_RESOURCE)) {
            if (source == null) throw new IOException("Runner script resource is missing");
            Files.copy(source, target);
        }
        return target;
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

    private static void minimalWindowsEnvironment(Map<String, String> environment) {
        String systemRoot = System.getenv("SystemRoot");
        environment.clear();
        if (systemRoot != null && !systemRoot.isBlank()) environment.put("SystemRoot", systemRoot);
    }

    private static CodeRunResult resultFor(int exitCode, String out, String error, Duration elapsed) {
        RunnerFailureReason reason = switch (exitCode) {
            case 0 -> RunnerFailureReason.NONE;
            case 10 -> RunnerFailureReason.COMPILE_ERROR;
            case 11 -> RunnerFailureReason.RUNTIME_ERROR;
            case 12 -> RunnerFailureReason.TIME_LIMIT;
            case 13 -> RunnerFailureReason.MEMORY_LIMIT;
            case 14 -> RunnerFailureReason.WORKSPACE_LIMIT;
            case 15 -> RunnerFailureReason.PROCESS_LIMIT;
            case 20 -> RunnerFailureReason.TOOLCHAIN_UNAVAILABLE;
            default -> RunnerFailureReason.SANDBOX_UNAVAILABLE;
        };
        return failed(reason, exitCode, out, error, elapsed, byteCount(out, error));
    }

    private static CodeRunResult unavailable(String reason) {
        return failed(reason.contains("TOOLCHAIN") ? RunnerFailureReason.TOOLCHAIN_UNAVAILABLE
            : RunnerFailureReason.SANDBOX_UNAVAILABLE, -1, "", reason, Duration.ZERO, 0);
    }

    private static CodeRunResult failed(RunnerFailureReason reason, int exitCode, String out, String error,
                                        Duration elapsed, long outputBytes) {
        return new CodeRunResult(reason, exitCode, out, error,
            new ActivityResourceUsage(elapsed, outputBytes, 0));
    }

    private static long byteCount(String out, String error) {
        return out.getBytes(StandardCharsets.UTF_8).length + error.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String decode(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).replace("\u0000", "");
    }

    private static String redact(String value, Path workspace) {
        String result = value;
        if (workspace != null) result = result.replace(workspace.toString(), "<runner-workspace>");
        String user = System.getProperty("user.name", "");
        if (!user.isBlank()) result = result.replace(user, "<user>");
        return result.replaceAll("(?i)/mnt/[a-z]/[^\\s:]+", "<host-path>")
            .replaceAll("(?i)[a-z]:\\\\[^\\r\\n:]+", "<host-path>");
    }

    private static void deleteWorkspace(Path workspace) {
        if (workspace == null || !Files.exists(workspace)) return;
        try (var paths = Files.walk(workspace)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static List<RunnerCapability> unavailableAll(String reason) {
        return java.util.Arrays.stream(CodeLanguage.values())
            .map(language -> new RunnerCapability(language, false, reason)).toList();
    }

    private static Path defaultWslExecutable() {
        String root = System.getenv("SystemRoot");
        return root == null || root.isBlank() ? Path.of("C:\\Windows\\System32\\wsl.exe")
            : Path.of(root, "System32", "wsl.exe");
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    private record CommandOutput(int exitCode, String stdout, String stderr) { }
}
