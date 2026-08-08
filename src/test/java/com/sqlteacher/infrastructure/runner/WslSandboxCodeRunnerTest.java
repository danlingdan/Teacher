package com.sqlteacher.infrastructure.runner;

import com.sqlteacher.application.runner.CodeRunRequest;
import com.sqlteacher.application.runner.RunnerFailureReason;
import com.sqlteacher.domain.activity.CodeExecutionLimits;
import com.sqlteacher.domain.activity.CodeLanguage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledOnOs(OS.WINDOWS)
class WslSandboxCodeRunnerTest {
    private static WslSandboxCodeRunner runner;

    @BeforeAll
    static void createRunner() {
        runner = new WslSandboxCodeRunner();
        assumeTrue(runner.capability(CodeLanguage.PYTHON).available(),
            () -> "Python sandbox unavailable: " + runner.capability(CodeLanguage.PYTHON).reasonCode());
    }

    @Test
    void shouldRunJavaPythonCAndCppInIsolatedWorkspace() {
        var java = run(CodeLanguage.JAVA, "public class Main { public static void main(String[] args) { System.out.println(\"java-ok\"); } }", "", limits());
        var python = run(CodeLanguage.PYTHON, "print(input())", "hello\n", limits());
        var c = run(CodeLanguage.C, "#include <stdio.h>\nint main(void){puts(\"c-ok\");return 0;}", "", limits());
        var cpp = run(CodeLanguage.CPP, "#include <iostream>\nint main(){std::cout << \"cpp-ok\\n\";}", "", limits());

        assertEquals(RunnerFailureReason.NONE, java.failureReason(), java.standardError());
        assertEquals("java-ok\n", java.standardOutput());
        assertEquals(RunnerFailureReason.NONE, python.failureReason(), python.standardError());
        assertEquals("hello\n", python.standardOutput());
        assertEquals(RunnerFailureReason.NONE, c.failureReason(), c.standardError());
        assertEquals("c-ok\n", c.standardOutput());
        assertEquals(RunnerFailureReason.NONE, cpp.failureReason(), cpp.standardError());
        assertEquals("cpp-ok\n", cpp.standardOutput());
    }

    @Test
    void shouldDenyNetworkHostFilesAndCredentialInheritance() {
        String source = """
            import os, socket
            checks = []
            try:
                open('/mnt/c/Windows/win.ini').read()
                checks.append('host-visible')
            except OSError:
                checks.append('host-blocked')
            try:
                socket.create_connection(('1.1.1.1', 53), timeout=0.2)
                checks.append('network-visible')
            except OSError:
                checks.append('network-blocked')
            forbidden = {'USERPROFILE', 'APPDATA', 'SQLTEACHER_CLOUD_TOKEN', 'HTTP_PROXY', 'HTTPS_PROXY'}
            checks.append('env-blocked' if forbidden.isdisjoint(os.environ) else 'env-visible')
            print(','.join(checks))
            """;

        var result = run(CodeLanguage.PYTHON, source, "", limits());

        assertEquals(RunnerFailureReason.NONE, result.failureReason(), result.standardError());
        assertEquals("host-blocked,network-blocked,env-blocked\n", result.standardOutput());
        assertFalse(result.standardError().contains(System.getProperty("user.name")));
    }

    @Test
    void shouldStopInfiniteLoopAndOutputFlood() {
        var shortLimits = new CodeExecutionLimits(Duration.ofSeconds(1), Duration.ofSeconds(1),
            128 * CodeExecutionLimits.MEBIBYTE, 4096, 4 * CodeExecutionLimits.MEBIBYTE, 32, 4);

        var timeout = run(CodeLanguage.PYTHON, "while True: pass", "", shortLimits);
        var flood = run(CodeLanguage.PYTHON, "while True: print('x' * 1024)", "", shortLimits);

        assertEquals(RunnerFailureReason.TIME_LIMIT, timeout.failureReason(), timeout.standardError());
        assertEquals(RunnerFailureReason.OUTPUT_LIMIT, flood.failureReason(), flood.standardError());
        assertTrue(flood.resourceUsage().outputBytes() <= shortLimits.outputBytes());
    }

