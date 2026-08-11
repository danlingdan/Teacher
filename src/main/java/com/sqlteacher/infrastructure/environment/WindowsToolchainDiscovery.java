package com.sqlteacher.infrastructure.environment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Discovers Windows development tools without assuming one vendor, version, or installation root. */
public final class WindowsToolchainDiscovery {
    private WindowsToolchainDiscovery() { }

    public static Path javaRuntime() {
        Path compiler = javaCompiler();
        return firstRegular(java.util.Arrays.asList(
            sibling(compiler, "java.exe"),
            binExecutable(System.getProperty("java.home"), "java.exe"),
            binExecutable(System.getenv("JAVA_HOME"), "java.exe"),
            binExecutable(System.getenv("JDK_HOME"), "java.exe"),
            firstOnPath("java.exe")
        ));
    }

    public static Path javaCompiler() {
        List<Path> candidates = new ArrayList<>();
        candidates.add(binExecutable(System.getProperty("java.home"), "javac.exe"));
        candidates.add(binExecutable(System.getenv("JAVA_HOME"), "javac.exe"));
        candidates.add(binExecutable(System.getenv("JDK_HOME"), "javac.exe"));
        candidates.add(firstOnPath("javac.exe"));
        addDiscovered(candidates, roots(
            envPath("ProgramFiles", "Java"),
            envPath("ProgramFiles", "Eclipse Adoptium"),
            envPath("ProgramFiles", "Microsoft"),
            envPath("ProgramFiles", "Amazon Corretto"),
            envPath("ProgramFiles", "Zulu"),
            envPath("USERPROFILE", ".jdks")
        ), "javac.exe", 6);
        return firstRegular(candidates);
    }

    public static Path python() {
        List<Path> candidates = new ArrayList<>(onPath("python.exe", "python3.exe"));
        Path launcher = firstRegular(onPath("py.exe"));
        Path launcherPython = launcher == null ? null : outputPath(launcher, "-3", "-c",
            "import sys; print(sys.executable)");
        candidates.add(launcherPython);
        addDiscovered(candidates, roots(
            envPath("LOCALAPPDATA", "Programs", "Python"),
            envPath("LOCALAPPDATA", "Python"),
            envPath("ProgramFiles", "Python"),
            envPath("USERPROFILE", ".pyenv", "pyenv-win", "versions")
        ), "python.exe", 6);
        return firstUsable(candidates, path -> usable(path, "--version"));
    }

    public static Path visualStudioDeveloperShell() {
        List<Path> candidates = new ArrayList<>();
        candidates.add(envPath("VSINSTALLDIR", "Common7", "Tools", "VsDevCmd.bat"));
        for (Path vswhere : vswhereCandidates()) {
            Path installation = outputPath(vswhere, "-latest", "-products", "*", "-requires",
                "Microsoft.VisualStudio.Component.VC.Tools.x86.x64", "-property", "installationPath");
            if (installation != null) candidates.add(installation.resolve("Common7/Tools/VsDevCmd.bat"));
        }
        addDiscovered(candidates, roots(
            envPath("ProgramFiles", "Microsoft Visual Studio"),
            envPath("ProgramFiles(x86)", "Microsoft Visual Studio")
        ), "VsDevCmd.bat", 7);
        return firstRegular(candidates);
    }

    public static Path cCompiler() {
        return nativeCompiler("gcc.exe", "clang.exe");
    }

    public static Path cppCompiler() {
        return nativeCompiler("g++.exe", "clang++.exe");
    }

    private static Path nativeCompiler(String... names) {
        List<Path> candidates = new ArrayList<>(onPath(names));
        addDiscovered(candidates, roots(
            envPath("ProgramFiles", "LLVM", "bin"),
            Path.of("C:/msys64"), Path.of("C:/mingw64"), Path.of("C:/cygwin64"),
            envPath("ChocolateyInstall", "bin"), envPath("USERPROFILE", "scoop", "apps")
        ), names[0], 6);
        if (names.length > 1) addDiscovered(candidates, roots(
            envPath("ProgramFiles", "LLVM", "bin"), Path.of("C:/msys64"),
            Path.of("C:/mingw64"), Path.of("C:/cygwin64"), envPath("USERPROFILE", "scoop", "apps")
        ), names[1], 6);
        return firstRegular(candidates);
    }

    static Path firstUsable(List<Path> candidates, Predicate<Path> usable) {
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate) && usable.test(candidate)) return candidate;
        }
        return null;
    }

    private static Path firstRegular(List<Path> candidates) {
        return firstUsable(candidates, ignored -> true);
    }

    private static List<Path> onPath(String... names) {
        List<Path> matches = new ArrayList<>();
        String value = System.getenv().getOrDefault("PATH", "");
        for (String entry : value.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) continue;
            for (String name : names) {
                try { matches.add(Path.of(entry, name)); } catch (RuntimeException ignored) { }
            }
        }
        return matches;
    }

    private static Path firstOnPath(String name) {
        return firstRegular(onPath(name));
    }

    private static List<Path> vswhereCandidates() {
        List<Path> candidates = new ArrayList<>(onPath("vswhere.exe"));
        candidates.add(envPath("ProgramFiles(x86)", "Microsoft Visual Studio", "Installer", "vswhere.exe"));
        candidates.add(envPath("ProgramFiles", "Microsoft Visual Studio", "Installer", "vswhere.exe"));
        return candidates.stream().filter(path -> path != null && Files.isRegularFile(path)).toList();
    }

    private static void addDiscovered(List<Path> target, List<Path> roots, String name, int depth) {
        for (Path root : roots) {
            if (root == null || !Files.isDirectory(root)) continue;
            try (var paths = Files.find(root, depth, (path, attributes) -> attributes.isRegularFile()
                && path.getFileName().toString().equalsIgnoreCase(name))) {
                paths.forEach(target::add);
            } catch (IOException | SecurityException ignored) { }
        }
    }

    private static List<Path> roots(Path... values) {
        return java.util.Arrays.stream(values).filter(java.util.Objects::nonNull).toList();
    }

    private static Path envPath(String variable, String... children) {
        String root = System.getenv(variable);
        if (root == null || root.isBlank()) return null;
        try {
            Path path = Path.of(root);
            for (String child : children) path = path.resolve(child);
            return path;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Path binExecutable(String home, String name) {
        if (home == null || home.isBlank()) return null;
        try { return Path.of(home, "bin", name); } catch (RuntimeException ignored) { return null; }
    }

    private static Path sibling(Path path, String name) {
        return path == null || path.getParent() == null ? null : path.getParent().resolve(name);
    }

    private static boolean usable(Path executable, String... arguments) {
        if (executable == null || !Files.isRegularFile(executable)) return false;
        Process process = null;
        try {
            List<String> command = new ArrayList<>();
            command.add(executable.toString());
            command.addAll(List.of(arguments));
            process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException error) {
            return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return false;
        }
    }

    private static Path outputPath(Path executable, String... arguments) {
        if (executable == null || !Files.isRegularFile(executable)) return null;
        Process process = null;
        try {
            List<String> command = new ArrayList<>();
            command.add(executable.toString());
            command.addAll(List.of(arguments));
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(6, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                return null;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines()
                .map(String::trim).filter(line -> !line.isBlank()).findFirst().orElse("");
            if (output.isBlank()) return null;
            Path result = Path.of(output);
            return Files.exists(result) ? result : null;
        } catch (IOException | RuntimeException error) {
            return null;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return null;
        }
    }
}
