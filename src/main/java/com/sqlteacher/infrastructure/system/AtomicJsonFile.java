package com.sqlteacher.infrastructure.system;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class AtomicJsonFile {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private AtomicJsonFile() { }

    public static <T> T read(Path path, Class<T> type, T fallback) {
        if (!Files.isRegularFile(path)) return fallback;
        try { return JSON.readValue(Files.readAllBytes(path), type); }
        catch (IOException | RuntimeException error) {
            try { Files.move(path, path.resolveSibling(path.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException ignored) { }
            return fallback;
        }
    }

    public static void write(Path path, Object value) {
        try {
            Files.createDirectories(path.toAbsolutePath().normalize().getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException error) { throw new IllegalStateException("Unable to save application state", error); }
    }
}
