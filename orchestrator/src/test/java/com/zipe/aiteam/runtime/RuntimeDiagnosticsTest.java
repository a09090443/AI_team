package com.zipe.aiteam.runtime;

import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.sandbox.ExecutionSandbox;
import com.zipe.aiteam.sandbox.SandboxPolicy;
import com.zipe.aiteam.sandbox.SandboxUnitCleanup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real portable fake CLI subprocess; no paid model or host systemd calls. */
class RuntimeDiagnosticsTest {
    @TempDir Path tmp;

    public static class PartialOutputCli {
        public static void main(String[] args) throws Exception {
            System.out.print("partial-evidence");
            System.out.flush();
            Thread.sleep(10000);
        }
    }

    @Test void bothRuntimesKeepPartialStdoutOnTimeout() throws Exception {
        Files.createDirectory(tmp.resolve(".git"));
        Path javaBin = Path.of(System.getProperty("java.home"), "bin", "java");
        if (!Files.exists(javaBin)) javaBin = javaBin.resolveSibling("java.exe");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String kind : List.of("claude", "codex", "opencode")) {
            AiTeamProperties props = mock(AiTeamProperties.class);
            when(props.logsPath()).thenReturn(tmp);
            when(props.codexBin()).thenReturn(javaBin);
            when(props.claudeBin()).thenReturn(javaBin);
            when(props.opencodeBin()).thenReturn(javaBin);
            when(props.unitPrefix()).thenReturn("ai-team-runtime-test-");
            ExecutionSandbox sandbox = mock(ExecutionSandbox.class);
            when(sandbox.wrap(anyString(), any(), any(), anyList())).thenReturn(
                    List.of(javaBin.toString(), "-cp", classpath, PartialOutputCli.class.getName()));
            Path prompt = tmp.resolve("role.md");
            Files.writeString(prompt, "test role");
            AgentRuntime runtime;
            if (kind.equals("codex")) {
                var codex = spy(new CodexRuntime(props, sandbox));
                doNothing().when(codex).checkSandbox(any(), any());
                runtime = codex;
            } else if (kind.equals("claude")) runtime = new ClaudeCodeRuntime(props, sandbox);
            else runtime = new OpenCodeRuntime(props, sandbox);
            var request = new AgentRuntime.RuntimeRequest(kind, "pm", tmp, prompt, "test", List.of(),
                    null, null, 1, Duration.ofSeconds(1),
                    new SandboxPolicy(List.of(), List.of(), 256, 100, 32), "");
            try (var cleanup = mockStatic(SandboxUnitCleanup.class)) {
                cleanup.when(() -> SandboxUnitCleanup.terminate(anyString(), any(Process.class))).thenAnswer(call -> {
                    Process launcher = call.getArgument(1);
                    launcher.destroyForcibly();
                    assertThat(launcher.waitFor(2, TimeUnit.SECONDS)).isTrue();
                    return new SandboxUnitCleanup.Result(SandboxUnitCleanup.Status.UNKNOWN, call.getArgument(0),
                            "Fake CLI has no systemd unit; launcher reaped");
                });
                Throwable error = catchThrowable(() -> runtime.execute(request));
                assertThat(error).isInstanceOf(RuntimeTimedOut.class)
                        .hasMessageContaining("逾時").hasMessageContaining("stdout").hasMessageContaining("尚未確認");
                RuntimeTimedOut timeout = RuntimeTimedOut.find(error);
                Path stdout = tmp.resolve(kind).resolve(kind.equals("codex") ? "pm.codex.jsonl"
                        : kind.equals("opencode") ? "pm.opencode.jsonl" : "pm.stdout.json");
                assertThat(timeout.phase()).isEqualTo(RuntimeTimedOut.Phase.MODEL);
                assertThat(timeout.runtimeId()).isEqualTo(runtime.id());
                assertThat(timeout.stdoutLog()).isEqualTo(stdout);
                assertThat(timeout.stderrLog()).exists();
                assertThat(timeout.cleanup().status()).isEqualTo(SandboxUnitCleanup.Status.UNKNOWN);
                assertThat(timeout.unit()).startsWith("ai-team-runtime-test-" + kind + "-pm-").doesNotContain("*");
                assertThat(Files.readString(stdout)).isEqualTo("partial-evidence");
                cleanup.verify(() -> SandboxUnitCleanup.terminate(eq(timeout.unit()), any(Process.class)));
            }
        }
    }
}
