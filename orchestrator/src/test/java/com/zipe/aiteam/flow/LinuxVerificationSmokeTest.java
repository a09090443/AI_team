package com.zipe.aiteam.flow;

import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.sandbox.*;
import com.zipe.aiteam.workspace.WorkspaceManager;
import com.zipe.aiteam.task.Task;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** opt-in：只在指定隔離目錄做真實 Linux/systemd 與主 repo 基準建置，不啟動 Slack／模型。 */
@EnabledIfEnvironmentVariable(named = "AI_TEAM_LINUX_SMOKE", matches = "true")
class LinuxVerificationSmokeTest {
    Path root;
    AiTeamProperties props;
    ExecutionSandbox sandbox;
    IndependentVerifier verifier;
    @BeforeEach void setup() throws Exception {
        Path parent = Path.of(System.getenv("AI_TEAM_SMOKE_ROOT")).toRealPath();
        if (parent.startsWith(Path.of("/tmp"))) throw new IllegalArgumentException("PrivateTmp 隔離不能使用 /tmp fixture");
        root = Files.createTempDirectory(parent, "smoke-");
        props = mock(AiTeamProperties.class);
        when(props.homePath()).thenReturn(root); when(props.logsPath()).thenReturn(root.resolve("logs"));
        when(props.requiredBuildTools()).thenReturn(List.of("git", "java", "mvn"));
        when(props.workspacesPath()).thenReturn(root.resolve("workspaces"));
        when(props.unitPrefix()).thenReturn("ai-team-");
        sandbox = new ExecutionSandbox(props); sandbox.selfTest(); sandbox.requireReadyForCodeChanges();
        verifier = new IndependentVerifier(props, sandbox, new WorkspaceManager(props));
    }
    @Test void finalBaselineBuildRunsInFreshClone() throws Exception {
        Path source = Path.of(System.getenv("AI_TEAM_SMOKE_REPO")).toRealPath();
        Path target = root.resolve("workspaces/task-900001"); Files.createDirectories(target.getParent());
        Process clone = new ProcessBuilder("git", "clone", "--no-hardlinks", "--quiet", "--", source.toString(), target.toString())
                .redirectErrorStream(true).redirectOutput(root.resolve("fixture-clone.log").toFile()).start();
        assertThat(clone.waitFor(30, TimeUnit.SECONDS)).isTrue(); assertThat(clone.exitValue()).isZero();
        FlowRun flow = new FlowRun(TeamCatalog.load(Path.of("teams/spring-starters.json"), key -> source.toString()));
        flow.baselineRevision = "deployment-baseline-smoke";
        flow.independentEvidence = verifier.verify(new Task("900001", "none", "none", "deployment", "baseline smoke"), flow);
        assertThat(verifier.blockingReason(flow, flow.independentEvidence.head())).isNull();
        System.out.println("INDEPENDENT_BASELINE_RECEIPT=" + flow.independentEvidence);
    }
    @Test void trackedPinAndPrivateJavaHomeAreEnforced() throws Exception {
        Path work = Files.createDirectory(root.resolve("work")); Path home = Files.createDirectory(work.resolve("home"));
        Path pin = work.resolve("pinned.txt"); Files.writeString(pin, "original");
        SandboxPolicy policy = new SandboxPolicy(List.of(work), List.of(pin), 512, 100, 64);
        Path log = root.resolve("denial.log");
        assertThatThrownBy(() -> verifier.execute(List.of("sh", "-c", "printf changed > pinned.txt"), work, home, policy, log,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(20))).hasMessageContaining("exit=");
        assertThat(Files.readString(pin)).isEqualTo("original");
        Path javaLog = root.resolve("java-home.log");
        verifier.execute(List.of("java", "-XshowSettings:properties", "-version"), work, home, policy, javaLog,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(20));
        assertThat(Files.readString(javaLog)).contains("user.home = " + home);
    }
    @Test void timeoutTerminatesWholeUnit() throws Exception {
        Path work = Files.createDirectory(root.resolve("work")); Path home = Files.createDirectory(work.resolve("home"));
        SandboxPolicy policy = new SandboxPolicy(List.of(work), List.of(), 256, 100, 32);
        assertThatThrownBy(() -> verifier.execute(List.of("sh", "-c", "sleep 40 & wait"), work, home, policy, root.resolve("timeout.log"),
                System.nanoTime() + TimeUnit.SECONDS.toNanos(2))).isInstanceOf(IllegalStateException.class);
        Process check = new ProcessBuilder("systemctl", "--user", "list-units", "ai-team-check-*", "--no-legend", "--plain")
                .redirectErrorStream(true).start();
        String output = new String(check.getInputStream().readAllBytes());
        assertThat(check.waitFor(5, TimeUnit.SECONDS)).isTrue(); assertThat(output).isBlank();
    }
}
