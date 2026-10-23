package com.zipe.aiteam.runtime;
import com.zipe.aiteam.sandbox.SandboxUnitCleanup;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;
class RuntimeTimedOutTest {
    @Test void findsWrappedTimeoutAndPreservesEvidence() {
        var cleanup = new SandboxUnitCleanup.Result(SandboxUnitCleanup.Status.UNKNOWN, "ai-team-1048-service-unique", "unavailable");
        var timeout = new RuntimeTimedOut("codex", Duration.ofMinutes(30), Path.of("events.jsonl"), Path.of("stderr.log"), cleanup);
        assertThat(RuntimeTimedOut.find(new Exception("wrapped", timeout))).isSameAs(timeout);
        assertThat(timeout).isInstanceOf(AgentRuntime.RuntimeExecutionException.class);
        assertThat(timeout.cleanup()).isSameAs(cleanup);
        assertThat(timeout.stdoutLog()).isEqualTo(Path.of("events.jsonl"));
        assertThat(timeout.unit()).isEqualTo(cleanup.unit());
        assertThat(timeout.timeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(timeout.runtimeId()).isEqualTo("codex");
    }
    @Test void handlesNullAndCyclicCauses() {
        assertThat(RuntimeTimedOut.find(null)).isNull();
        var a = new Exception("a"); var b = new Exception("b", a); a.initCause(b);
        assertThat(RuntimeTimedOut.find(a)).isNull();
    }
}
