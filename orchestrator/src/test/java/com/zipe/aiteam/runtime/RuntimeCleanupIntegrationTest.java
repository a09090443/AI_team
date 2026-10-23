package com.zipe.aiteam.runtime;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.sandbox.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeCleanupIntegrationTest {
    @TempDir Path tmp;
    @ParameterizedTest
    @CsvSource({"codex,false", "claude,false", "opencode,false", "codex,true", "claude,true", "opencode,true"})
    void timeoutAndInterruptCleanExactUnitAndKeepSession(String kind, boolean interrupt) throws Exception {
        Files.createDirectory(tmp.resolve(".git"));
        var props = mock(AiTeamProperties.class);
        when(props.logsPath()).thenReturn(tmp.resolve("logs"));
        when(props.userHome()).thenReturn(tmp);
        when(props.unitPrefix()).thenReturn("ai-team-owned-");
        when(props.codexBin()).thenReturn(Path.of("codex"));
        when(props.claudeBin()).thenReturn(Path.of("claude"));
        when(props.opencodeBin()).thenReturn(Path.of("opencode"));
        Path prompt = tmp.resolve("role.md"); Files.writeString(prompt, "role instructions");
        var req = new AgentRuntime.RuntimeRequest("1048", "service", tmp, prompt, "prompt", List.of("Read"),
                null, "saved-session", 4, Duration.ofSeconds(1), new SandboxPolicy(List.of(), List.of(),256,100,32), "", "");
        var sandbox = mock(ExecutionSandbox.class);
        var unit = new AtomicReference<String>();
        when(sandbox.wrap(anyString(), any(), any(), anyList())).thenAnswer(call -> {
            unit.set(call.getArgument(0)); return List.of("fake-launcher");
        });
        AgentRuntime runtime;
        if (kind.equals("codex")) {
            var codex = spy(new CodexRuntime(props, sandbox));
            doNothing().when(codex).checkSandbox(any(), any()); runtime = codex;
        } else if (kind.equals("claude")) runtime = new ClaudeCodeRuntime(props, sandbox);
        else runtime = new OpenCodeRuntime(props, sandbox);
        Process process = mock(Process.class);
        when(process.getOutputStream()).thenReturn(new java.io.ByteArrayOutputStream());
        if (interrupt) when(process.waitFor(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException());
        else when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(false);
        var sessions = new ArrayList<String>();
        try (var starts = mockStatic(ArgumentListTooLong.class);
             var capture = mockStatic(SessionCapture.class);
             var cleanup = mockStatic(SandboxUnitCleanup.class)) {
            starts.when(() -> ArgumentListTooLong.start(any())).thenAnswer(call -> {
                ProcessBuilder pb = call.getArgument(0);
                Files.writeString(pb.redirectOutput().file().toPath(), "partial stdout evidence");
                return process;
            });
            capture.when(() -> SessionCapture.await(any(), any(), any(), any())).thenAnswer(call -> {
                ((java.util.function.Consumer<String>)call.getArgument(3)).accept("captured-session");
                if (interrupt) throw new InterruptedException(); return false;
            });
            capture.when(() -> SessionCapture.await(any(), any(), any(), any(), any())).thenAnswer(call -> {
                ((java.util.function.Consumer<String>)call.getArgument(3)).accept("captured-session");
                if (interrupt) throw new InterruptedException(); return false;
            });
            cleanup.when(() -> SandboxUnitCleanup.terminate(anyString(), same(process))).thenAnswer(call ->
                    new SandboxUnitCleanup.Result(SandboxUnitCleanup.Status.CONFIRMED, call.getArgument(0), "empty"));
            try {
                var error = catchThrowable(() -> runtime.execute(req, sessions::add));
                assertThat(error).isInstanceOf(AgentRuntime.RuntimeExecutionException.class);
                if (interrupt) {
                    assertThat(RuntimeTimedOut.find(error)).isNull();
                    assertThat(Thread.currentThread().isInterrupted()).isTrue();
                    assertThat(error.getMessage()).contains("CONFIRMED");
                } else {
                    var timed = RuntimeTimedOut.find(error);
                    assertThat(timed).isNotNull();
                    assertThat(timed.phase()).isEqualTo(RuntimeTimedOut.Phase.MODEL);
                    assertThat(timed.unit()).isEqualTo(unit.get());
                    assertThat(timed.stdoutLog().getParent()).isEqualTo(tmp.resolve("logs/1048"));
                    assertThat(Files.readString(timed.stdoutLog())).isEqualTo("partial stdout evidence");
                }
                cleanup.verify(() -> SandboxUnitCleanup.terminate(unit.get(), process));
                assertThat(sessions).isNotEmpty();
                assertThat(unit.get()).startsWith("ai-team-owned-1048-service-").doesNotContain("*");
            } finally { Thread.interrupted(); }
        }
    }
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void preflightUsesItsOwnUnitAndReportsPhase(boolean interrupt) throws Exception {
        var props = mock(AiTeamProperties.class);
        when(props.codexBin()).thenReturn(Path.of("codex"));
        when(props.unitPrefix()).thenReturn("ai-team-owned-");
        var sandbox = mock(ExecutionSandbox.class);
        var unit = new AtomicReference<String>();
        when(sandbox.wrap(anyString(), any(), any(), anyList())).thenAnswer(call -> {
            unit.set(call.getArgument(0)); return List.of("fake-launcher");
        });
        var req = new AgentRuntime.RuntimeRequest("1048", "service", tmp, tmp.resolve("role.md"), "prompt",
                List.of(), null, null, 4, Duration.ofSeconds(1), new SandboxPolicy(List.of(),List.of(),256,100,32), "", "");
        Process process = mock(Process.class);
        when(process.getOutputStream()).thenReturn(new java.io.ByteArrayOutputStream());
        if (interrupt) when(process.waitFor(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException());
        else when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(false);
        try (var starts = mockStatic(ArgumentListTooLong.class); var cleanup = mockStatic(SandboxUnitCleanup.class)) {
            starts.when(() -> ArgumentListTooLong.start(any())).thenReturn(process);
            cleanup.when(() -> SandboxUnitCleanup.terminate(anyString(), same(process))).thenAnswer(call ->
                    new SandboxUnitCleanup.Result(SandboxUnitCleanup.Status.CONFIRMED, call.getArgument(0), "empty"));
            try {
                var error = catchThrowable(() -> new CodexRuntime(props, sandbox).checkSandbox(req, tmp.resolve("preflight.log")));
                assertThat(error).isInstanceOf(AgentRuntime.RuntimeExecutionException.class);
                if (interrupt) {
                    assertThat(error.getMessage()).contains(unit.get(), "CONFIRMED");
                    assertThat(Thread.currentThread().isInterrupted()).isTrue();
                } else assertThat(RuntimeTimedOut.find(error).phase()).isEqualTo(RuntimeTimedOut.Phase.PREFLIGHT);
                cleanup.verify(() -> SandboxUnitCleanup.terminate(unit.get(), process));
                assertThat(unit.get()).startsWith("ai-team-owned-codex-preflight-");
            } finally { Thread.interrupted(); }
        }
    }
}
