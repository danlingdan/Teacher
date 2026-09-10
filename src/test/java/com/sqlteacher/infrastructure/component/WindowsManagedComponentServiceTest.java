package com.sqlteacher.infrastructure.component;

import com.sqlteacher.application.component.ManagedComponentId;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsManagedComponentServiceTest {
    @Test
    void shouldExposeEverySupportedComponentWithoutStartingAnInstaller() {
        var statuses = new WindowsManagedComponentService().statuses();

        assertEquals(Set.of(ManagedComponentId.values()), statuses.stream()
            .map(status -> status.id()).collect(Collectors.toSet()));
        assertTrue(statuses.stream().allMatch(status -> !status.source().isBlank()));
        assertTrue(statuses.stream().allMatch(status -> !status.license().isBlank()));
    }

    @Test
    void shouldBuildFixedNonInteractiveWingetArguments() {
        var jdk = WindowsManagedComponentService.wingetArguments(ManagedComponentId.JDK);
        var python = WindowsManagedComponentService.wingetArguments(ManagedComponentId.PYTHON);
        var ollama = WindowsManagedComponentService.wingetArguments(ManagedComponentId.OLLAMA);
        var msvc = WindowsManagedComponentService.wingetArguments(ManagedComponentId.MSVC);

        assertTrue(jdk.contains("EclipseAdoptium.Temurin.21.JDK"));
        assertTrue(python.contains("Python.Python.3.13"));
        assertTrue(ollama.contains("Ollama.Ollama"));
        assertTrue(msvc.contains("Microsoft.VisualStudio.BuildTools"));
        assertTrue(msvc.contains("--override"));
        for (var arguments : java.util.List.of(jdk, python, ollama, msvc)) {
            assertTrue(arguments.contains("--accept-package-agreements"));
            assertTrue(arguments.contains("--accept-source-agreements"));
            assertTrue(arguments.contains("--disable-interactivity"));
            assertFalse(arguments.stream().anyMatch(value -> value.toLowerCase().contains("http")));
        }
        assertThrows(IllegalArgumentException.class,
            () -> WindowsManagedComponentService.wingetArguments(ManagedComponentId.WSL_UBUNTU));
    }

    @Test
    void shouldInstallVerifyAndReportReadyWithAFakeProcess() throws Exception {
        AtomicBoolean ready = new AtomicBoolean();
        var service = new WindowsManagedComponentService(
            ignored -> ready.get(), ignored -> java.util.List.of("fixed-installer"),
            ignored -> true, () -> false, ignored -> new FinishedProcess(0, ready)
        );

        // W6.4：install 异步返回；假进程可能在返回前已完成，两种初始状态都合法。
        var result = service.install(ManagedComponentId.PYTHON, ignored -> { });
        if (!result.ready()) {
            assertEquals("DOWNLOADING_AND_INSTALLING", result.detail());
        }

        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var status = service.statuses().stream()
                .filter(item -> item.id() == ManagedComponentId.PYTHON).findFirst().orElseThrow();
            if (status.ready()) return;
            java.util.concurrent.TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("install never reached READY within 5 seconds");
    }

    @Test
    void shouldCancelARunningInstallation() throws Exception {
        BlockingProcess process = new BlockingProcess();
        var service = new WindowsManagedComponentService(
            ignored -> false, ignored -> java.util.List.of("fixed-installer"),
            ignored -> true, () -> false, ignored -> process
        );
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> service.install(ManagedComponentId.OLLAMA, ignored -> { }));
            assertTrue(process.started.await(2, TimeUnit.SECONDS));

            service.cancel(ManagedComponentId.OLLAMA);

            // 取消后后台线程结束，statuses() 报告 INSTALL_CANCELLED。
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            String detail = "";
            while (System.nanoTime() < deadline) {
                detail = service.statuses().stream()
                    .filter(item -> item.id() == ManagedComponentId.OLLAMA).findFirst().orElseThrow()
                    .detail();
                if ("INSTALL_CANCELLED".equals(detail)) return;
                TimeUnit.MILLISECONDS.sleep(20);
            }
            assertEquals("INSTALL_CANCELLED", detail);
        }
    }

    private static class FinishedProcess extends Process {
        private final int exitCode;
        private final AtomicBoolean ready;

        FinishedProcess(int exitCode, AtomicBoolean ready) {
            this.exitCode = exitCode;
            this.ready = ready;
        }

        @Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
        @Override public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
        @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
        @Override public int waitFor() throws InterruptedException { ready.set(true); return exitCode; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { ready.set(true); return true; }
        @Override public int exitValue() { return exitCode; }
        @Override public void destroy() { }
    }

    private static final class BlockingProcess extends FinishedProcess {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);

        BlockingProcess() { super(-1, new AtomicBoolean()); }

        @Override public int waitFor() throws InterruptedException {
            started.countDown();
            finished.await();
            return -1;
        }

        @Override public void destroy() { finished.countDown(); }
        @Override public Process destroyForcibly() { finished.countDown(); return this; }
        @Override public boolean isAlive() { return finished.getCount() > 0; }
    }
}
