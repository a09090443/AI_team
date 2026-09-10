package com.zipe.aiteam.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class QuotaExceededTest {
    @TempDir Path tmp;
    @Test void sessionLimit辨識且保留重置原文() {
        assertThatThrownBy(() -> QuotaExceeded.check("claude-code", "{\"is_error\":true,\"subtype\":\"success\",\"api_error_status\":429,\"result\":\"You've hit your session limit · resets 6:30pm (UTC)\"}", "log"))
                .isInstanceOf(QuotaExceeded.class).hasMessageContaining("resets 6:30pm (UTC)");
    }
    @Test void onlyExplicitProviderErrorsAreQuota() {
        assertThatThrownBy(() -> QuotaExceeded.check("codex",
                "{\"type\":\"turn.failed\",\"error\":{\"code\":\"usage_limit_reached\"}}", "log"))
                .isInstanceOf(QuotaExceeded.class).hasMessageContaining("重置時間");
        assertThatThrownBy(() -> QuotaExceeded.check("claude-code",
                "{\"is_error\":true,\"result\":\"You've hit your limit · resets 3pm\"}", "log"))
                .isInstanceOf(QuotaExceeded.class).hasMessageContaining("resets 3pm");
        for (String value : new String[]{"429 Too Many Requests", "timeout", "connection refused"}) {
            assertThatCode(() -> QuotaExceeded.check("codex",
                    "{\"type\":\"error\",\"message\":\"" + value + "\"}", "log"))
                    .doesNotThrowAnyException();
        }
    }

    @Test void quotedToolOutputCannotSpoofQuota() {
        assertThatCode(() -> QuotaExceeded.check("codex", """
            {"type":"item.completed","item":{"type":"command_execution","aggregated_output":"usage_limit_reached"}}
            {"type":"item.completed","item":{"type":"agent_message","text":"usage_limit_reached"}}
            """, "log")).doesNotThrowAnyException();
        assertThatCode(() -> QuotaExceeded.check("claude-code",
                "{\"is_error\":false,\"result\":\"usage_limit_reached\"}", "log"))
                .doesNotThrowAnyException();
    }

    @Test void codexSessionIsCapturedWhileProcessIsStillRunning() throws Exception {
        Path output = tmp.resolve("stdout");
        Process process = new ProcessBuilder("/bin/sh", "-c",
                "printf '{\"type\":\"thread.started\",\"thread_id\":\"session-42\"}\\n'; exec sleep 2")
                .redirectOutput(output.toFile()).start();
        AtomicBoolean captured = new AtomicBoolean();
        try {
            assertThat(SessionCapture.await(process, Duration.ofMillis(500), output, id -> {
                assertThat(process.isAlive()).isTrue();
                assertThat(id).isEqualTo("session-42");
                captured.set(true);
            })).isFalse();
            assertThat(captured).isTrue();
            SessionCapture.preserve(output);
            assertThat(Files.exists(output)).isFalse();
            try (var files = Files.list(tmp)) {
                assertThat(files.anyMatch(p -> p.getFileName().toString().startsWith("stdout.previous-"))).isTrue();
            }
        } finally { process.destroyForcibly(); }
    }
}
