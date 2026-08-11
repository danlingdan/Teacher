package com.sqlteacher.infrastructure.component;

import com.sqlteacher.application.component.ComponentInstallProgress;
import com.sqlteacher.application.component.ManagedComponentId;
import com.sqlteacher.application.component.ManagedComponentService;
import com.sqlteacher.application.component.ManagedComponentStatus;
import com.sqlteacher.infrastructure.environment.WindowsToolchainDiscovery;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/** Uses fixed Windows Package Manager identifiers and the official WSL command. */
public final class WindowsManagedComponentService implements ManagedComponentService {
    private static final int OUTPUT_LIMIT = 16 * 1024;
    private static final Map<ManagedComponentId, Descriptor> DESCRIPTORS = descriptors();

    private final Map<ManagedComponentId, Process> running = new ConcurrentHashMap<>();
    private final Map<ManagedComponentId, String> failures = new ConcurrentHashMap<>();
    private final java.util.Set<ManagedComponentId> restartPending = ConcurrentHashMap.newKeySet();
    private final Predicate<ManagedComponentId> componentProbe;
    private final Function<ManagedComponentId, List<String>> commandFactory;
    private final Predicate<ManagedComponentId> installerProbe;
    private final BooleanSupplier subsystemProbe;
    private final ProcessStarter processStarter;

    public WindowsManagedComponentService() {
        this(WindowsManagedComponentService::probe,
            id -> installCommand(DESCRIPTORS.get(id)),
            WindowsManagedComponentService::wingetAvailable,
            WindowsManagedComponentService::windowsSubsystemPresent,
            command -> new ProcessBuilder(command).redirectErrorStream(true).start());
    }

    WindowsManagedComponentService(
        Predicate<ManagedComponentId> componentProbe,
        Function<ManagedComponentId, List<String>> commandFactory,
        Predicate<ManagedComponentId> installerProbe,
        BooleanSupplier subsystemProbe,
        ProcessStarter processStarter
    ) {
        this.componentProbe = Objects.requireNonNull(componentProbe);
        this.commandFactory = Objects.requireNonNull(commandFactory);
        this.installerProbe = Objects.requireNonNull(installerProbe);
        this.subsystemProbe = Objects.requireNonNull(subsystemProbe);
        this.processStarter = Objects.requireNonNull(processStarter);
    }

    @Override
    public List<ManagedComponentStatus> statuses() {
        return List.of(ManagedComponentId.values()).stream().map(this::status).toList();
    }

    @Override
    public ManagedComponentStatus install(
        ManagedComponentId componentId,
        Consumer<ComponentInstallProgress> progress
    ) {
        Objects.requireNonNull(componentId);
        Objects.requireNonNull(progress);
        if (running.containsKey(componentId)) return status(componentId);
        if (componentProbe.test(componentId)) return status(componentId);
        List<String> command = commandFactory.apply(componentId);
        failures.remove(componentId);
        restartPending.remove(componentId);
        progress.accept(new ComponentInstallProgress(0, "STARTING"));
        Process process = null;
        try {
            process = processStarter.start(command);
            Process previous = running.putIfAbsent(componentId, process);
            if (previous != null) {
                process.destroyForcibly();
                return status(componentId);
            }
            Process owned = process;
            Thread reader = Thread.ofVirtual().start(() -> drain(owned.getInputStream()));
            progress.accept(new ComponentInstallProgress(-1, "DOWNLOADING_AND_INSTALLING"));
            int exitCode = process.waitFor();
            reader.join(TimeUnit.SECONDS.toMillis(2));
            if (exitCode != 0) {
                failures.putIfAbsent(componentId, "INSTALL_EXIT_" + exitCode);
            } else {
                failures.remove(componentId);
                if (!componentProbe.test(componentId)) restartPending.add(componentId);
                progress.accept(new ComponentInstallProgress(1, "VERIFYING"));
            }
        } catch (IOException error) {
            failures.put(componentId, "INSTALL_PROCESS_UNAVAILABLE");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            failures.put(componentId, "INSTALL_CANCELLED");
            if (process != null) terminate(process);
        } finally {
            if (process != null) running.remove(componentId, process);
        }
        return status(componentId);
    }

    @Override
    public void cancel(ManagedComponentId componentId) {
        Objects.requireNonNull(componentId);
        Process process = running.remove(componentId);
        if (process != null) {
            terminate(process);
            failures.put(componentId, "INSTALL_CANCELLED");
        }
    }

    private ManagedComponentStatus status(ManagedComponentId id) {
        Descriptor descriptor = DESCRIPTORS.get(id);
        ManagedComponentStatus.State state;
        String detail;
        if (running.containsKey(id)) {
            state = ManagedComponentStatus.State.INSTALLING;
            detail = "DOWNLOADING_AND_INSTALLING";
        } else if (componentProbe.test(id)) {
            restartPending.remove(id);
            state = ManagedComponentStatus.State.READY;
            detail = "READY";
        } else if (failures.containsKey(id)) {
            state = ManagedComponentStatus.State.FAILED;
            detail = failures.get(id);
        } else if (restartPending.contains(id)
            || (id == ManagedComponentId.WSL_UBUNTU && subsystemProbe.getAsBoolean())) {
            state = ManagedComponentStatus.State.RESTART_REQUIRED;
            detail = "RESTART_OR_FINISH_UBUNTU_SETUP";
        } else {
            state = ManagedComponentStatus.State.MISSING;
            detail = installerProbe.test(id) ? "NOT_INSTALLED" : "INSTALLER_UNAVAILABLE";
        }
        return new ManagedComponentStatus(id, descriptor.displayName(), state, detail,
            descriptor.source(), descriptor.license(), descriptor.administrator(), descriptor.restart());
    }

