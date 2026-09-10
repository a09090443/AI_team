package com.zipe.aiteam.runtime;

import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.sandbox.ExecutionSandbox;
import com.zipe.aiteam.sandbox.SandboxPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 固定假 CLI 子行程，不呼叫付費模型；驗證真實 ProcessBuilder 的失敗落檔。 */
class RuntimeDiagnosticsTest {
    @TempDir Path tmp;

    @Test void bothRuntimesKeepPartialStdoutOnTimeout() throws Exception {
        Files.createDirectory(tmp.resolve(".git"));
        for (boolean codex : List.of(false, true)) {
            AiTeamProperties props = mock(AiTeamProperties.class);
            when(props.logsPath()).thenReturn(tmp);
            when(props.codexBin()).thenReturn(Path.of("/bin/sh"));
            when(props.claudeBin()).thenReturn(Path.of("/bin/sh"));
            ExecutionSandbox sandbox = mock(ExecutionSandbox.class);
            when(sandbox.wrap(anyString(), any(), any(), anyList())).thenReturn(
                    List.of("/bin/sh", "-c", "printf partial-evidence; exec sleep 5"));
            when(sandbox.wrap(startsWith("ai-team-codex-preflight-"), any(), any(), anyList()))
                    .thenReturn(List.of("/bin/true"));
            Path prompt = tmp.resolve("role.md");
            Files.writeString(prompt, "test role");
            AgentRuntime runtime = codex ? new CodexRuntime(props, sandbox) : new ClaudeCodeRuntime(props, sandbox);
            String id = codex ? "codex" : "claude";
            var request = new AgentRuntime.RuntimeRequest(id, "pm", tmp, prompt, "test", List.of(),
                    null, null, 1, Duration.ofSeconds(1),
                    new SandboxPolicy(List.of(), List.of(), 256, 100, 32), "");
            assertThatThrownBy(() -> runtime.execute(request)).hasMessageContaining("逾時")
                    .hasMessageContaining("stdout").hasMessageContaining("尚未確認");
            assertThat(Files.readString(tmp.resolve(id).resolve(codex ? "pm.codex.jsonl" : "pm.stdout.json")))
                    .isEqualTo("partial-evidence");
        }
    }
}
