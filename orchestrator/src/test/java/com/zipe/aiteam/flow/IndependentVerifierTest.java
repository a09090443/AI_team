package com.zipe.aiteam.flow;

import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.sandbox.*;
import com.zipe.aiteam.task.Task;
import com.zipe.aiteam.workspace.WorkspaceManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IndependentVerifierTest {
    @TempDir Path tmp;
    AiTeamProperties props = mock(AiTeamProperties.class);
    WorkspaceManager workspace = mock(WorkspaceManager.class);
    ExecutionSandbox sandbox = mock(ExecutionSandbox.class);
    IndependentVerifier verifier;
    FlowRun flow;
    List<List<String>> calls = new ArrayList<>();
    List<SandboxPolicy> policies = new ArrayList<>();
    boolean fail;
    String head = "a".repeat(40);
    @BeforeEach void setup() throws Exception {
        Files.createDirectories(tmp.resolve("source/.git"));
        flow = new FlowRun(TeamCatalog.load(Path.of("teams/node-web.json"), key -> tmp.resolve("source").toString()));
        flow.baselineRevision = "baseline";
        when(props.homePath()).thenReturn(tmp.resolve("app"));
        when(workspace.headSha("42")).thenReturn(head); when(workspace.clean("42")).thenReturn(true);
        when(workspace.pathFor("42")).thenReturn(tmp.resolve("source"));
        // 此層只模擬子行程；測收據與權限組裝，不冒充 Linux/systemd 實測。
        verifier = new IndependentVerifier(props, sandbox, workspace) {
            @Override void execute(List<String> argv, Path cwd, Path home, SandboxPolicy policy, Path log, long deadline) throws Exception {
                calls.add(argv); policies.add(policy);
                if (argv.contains("clone")) Files.createDirectories(Path.of(argv.getLast()).resolve(".git"));
                if (argv.contains("checkout")) Files.writeString(cwd.resolve("package.json"), "{}");
                Files.writeString(log, "observed execution");
                if (fail && argv.getFirst().equals("npm")) throw new IllegalStateException("exit=1; log=" + log);
            }
        };
    }
    void verifyNow() throws Exception { flow.independentEvidence = verifier.verify(new Task("42", "c", "t", "u", "mail"), flow); }
    @Test void usesFreshCloneAndConfiguredCommandsNotModelCommands() throws Exception {
        verifyNow();
        assertThat(calls).hasSize(5);
        assertThat(calls.get(0)).contains("--no-local", "--no-checkout", tmp.resolve("source").toString());
        assertThat(calls.get(1)).contains("--detach", head);
        assertThat(calls.subList(2, 5)).isEqualTo(flow.snapshot.definition().project().verification().commands());
        assertThat(verifier.blockingReason(flow, head)).isNull();
        assertThat(flow.independentEvidence.commands()).allMatch(c -> c.exitCode() == 0 && c.sha256().length() == 64);
    }
    @Test void pinsVersionedSourcesAndGitOutsideOutputLogs() throws Exception {
        verifyNow();
        SandboxPolicy policy = policies.get(2);
        Path root = policy.writablePaths().getFirst();
        assertThat(policy.readOnlyPins()).contains(root.resolve("repo/.git"), root.resolve("repo/package.json"));
        assertThat(Path.of(flow.independentEvidence.commands().getFirst().log()).startsWith(root)).isFalse();
        assertThat(policy.writablePaths()).doesNotContain(tmp.resolve("source"));
    }
    @Test void failedCommandNeverCreatesSuccessReceipt() {
        fail = true;
        assertThatThrownBy(this::verifyNow).hasMessageContaining("exit=1");
        assertThat(flow.independentEvidence).isNull();
        assertThat(calls).hasSize(3);
    }
    @Test void changedLogIsRejectedEvenIfAgentClaimsPass() throws Exception {
        verifyNow(); Files.writeString(Path.of(flow.independentEvidence.commands().getFirst().log()), "forged");
        assertThat(verifier.blockingReason(flow, head)).contains("雜湊不符");
    }
    @Test void deletedLogIsNotTreatedAsEmptySuccessfulOutput() throws Exception {
        verifyNow(); Files.delete(Path.of(flow.independentEvidence.commands().getFirst().log()));
        assertThat(verifier.blockingReason(flow, head)).contains("無法核實");
    }
    @Test void staleCommitOrRequirementsInvalidatesReceipt() throws Exception {
        verifyNow(); assertThat(verifier.blockingReason(flow, "b".repeat(40))).contains("失效");
        flow.baselineRevision = "new"; assertThat(verifier.blockingReason(flow, head)).contains("失效");
    }
    @Test void absentReceiptAndMissingCommandAreRejected() throws Exception {
        assertThat(verifier.blockingReason(flow, head)).contains("缺少");
        verifyNow(); var r = flow.independentEvidence;
        flow.independentEvidence = new IndependentVerifier.Receipt(r.head(), r.baseline(), r.configuration(), r.startedAt(), r.completedAt(), List.of());
        assertThat(verifier.blockingReason(flow, head)).contains("必要指令");
    }
    @Test void sandboxNotReadyCannotRunAnyCommand() throws Exception {
        doThrow(new IllegalStateException("sandbox not ready")).when(sandbox).requireReadyForCodeChanges();
        assertThatThrownBy(this::verifyNow).hasMessageContaining("sandbox not ready");
        assertThat(calls).isEmpty();
    }
}
