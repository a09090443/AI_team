package com.zipe.aiteam.sandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class SandboxUnitCleanupTest {
    @TempDir Path root;
    final String unit = "ai-team-example-1048-service-unique";
    @Test void stopsExactServiceBeforeReapingLauncher() throws Exception {
        Process launcher = mock(Process.class);
        var calls = new ArrayList<List<String>>();
        var result = SandboxUnitCleanup.terminate(unit, launcher, (argv, timeout) -> {
            verify(launcher, never()).destroyForcibly();
            calls.add(argv);
            return new SandboxUnitCleanup.Reply(0, "LoadState=loaded\nActiveState=inactive\nControlGroup=\n");
        }, root);
        assertThat(result.status()).isEqualTo(SandboxUnitCleanup.Status.CONFIRMED);
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0)).containsExactly("systemctl", "--user", "stop", unit + ".service");
        assertThat(calls.stream().flatMap(List::stream)).noneMatch(x -> x.contains("*"));
        verify(launcher).destroyForcibly();
    }
    @Test void escalatesOnlySameUnitAndChecksCgroup() throws Exception {
        Files.createDirectory(root.resolve("owned"));
        Files.writeString(root.resolve("owned/cgroup.events"), "populated 0\n");
        var calls = new ArrayList<List<String>>();
        var result = SandboxUnitCleanup.terminate(unit, null, (argv, timeout) -> {
            calls.add(argv);
            if (calls.size() == 1) return new SandboxUnitCleanup.Reply(-1, "");
            return new SandboxUnitCleanup.Reply(0, "ActiveState=inactive\nControlGroup=/owned\n");
        }, root);
        assertThat(result.status()).isEqualTo(SandboxUnitCleanup.Status.CONFIRMED);
        assertThat(calls.get(1)).containsExactly("systemctl", "--user", "kill", "--kill-whom=all", "--signal=SIGKILL", unit + ".service");
    }
    @Test void populatedGroupIsNeverReportedClean() throws Exception {
        Files.createDirectory(root.resolve("owned"));
        Files.writeString(root.resolve("owned/cgroup.events"), "populated 1\n");
        var result = SandboxUnitCleanup.terminate(unit, null, (argv, timeout) ->
                new SandboxUnitCleanup.Reply(0, "ActiveState=failed\nControlGroup=/owned\n"), root);
        assertThat(result.status()).isEqualTo(SandboxUnitCleanup.Status.FAILED);
    }
    @Test void missingManagerStillReapsLauncherAndPreservesInterrupt() {
        Process launcher = mock(Process.class);
        Thread.currentThread().interrupt();
        try {
            var result = SandboxUnitCleanup.terminate(unit, launcher, (argv, timeout) -> {
                assertThat(Thread.currentThread().isInterrupted()).isFalse();
                throw new java.io.IOException("no manager");
            }, root);
            assertThat(result.status()).isEqualTo(SandboxUnitCleanup.Status.UNKNOWN);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(launcher).destroyForcibly();
        } finally { Thread.interrupted(); }
    }
    @Test void rejectsGlobsWithoutInvokingSystemctl() {
        var result = SandboxUnitCleanup.terminate("ai-team-*", null, (argv, timeout) -> { throw new AssertionError(); }, root);
        assertThat(result.status()).isEqualTo(SandboxUnitCleanup.Status.FAILED);
    }
    @Test void collectedUnitRequiresSuccessfulStop() {
        var result = SandboxUnitCleanup.terminate(unit, null, (argv, timeout) ->
                new SandboxUnitCleanup.Reply(1, "LoadState=not-found\n"), root);
        assertThat(result.status()).isEqualTo(SandboxUnitCleanup.Status.UNKNOWN);
    }
    @Test void collectedUnitAfterSuccessfulStopIsConfirmed() {
        var result = SandboxUnitCleanup.terminate(unit, null, (argv, timeout) ->
                new SandboxUnitCleanup.Reply(0, "LoadState=not-found\n"), root);
        assertThat(result.status()).isEqualTo(SandboxUnitCleanup.Status.CONFIRMED);
    }
    @Test void stopInterruptionStillReapsLauncherAndRestoresFlag() {
        Process launcher = mock(Process.class);
        try {
            var result = SandboxUnitCleanup.terminate(unit, launcher, (argv, timeout) -> { throw new InterruptedException(); }, root);
            assertThat(result.status()).isEqualTo(SandboxUnitCleanup.Status.UNKNOWN);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(launcher).destroyForcibly();
        } finally { Thread.interrupted(); }
    }
    @Test void wrappingAlwaysConfiguresWholeGroupTermination() {
        var sandbox = new ExecutionSandbox(null);
        var command = sandbox.wrap(unit, new SandboxPolicy(List.of(), List.of(), 256, 100, 32), root, List.of("sleep", "10"));
        assertThat(command).containsSubsequence("-p", "KillMode=control-group");
        assertThat(command).containsSubsequence("-p", "TimeoutStopSec=3s");
        assertThat(command).containsSubsequence("-p", "SendSIGKILL=yes");
    }
}
