package com.sqlteacher.application.system;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Detects missing or tampered program files so the desktop can guide the user to
 * re-run the installer. Never downloads arbitrary files and never touches user data.
 */
public final class InstallIntegrityChecker {
    private InstallIntegrityChecker() { }

    public record Issue(Path file, String kind, String detail) {
        public static final String MISSING = "MISSING";
        public static final String HASH_MISMATCH = "HASH_MISMATCH";
    }

    /**
     * @param baseDirectory app-image program directory (containing {@code bin/}, {@code app/}, ...)
     * @param expectedHash  file name (lowercase) -> SHA-256; only regular files present in the map are hashed
     */
    public static List<Issue> check(Path baseDirectory, Map<String, String> expectedHash) {
        Path base = baseDirectory.toAbsolutePath().normalize();
        List<Issue> issues = new ArrayList<>();
        if (!Files.isDirectory(base)) return List.of(new Issue(base, Issue.MISSING, "program directory does not exist"));
        for (Map.Entry<String, String> entry : expectedHash.entrySet()) {
            Path file = base.resolve(entry.getKey()).normalize();
            if (!file.startsWith(base)) continue;
            if (!Files.isRegularFile(file)) {
                issues.add(new Issue(file, Issue.MISSING, "required file is missing"));
                continue;
            }
            String actual = sha256(file);
            if (!actual.equalsIgnoreCase(entry.getValue())) {
                issues.add(new Issue(file, Issue.HASH_MISMATCH, "file hash does not match the expected build"));
            }
        }
        return List.copyOf(issues);
    }

    /** Convenience for tests: hash a file with the same algorithm the checker uses. */
    public static String sha256(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to read file for integrity check", error);
        }
    }

    static Predicate<Path> under(Path base) {
        return file -> file.toAbsolutePath().normalize().startsWith(base.toAbsolutePath().normalize());
    }
}
