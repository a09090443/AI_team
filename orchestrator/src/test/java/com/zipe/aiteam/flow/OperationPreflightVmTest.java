package com.zipe.aiteam.flow;

import com.zipe.aiteam.sandbox.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 不呼叫模型，實測同一 systemd 沙箱的工具／寫入檢查。 */
@EnabledIfEnvironmentVariable(named="AI_TEAM_PREFLIGHT_SMOKE", matches="true")
class OperationPreflightVmTest {
    Path tmp;
    OperationPreflight preflight = new OperationPreflight(null, new ExecutionSandbox(null));
    @BeforeEach void init() throws Exception {
        Path root = Path.of("target/preflight-smoke").toAbsolutePath(); Files.createDirectories(root);
        tmp = Files.createTempDirectory(root, "run-");
        run("git", "init", tmp.toString());
        run("git", "-C", tmp.toString(), "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                "-c", "core.hooksPath=/dev/null", "commit", "--allow-empty", "-m", "fixture");
    }
    void run(String... argv) throws Exception {
        Process p = new ProcessBuilder(argv).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(p.waitFor()).isZero();
    }
    SandboxPolicy policy() { return new SandboxPolicy(List.of(tmp), List.of(), 256, 100, 64); }
    @Test void javaAndNodeToolsArePresentInActualSandbox() throws Exception {
        Path log = tmp.resolve("probe.log");
        preflight.probe(tmp, policy(), List.of("tool", "java", "tool", "mvn", "tool", "node", "tool", "npm", "dir", tmp.toString(), "write", tmp.toString()), log);
        assertThat(Files.readString(log)).containsPattern("[0-9a-f]{40}");
    }
    @Test void missingToolFailsBeforeAgentExecution() throws Exception {
        assertThatThrownBy(() -> preflight.probe(tmp, policy(), List.of("tool", "aiteam_nonexistent_tool_9631"), tmp.resolve("missing.log")))
                .hasMessageContaining("派工前檢查失敗");
        assertThat(Files.readString(tmp.resolve("missing.log"))).contains("missing tool: aiteam_nonexistent_tool_9631");
    }
    @Test void readOnlyPinDeniesWriteDespiteHostPermissions() throws Exception {
        Path pinned = Files.createDirectory(tmp.resolve("pinned"));
        assertThat(Files.isWritable(pinned)).isTrue();
        var policy = new SandboxPolicy(List.of(tmp), List.of(pinned), 256, 100, 64);
        assertThatThrownBy(() -> preflight.probe(tmp, policy, List.of("write", pinned.toString()), tmp.resolve("denied.log")))
                .hasMessageContaining("派工前檢查失敗");
        assertThat(Files.readString(tmp.resolve("denied.log"))).contains("not writable:");
    }
}
