package com.zipe.aiteam.runtime;

import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.sandbox.SandboxPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * #1016：分流節點的提示 131772 位元組，超過 Linux 單一參數上限 131072，
 * systemd-run 以 Argument list too long 拒絕，任何重試都一樣失敗。提示必須走 stdin。
 */
class ClaudeCodeRuntimeCommandTest {
    @TempDir Path tmp;

    @Test void promptNeverGoesIntoArgvSoLongTasksCanStillLaunch() {
        var props = mock(AiTeamProperties.class);
        when(props.claudeBin()).thenReturn(Path.of("claude"));
        String huge = "需求與交接紀錄".repeat(40_000);
        for (String session : new String[]{null, "saved-session"}) {
            var req = new AgentRuntime.RuntimeRequest("1016", "triage", tmp, tmp.resolve("pm.md"), huge,
                    List.of("Read"), "{\"type\":\"object\"}", session, 40, Duration.ofSeconds(10),
                    new SandboxPolicy(List.of(), List.of(), 256, 100, 32), "opus", "");

            List<String> cmd = ReflectionTestUtils.invokeMethod(new ClaudeCodeRuntime(props, null), "buildCommand", req);

            assertThat(cmd).contains("-p").noneMatch(arg -> arg.contains("需求與交接紀錄"));
            assertThat(cmd.stream().mapToInt(a -> a.getBytes(StandardCharsets.UTF_8).length).max().orElse(0))
                    .isLessThan(131_072);
            if (session != null) assertThat(cmd).containsSubsequence("--resume", session);
        }
    }
}
