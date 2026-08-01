package com.sqlteacher.infrastructure.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.support.DiagnosticBundleService;
import com.sqlteacher.application.support.DiagnosticSelection;
import com.sqlteacher.application.update.ApplicationBuildInfo;
import com.sqlteacher.infrastructure.system.AtomicJsonFile;
import com.sqlteacher.infrastructure.system.SensitiveDataRedactor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class FileDiagnosticBundleService implements DiagnosticBundleService {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final Path supportDirectory;
    private final Path lifecycleFile;
    private final Path errorsFile;
    private final boolean previousRunCrashed;

    public FileDiagnosticBundleService(Path dataDirectory) {
        supportDirectory = dataDirectory.toAbsolutePath().normalize().resolve("support");
        lifecycleFile = supportDirectory.resolve("lifecycle.json");
        errorsFile = supportDirectory.resolve("recent-errors.json");
        Lifecycle old = AtomicJsonFile.read(lifecycleFile, Lifecycle.class, new Lifecycle("NEW", Instant.now(), 0));
        previousRunCrashed = !Set.of("NEW", "CLEAN_SHUTDOWN").contains(old.state());
        int failures = previousRunCrashed ? old.consecutiveFailures() + 1 : 0;
        AtomicJsonFile.write(lifecycleFile, new Lifecycle("STARTING", Instant.now(), failures));
    }

    @Override public Map<String, Object> preview(DiagnosticSelection selection) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("application", Map.of("version", ApplicationBuildInfo.current().version().toString()));
        if (selection.environment()) result.put("environment", environment());
        if (selection.recentErrors()) result.put("recentErrors", recentErrors());
        if (selection.networkSummary()) result.put("network", Map.of("cloud", "not-probed", "ollama", "not-probed"));
        if (selection.updateState()) result.put("update", Map.of("state", "not-checked"));
        return Collections.unmodifiableMap(result);
    }

    @Override public Path export(DiagnosticSelection selection, Path destinationDirectory) {
        try {
            Path directory = destinationDirectory.toAbsolutePath().normalize();
            Files.createDirectories(directory);
            String name = "SQLTeacher-diagnostics-" + Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID().toString().substring(0, 8) + ".zip";
            Path target = directory.resolve(name).normalize();
            if (!target.startsWith(directory)) throw new IllegalArgumentException("diagnostic destination is invalid");
            Map<String, byte[]> entries = new LinkedHashMap<>();
            Map<String, Object> preview = preview(selection);
            for (Map.Entry<String, Object> item : preview.entrySet()) entries.put(item.getKey() + ".json", JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(item.getValue()));
            entries.put("README.txt", ("SQLTeacher 诊断包\n生成时间：" + Instant.now() + "\n本文件不包含数据库、SQL、Prompt、密码、Token 或 AI Key。\n").getBytes(StandardCharsets.UTF_8));
            List<Map<String, Object>> manifestEntries = new ArrayList<>();
            for (Map.Entry<String, byte[]> item : entries.entrySet()) manifestEntries.add(Map.of("name", item.getKey(), "size", item.getValue().length, "sha256", sha256(item.getValue())));
            entries.put("manifest.json", JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of("schemaVersion", 1, "createdAt", Instant.now(), "files", manifestEntries)));
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE_NEW))) {
                for (Map.Entry<String, byte[]> item : entries.entrySet()) { zip.putNextEntry(new ZipEntry(item.getKey())); zip.write(item.getValue()); zip.closeEntry(); }
            }
            return target;
        } catch (IOException error) { throw new IllegalStateException("Unable to create diagnostic bundle", error); }
    }

    @Override public synchronized void recordFailure(Throwable error, String source) {
        List<ErrorEntry> entries = new ArrayList<>(recentErrors());
        List<String> frames = Arrays.stream(error.getStackTrace()).limit(12).map(frame -> SensitiveDataRedactor.redact(frame.toString())).toList();
        entries.add(new ErrorEntry(UUID.randomUUID().toString(), Instant.now(), safe(source), error.getClass().getSimpleName(), frames));
        if (entries.size() > 50) entries = new ArrayList<>(entries.subList(entries.size() - 50, entries.size()));
        AtomicJsonFile.write(errorsFile, entries);
    }

    @Override public boolean previousRunCrashed() { return previousRunCrashed; }
    @Override public void markUiReady() { AtomicJsonFile.write(lifecycleFile, new Lifecycle("UI_READY", Instant.now(), 0)); }
    @Override public void markCleanShutdown() { AtomicJsonFile.write(lifecycleFile, new Lifecycle("CLEAN_SHUTDOWN", Instant.now(), 0)); }

    private Map<String, Object> environment() {
        return Map.of("os", System.getProperty("os.name", "unknown") + " " + System.getProperty("os.version", "unknown"),
            "architecture", System.getProperty("os.arch", "unknown"), "java", System.getProperty("java.version", "unknown"),
            "locale", Locale.getDefault().toLanguageTag(), "timezone", TimeZone.getDefault().getID());
    }
    private List<ErrorEntry> recentErrors() {
        ErrorEntry[] values = AtomicJsonFile.read(errorsFile, ErrorEntry[].class, new ErrorEntry[0]);
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        return Arrays.stream(values).filter(item -> item.time().isAfter(cutoff)).toList();
    }
    private static String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.GeneralSecurityException error) { throw new IllegalStateException(error); }
    }
    private static String safe(String value) {
        String redacted = SensitiveDataRedactor.redact(value);
        return redacted.substring(0, Math.min(120, redacted.length()));
    }
    private record Lifecycle(String state, Instant changedAt, int consecutiveFailures) { }
    public record ErrorEntry(String id, Instant time, String source, String exceptionType, List<String> frames) {
        public ErrorEntry { frames = List.copyOf(frames); }
    }
}
