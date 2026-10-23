package com.zipe.aiteam.sandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Opt-in Linux integration: no model, credentials or deployment executor. */
@EnabledIfEnvironmentVariable(named="AI_TEAM_SANDBOX_CLEANUP_SMOKE", matches="true")
class SandboxUnitCleanupLinuxTest {
    @Test void killsDetachedDescendantButLeavesOtherUnitAlive() throws Exception {
        Path root = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "unit-cleanup-");
        String owned = "ai-team-cleanup-owned-" + UUID.randomUUID();
        String other = "ai-team-cleanup-other-" + UUID.randomUUID();
        var sandbox = new ExecutionSandbox(null);
        var policy = new SandboxPolicy(List.of(root), List.of(), 256, 100, 32);
        Path ready = root.resolve("ready");
        Process sentinel = null, launcher = null;
        try {
            sentinel = new ProcessBuilder(sandbox.wrap(other, policy, root, List.of("sleep", "120")))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            launcher = new ProcessBuilder(sandbox.wrap(owned, policy, root,
                    List.of("sh", "-c", "setsid sh -c 'trap \"\" TERM; echo ready > \"$1\"; while :; do sleep 1; done' child \"$1\" & wait", "parent", ready.toString())))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(ready) && System.nanoTime() < deadline) Thread.sleep(50);
            assertThat(ready).exists();
            var result = SandboxUnitCleanup.terminate(owned, launcher);
            assertThat(result.status()).isEqualTo(SandboxUnitCleanup.Status.CONFIRMED);
            assertThat(launcher.isAlive()).isFalse();
            assertThat(sentinel.isAlive()).isTrue();
        } finally {
            SandboxUnitCleanup.terminate(owned, launcher);
            SandboxUnitCleanup.terminate(other, sentinel);
            try (var paths = Files.walk(root)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }
}
