package com.sqlteacher.infrastructure.system;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;

/** Data-directory-scoped single instance coordination; IPC accepts only an authenticated ACTIVATE message. */
public final class ApplicationInstanceCoordinator implements AutoCloseable {
    private final Path directory;
    private final Path endpointFile;
    private FileChannel lockChannel;
    private FileLock lock;
    private ServerSocket server;
    private volatile Runnable activation = () -> { };
    private volatile boolean closed;

    public ApplicationInstanceCoordinator(Path dataDirectory) {
        directory = dataDirectory.toAbsolutePath().normalize().resolve("support");
        endpointFile = directory.resolve("instance.json");
    }

    /** @return true for the primary process; false after the existing window was asked to activate. */
    public boolean acquire() {
        try {
            Files.createDirectories(directory);
            lockChannel = FileChannel.open(directory.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try { lock = lockChannel.tryLock(); } catch (java.nio.channels.OverlappingFileLockException error) { lock = null; }
            if (lock == null) { notifyPrimary(); return false; }
            startServer(); return true;
        } catch (IOException error) { throw new IllegalStateException("Unable to establish SQLTeacher single-instance boundary", error); }
    }

    public void onActivate(Runnable action) { activation = action == null ? () -> { } : action; }

    private void startServer() throws IOException {
        server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
        byte[] tokenBytes = new byte[32]; new SecureRandom().nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        AtomicJsonFile.write(endpointFile, new Endpoint(server.getLocalPort(), token));
        Thread.ofVirtual().name("sqlteacher-instance-listener").start(() -> {
            while (!closed) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    if (!socket.getInetAddress().isLoopbackAddress()) continue;
                    String line = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
                    if (("ACTIVATE " + token).equals(line)) activation.run();
                } catch (IOException error) {
                    if (!closed) try { Thread.sleep(50); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                }
            }
        });
    }

    private void notifyPrimary() {
        Endpoint endpoint = AtomicJsonFile.read(endpointFile, Endpoint.class, null);
        if (endpoint == null || endpoint.port() < 1 || endpoint.port() > 65535 || endpoint.token() == null || endpoint.token().length() > 128) return;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port()), 1500);
            socket.getOutputStream().write(("ACTIVATE " + endpoint.token() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }

    @Override public void close() {
        closed = true;
        try { Files.deleteIfExists(endpointFile); } catch (IOException ignored) { }
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        try { if (lock != null) lock.release(); } catch (IOException ignored) { }
        try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) { }
    }
    private record Endpoint(int port, String token) { }
}
