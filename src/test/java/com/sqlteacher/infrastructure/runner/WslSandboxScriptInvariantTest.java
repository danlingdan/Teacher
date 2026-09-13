package com.sqlteacher.infrastructure.runner;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-shot-database isolation guarantee of the WSL sandbox lives entirely in the
 * runner script (a fresh mktemp workspace plus an unconditional cleanup trap). These
 * assertions pin the invariants so a script edit cannot silently drop them.
 */
class WslSandboxScriptInvariantTest {
    @Test
    void sandboxScriptCreatesDisposableWorkspaceAndAlwaysCleansUp() throws Exception {
        String script = new String(Objects.requireNonNull(
                WslSandboxScriptInvariantTest.class.getResourceAsStream("/runner/wsl-sandbox-runner.sh"),
                "missing resource /runner/wsl-sandbox-runner.sh")
                .readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(script.contains("mktemp -d /tmp/sqlteacher-runner."),
            "sandbox workspace must be a fresh mktemp directory under /tmp");
        assertTrue(script.contains("trap cleanup EXIT INT TERM HUP"),
            "cleanup must run on normal exit and on every termination signal");
        assertTrue(script.contains("rm -rf"),
            "cleanup must remove the whole workspace tree");
    }
}