    private static List<String> installCommand(Descriptor descriptor) {
        if (descriptor.id() == ManagedComponentId.WSL_UBUNTU) {
            Path wsl = systemExecutable("wsl.exe");
            if (!Files.isRegularFile(wsl)) throw new IllegalStateException("WSL_COMMAND_UNAVAILABLE");
            return List.of(wsl.toString(), "--install", "-d", "Ubuntu", "--no-launch");
        }
        Path winget = wingetExecutable();
        if (winget == null) throw new IllegalStateException("WINGET_UNAVAILABLE");
        List<String> command = new ArrayList<>();
        command.add(winget.toString());
        command.addAll(wingetArguments(descriptor.id()));
        return List.copyOf(command);
    }

    static List<String> wingetArguments(ManagedComponentId id) {
        Descriptor descriptor = DESCRIPTORS.get(Objects.requireNonNull(id));
        if (id == ManagedComponentId.WSL_UBUNTU) {
            throw new IllegalArgumentException("WSL does not use WinGet");
        }
        List<String> command = new ArrayList<>(List.of(
            "install", "--id", descriptor.packageId(), "--exact", "--source", "winget",
            "--accept-package-agreements", "--accept-source-agreements", "--disable-interactivity"
        ));
        if (descriptor.id() == ManagedComponentId.MSVC) {
            command.add("--override");
            command.add("--wait --passive --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended");
        }
        return List.copyOf(command);
    }

    private static boolean probe(ManagedComponentId id) {
        return switch (id) {
            case JDK -> WindowsToolchainDiscovery.javaCompiler() != null;
            case PYTHON -> WindowsToolchainDiscovery.python() != null;
            case OLLAMA -> executableOnPath("ollama.exe") || Files.isRegularFile(Path.of(
                System.getenv().getOrDefault("LOCALAPPDATA", ""), "Programs", "Ollama", "ollama.exe"));
            case MSVC -> WindowsToolchainDiscovery.visualStudioDeveloperShell() != null
                || executableOnPath("cl.exe");
            case WSL_UBUNTU -> ubuntuAvailable();
        };
    }

    private static boolean ubuntuAvailable() {
        Path wsl = systemExecutable("wsl.exe");
        if (!Files.isRegularFile(wsl)) return false;
        try {
            Process process = new ProcessBuilder(wsl.toString(), "-d", "Ubuntu", "--", "true")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(8, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException error) {
            return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean windowsSubsystemPresent() {
        return Files.isRegularFile(systemExecutable("wsl.exe"));
    }

    private static boolean wingetAvailable(ManagedComponentId id) {
        return id == ManagedComponentId.WSL_UBUNTU
            ? Files.isRegularFile(systemExecutable("wsl.exe"))
            : wingetExecutable() != null;
    }

    private static Path wingetExecutable() {
        Path local = Path.of(System.getenv().getOrDefault("LOCALAPPDATA", ""),
            "Microsoft", "WindowsApps", "winget.exe");
        if (Files.isRegularFile(local)) return local;
        return findOnPath("winget.exe");
    }

    private static boolean executableOnPath(String name) {
        return findOnPath(name) != null;
    }

    private static Path findOnPath(String name) {
        String path = System.getenv().getOrDefault("PATH", "");
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) continue;
            try {
                Path candidate = Path.of(entry, name);
                if (Files.isRegularFile(candidate)) return candidate;
            } catch (RuntimeException ignored) { }
        }
        return null;
    }

    private static void drain(InputStream input) {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                int retained = Math.min(read, Math.max(0, OUTPUT_LIMIT - total));
                if (retained > 0) output.write(buffer, 0, retained);
                total += retained;
            }
        } catch (IOException ignored) { }
    }

    private static void terminate(Process process) {
        try {
            process.descendants().forEach(child -> {
                child.destroy();
                if (child.isAlive()) child.destroyForcibly();
            });
        } catch (UnsupportedOperationException ignored) { }
        process.destroy();
        if (process.isAlive()) process.destroyForcibly();
    }

    private static Path systemExecutable(String name) {
        return Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", name);
    }

    private static Map<ManagedComponentId, Descriptor> descriptors() {
        Map<ManagedComponentId, Descriptor> values = new EnumMap<>(ManagedComponentId.class);
        values.put(ManagedComponentId.JDK, new Descriptor(ManagedComponentId.JDK, "Java Development Kit (JDK 21+)",
            "EclipseAdoptium.Temurin.21.JDK", "WinGet / Eclipse Adoptium", "GPL-2.0 with Classpath Exception", true, false));
        values.put(ManagedComponentId.PYTHON, new Descriptor(ManagedComponentId.PYTHON, "Python 3",
            "Python.Python.3.13", "WinGet / Python Software Foundation", "PSF License", false, false));
        values.put(ManagedComponentId.OLLAMA, new Descriptor(ManagedComponentId.OLLAMA, "Ollama",
            "Ollama.Ollama", "WinGet / Ollama", "MIT", false, false));
        values.put(ManagedComponentId.MSVC, new Descriptor(ManagedComponentId.MSVC,
            "MSVC Build Tools (Visual Studio 2026)", "Microsoft.VisualStudio.BuildTools",
            "WinGet / Microsoft", "Microsoft Visual Studio terms", true, true));
        values.put(ManagedComponentId.WSL_UBUNTU, new Descriptor(ManagedComponentId.WSL_UBUNTU, "WSL 2 + Ubuntu",
            "", "Windows optional features / Ubuntu", "Microsoft and Canonical terms", true, true));
        return Map.copyOf(values);
    }

    private record Descriptor(ManagedComponentId id, String displayName, String packageId, String source,
                              String license, boolean administrator, boolean restart) { }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(List<String> command) throws IOException;
    }
}
