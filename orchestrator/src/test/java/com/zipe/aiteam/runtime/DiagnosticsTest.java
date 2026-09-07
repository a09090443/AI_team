package com.zipe.aiteam.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class DiagnosticsTest {
    @Test void nestedSystemCauseRemainsVisible() {
        String text = Diagnostics.describe(new AgentRuntime.RuntimeExecutionException(
                "無法執行 Codex", new IOException("error=2, No such file or directory")));
        assertThat(text).startsWith("IOException: error=2").contains("無法執行 Codex");
    }

    @Test void masksCredentialsBeforeClipping() {
        String text = Diagnostics.safe("TOKEN=private123 --password 'abc def' Bearer secret456 "
                + "github_pat_example https://user:pwd@example.com real-env-secret", Map.of("API_KEY", "real-env-secret"));
        assertThat(text).doesNotContain("private123", "abc def", "secret456", "github_pat_example",
                "user:pwd", "real-env-secret");
        assertThat(text).contains("[REDACTED]", "example.com");
    }

    @Test void denialContainsCommandIdAndExplicitlyUnknownReason() throws Exception {
        var denied = new ObjectMapper().readTree("""
            [{"tool_name":"Bash","tool_use_id":"tool-42",
              "tool_input":{"command":"git diff --stat"}}]
            """);
        assertThat(Diagnostics.toolFailures(denied)).contains("git diff --stat", "tool-42",
                "未提供具體拒絕理由");
    }

    @Test void maxTurnsNeverInventsUncommittedStateOrCause() {
        String detail = new ClaudeCodeRuntime(null, null).describeFailure(1,
                "{\"subtype\":\"error_max_turns\",\"num_turns\":15}", Path.of("/logs/stdout.json"));
        assertThat(detail).contains("error_max_turns", "15", "尚未確認", "另查 Git", Path.of("/logs/stdout.json").toString())
                .doesNotContain("未 commit", "可能是任務太大");
    }
}
