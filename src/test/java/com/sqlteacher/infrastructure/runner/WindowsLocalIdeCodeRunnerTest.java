package com.sqlteacher.infrastructure.runner;

import com.sqlteacher.application.runner.CodeRunRequest;
import com.sqlteacher.application.runner.RunnerCancellation;
import com.sqlteacher.application.runner.RunnerFailureReason;
import com.sqlteacher.domain.activity.CodeExecutionLimits;
import com.sqlteacher.domain.activity.CodeLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("runner")
class WindowsLocalIdeCodeRunnerTest {
    @TempDir Path tempDir;

    @Test
    void shouldRunJavaPythonCAndCppWithInstalledLocalToolchains() {
        var runner = new WindowsLocalIdeCodeRunner(tempDir.resolve("runs"));
        assumeTrue(runner.capabilities().stream().allMatch(item -> item.available()),
            () -> "Missing local toolchain: " + runner.capabilities());

        var java = run(runner, CodeLanguage.JAVA, """
            import java.util.Scanner;
            public class Main { public static void main(String[] args) {
                var input = new Scanner(System.in); System.out.println(input.nextInt() + input.nextInt());
            }}
            """);
        var python = run(runner, CodeLanguage.PYTHON,
            "a, b = map(int, input().split())\nprint(a + b)");
        var c = run(runner, CodeLanguage.C, """
            #include <stdio.h>
            int main(void) { int a,b; scanf("%d%d", &a, &b); printf("%d\\n", a+b); return 0; }
            """);
        var cpp = run(runner, CodeLanguage.CPP, """
            #include <iostream>
            int main() { int a,b; std::cin >> a >> b; std::cout << a+b << "\\n"; }
            """);

        for (var result : List.of(java, python, c, cpp)) {
            assertEquals(RunnerFailureReason.NONE, result.failureReason(), result.standardError());
            assertEquals("5", result.standardOutput().trim());
        }
    }

    @Test
    void shouldAllowHostFilesEnvironmentAndNetworkLikeANormalIde() throws Exception {
        Path fixture = tempDir.resolve("host-fixture.txt");
        Files.writeString(fixture, "host-ok");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/probe", exchange -> {
            byte[] body = "net-ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String file = fixture.toString().replace("\\", "\\\\");
            String source = """
                import java.net.URI;
                import java.nio.file.Files;
                import java.nio.file.Path;
                public class Main { public static void main(String[] args) throws Exception {
                    String file = Files.readString(Path.of("%s"));
                    String network = new String(new URI("http://127.0.0.1:%d/probe").toURL()
                        .openStream().readAllBytes());
                    System.out.println(file + "," + network + "," + (System.getenv("PATH") != null));
                }}
                """.formatted(file, server.getAddress().getPort());
            var result = run(new WindowsLocalIdeCodeRunner(tempDir.resolve("network-runs")),
                CodeLanguage.JAVA, source);
            assertEquals(RunnerFailureReason.NONE, result.failureReason(), result.standardError());
            assertEquals("host-ok,net-ok,true", result.standardOutput().trim());
        } finally {
            server.stop(0);
        }
    }

    private static com.sqlteacher.application.runner.CodeRunResult run(WindowsLocalIdeCodeRunner runner,
                                                                        CodeLanguage language,
                                                                        String source) {
        return runner.run(new CodeRunRequest(language, source, "2 3\n", CodeExecutionLimits.defaults()),
            RunnerCancellation.NONE);
    }
}
