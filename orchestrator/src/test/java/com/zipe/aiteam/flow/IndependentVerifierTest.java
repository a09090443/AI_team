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
    String exitLog;
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
            @Override void execute(String taskId, List<String> argv, Path cwd, Path home, SandboxPolicy policy, Path log, long deadline) throws Exception {
                assertThat(taskId).isEqualTo("42");
                calls.add(argv); policies.add(policy);
                if (argv.contains("clone")) Files.createDirectories(Path.of(argv.getLast()).resolve(".git"));
                if (argv.contains("checkout")) Files.writeString(cwd.resolve("package.json"), "{}");
                Files.writeString(log, "observed execution");
                if (fail && argv.getFirst().equals("npm")) throw new IllegalStateException("exit=1; log=" + log);
                if (exitLog != null && argv.getFirst().equals("npm")) {
                    Files.writeString(log, exitLog);
                    throw new CommandExit(argv, 1, log);
                }
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

    // ---- 程式失敗與環境失敗要分開：前者交回實作，後者才需要人 ----

    @Test void summaryPicksTheFailingTestsOutOfALongBuildLog() {
        String log = "[INFO] noise\n".repeat(2000)
                + "[ERROR] Tests run: 6, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE! -- in MailTimeoutTest\n"
                + "[ERROR] MailTimeoutTest.boundsElapsed -- Time elapsed: 2.6 s <<< FAILURE!\n"
                + "[ERROR] Failures: \n[ERROR]   MailTimeoutTest.boundsElapsed:142 \n"
                + "[INFO] BUILD FAILURE\n";
        String summary = IndependentVerifier.summarize(log);
        assertThat(summary).contains("MailTimeoutTest.boundsElapsed:142").contains("<<< FAILURE!")
                .doesNotContain("[INFO] noise");
    }

    @Test void summaryFallsBackToTheTailWhenNothingIsRecognised() {
        String log = "line\n".repeat(100) + "last words";
        assertThat(IndependentVerifier.summarize(log)).endsWith("last words").hasLineCount(40);
    }

    @Test void missingDependenciesAreAnEnvironmentProblemNotACodeFailure() {
        assertThat(IndependentVerifier.environmental("[ERROR] Failed to execute goal on project x: "
                + "Could not resolve dependencies for project com.zipe:x")).isTrue();
        assertThat(IndependentVerifier.environmental("[ERROR]   MailTimeoutTest.boundsElapsed:142")).isFalse();
    }

    @Test void aFailingTestBecomesACheckFailureTheImplementerCanFix() {
        exitLog = "[ERROR] Failures: \n[ERROR]   MailTimeoutTest.boundsElapsed:142 \n[INFO] BUILD FAILURE\n";
        assertThatThrownBy(this::verifyNow).isInstanceOf(IndependentVerifier.CheckFailed.class)
                .satisfies(e -> assertThat(((IndependentVerifier.CheckFailed) e).summary()).contains("MailTimeoutTest.boundsElapsed:142"));
        assertThat(flow.independentEvidence).isNull();
    }

    @Test void aBuildThatCannotFetchDependenciesStaysAnEnvironmentFailure() {
        exitLog = "[ERROR] Failed to execute goal: Could not resolve dependencies for project x\n";
        assertThatThrownBy(this::verifyNow).isNotInstanceOf(IndependentVerifier.CheckFailed.class).hasMessageContaining("exit=1");
    }
}