    @Test
    void shouldCancelProcessTreeAndRecoverForNextRun() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try (executor) {
            var future = executor.submit(() -> runner.run(new CodeRunRequest(CodeLanguage.PYTHON, """
                import subprocess, sys, time
                subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(20)'])
                while True: time.sleep(0.1)
                """, "", limits()), cancelled::get));
            TimeUnit.MILLISECONDS.sleep(500);
            cancelled.set(true);
            var result = future.get(10, TimeUnit.SECONDS);
            assertEquals(RunnerFailureReason.CANCELLED, result.failureReason(), result.standardError());
        }

        var recovered = run(CodeLanguage.PYTHON, "print('recovered')", "", limits());
        assertEquals(RunnerFailureReason.NONE, recovered.failureReason(), recovered.standardError());
        assertEquals("recovered\n", recovered.standardOutput());
    }

    @Test
    void shouldBoundMemoryAndSubprocessesAtTheCgroup() {
        var constrained = new CodeExecutionLimits(Duration.ofSeconds(3), Duration.ofSeconds(2),
            64 * CodeExecutionLimits.MEBIBYTE, 16 * 1024, 4 * CodeExecutionLimits.MEBIBYTE, 32, 3);
        var memory = run(CodeLanguage.PYTHON,
            "chunks = []\nwhile True: chunks.append(bytearray(8 * 1024 * 1024))", "", constrained);
        var processes = run(CodeLanguage.PYTHON, """
            import subprocess, sys, time
            children = []
            while True:
                children.append(subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(5)']))
            """, "", constrained);

        assertEquals(RunnerFailureReason.MEMORY_LIMIT, memory.failureReason(), memory.standardError());
        assertEquals(RunnerFailureReason.PROCESS_LIMIT, processes.failureReason(), processes.standardError());
        assertTrue(memory.resourceUsage().wallTime().compareTo(Duration.ofSeconds(8)) < 0);
        assertTrue(processes.resourceUsage().wallTime().compareTo(Duration.ofSeconds(8)) < 0);
        var recovered = run(CodeLanguage.PYTHON, "print('bounded')", "", limits());
        assertEquals(RunnerFailureReason.NONE, recovered.failureReason(), recovered.standardError());
    }

    @Test
    void shouldClassifyCompileAndWorkspaceLimits() {
        var constrained = new CodeExecutionLimits(Duration.ofSeconds(4), Duration.ofSeconds(3),
            256 * CodeExecutionLimits.MEBIBYTE, 16 * 1024, CodeExecutionLimits.MEBIBYTE, 8, 4);
        var compile = run(CodeLanguage.JAVA, "public class Main { this is not Java; }", "", limits());
        var workspace = run(CodeLanguage.PYTHON, """
            for index in range(1000):
                open(f'/work/generated-{index}', 'w').write('x')
            """, "", constrained);

        assertEquals(RunnerFailureReason.COMPILE_ERROR, compile.failureReason(), compile.standardError());
        assertEquals(RunnerFailureReason.WORKSPACE_LIMIT, workspace.failureReason(), workspace.standardError());
    }

    @Test
    void shouldRunConcurrentRequestsAndRecover() throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try (executor) {
            var tasks = new ArrayList<java.util.concurrent.Callable<com.sqlteacher.application.runner.CodeRunResult>>();
            for (int index = 0; index < 4; index++) {
                int value = index;
                tasks.add(() -> run(CodeLanguage.PYTHON, "print('parallel-" + value + "')", "", limits()));
            }
            for (var future : executor.invokeAll(tasks)) {
                assertEquals(RunnerFailureReason.NONE, future.get().failureReason());
            }
        }
        assertEquals(RunnerFailureReason.NONE,
            run(CodeLanguage.PYTHON, "print('after-parallel')", "", limits()).failureReason());
    }

    private static com.sqlteacher.application.runner.CodeRunResult run(CodeLanguage language, String source,
                                                                         String input,
                                                                         CodeExecutionLimits limits) {
        return runner.run(new CodeRunRequest(language, source, input, limits), () -> false);
    }

    private static CodeExecutionLimits limits() {
        return new CodeExecutionLimits(Duration.ofSeconds(4), Duration.ofSeconds(3),
            384 * CodeExecutionLimits.MEBIBYTE, 64 * 1024, 16 * CodeExecutionLimits.MEBIBYTE, 96, 12);
    }
}
