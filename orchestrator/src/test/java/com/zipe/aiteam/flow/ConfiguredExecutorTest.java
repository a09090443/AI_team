package com.zipe.aiteam.flow;

import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.runtime.*;
import com.zipe.aiteam.sandbox.ExecutionSandbox;
import com.zipe.aiteam.store.TaskStore;
import com.zipe.aiteam.task.Task;
import com.zipe.aiteam.workspace.WorkspaceManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ConfiguredExecutorTest {
    @Test void parentDialogueMustNotFallBackWhenOriginalWorkspaceIsMissing() throws Exception {
        var parent = new FlowRun(flow.snapshot); parent.workspaceReady = true; parent.workspacePath = repo.toString();
        when(store.flow("parent")).thenReturn(Optional.of(parent));
        when(workspace.pathFor("parent")).thenReturn(repo);
        flow.dialogue = true; flow.parentId = "parent";
        assertThatThrownBy(() -> executor.workingDirectory(new Task("42", "c", "t", "u", "查核"), flow,
                flow.snapshot.definition().nodes().get("triage"))).hasMessageContaining("禁止退回");
        when(workspace.exists("parent")).thenReturn(true);
        assertThat(executor.workingDirectory(new Task("42", "c", "t", "u", "查核"), flow,
                flow.snapshot.definition().nodes().get("triage"))).isEqualTo(repo);
    }
    @Test void preflightFailurePreventsAnyModelCall() throws Exception {
        flow.node = "triage";
        doThrow(new IllegalStateException("missing tool: unavailable")).when(preflight).check(anyString(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> executor.execute(new Task("42", "c", "t", "u", "查核"), flow, flow.snapshot.definition().nodes().get("triage")))
                .hasMessageContaining("missing tool");
        verify(runtime, never()).execute(any(), any());
    }
    OperationPreflight preflight = mock(OperationPreflight.class);
    @Test void readOnlyTriageUsesExistingWorkspaceNotMainClone() throws Exception {
        Path taskRepo = tmp.resolve("task-repo"); Files.createDirectories(taskRepo.resolve(".git"));
        when(workspace.pathFor("42")).thenReturn(taskRepo);
        when(workspace.exists("42")).thenReturn(true);
        flow.workspacePath = taskRepo.toString(); flow.node = "triage";
        AtomicReference<AgentRuntime.RuntimeRequest> captured = new AtomicReference<>();
        when(runtime.execute(any(), any())).thenAnswer(c -> { captured.set(c.getArgument(0)); return null; });
        executor.execute(new Task("42", "c", "t", "u", "查核"), flow, flow.snapshot.definition().nodes().get("triage"));
        assertThat(captured.get().workingDirectory()).isEqualTo(taskRepo);
        assertThat(captured.get().sandboxPolicy().writablePaths()).doesNotContain(taskRepo, repo);
        assertThat(captured.get().prompt()).contains("不是\u771f人核准紀錄", "獨立副本");
    }
    @Test void openBlockedFindingsReachTheNextNodeRegardlessOfContextLength() throws Exception {
        Path taskRepo = tmp.resolve("task-repo"); Files.createDirectories(taskRepo.resolve(".git"));
        when(workspace.pathFor("42")).thenReturn(taskRepo); when(workspace.exists("42")).thenReturn(true);
        flow.workspacePath = taskRepo.toString(); flow.node = "triage";
        flow.context = "填充".repeat(FlowContext.MAX);
        flow.blockedFindings.put("review", new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree("{\"verdict\":\"REQUEST_CHANGES\",\"findings\":[{\"severity\":\"HIGH\",\"detail\":\"DeadlineSocketFactory 破壞隱式 TLS\"}]}"));
        AtomicReference<AgentRuntime.RuntimeRequest> captured = new AtomicReference<>();
        when(runtime.execute(any(), any())).thenAnswer(c -> { captured.set(c.getArgument(0)); return null; });

        executor.execute(new Task("42", "c", "t", "u", "查核"), flow, flow.snapshot.definition().nodes().get("triage"));

        assertThat(captured.get().prompt()).contains("尚未解除的受阻發現")
                .contains("來源節點 review").contains("DeadlineSocketFactory 破壞隱式 TLS");
    }

    @Test void reportStageExplainsWhyThereIsNoIndependentReceipt() {
        var triage = flow.snapshot.definition().nodes().get("triage");
        assertThat(ConfiguredExecutor.receiptView(flow, triage)).contains("本節點階段不產生系統獨立驗證收據");
        assertThat(ConfiguredExecutor.receiptView(flow, triage)).doesNotContain("null");
    }

    @Test void verificationStageSaysReceiptIsStillPending() {
        var verification = flow.snapshot.definition().nodes().get("verification");
        assertThat(ConfiguredExecutor.receiptView(flow, verification)).contains("尚未產生");
    }

    @Test void triageBeforeWorkspaceCreationUsesMainRepo() throws Exception {
        assertThat(executor.workingDirectory(new Task("42", "c", "t", "u", "查核"), flow,
                flow.snapshot.definition().nodes().get("triage"))).isEqualTo(repo);
    }
    @Test void missingOrMismatchedWorkspaceMustNotFallBack() {
        flow.workspacePath = repo.toString();
        var node = flow.snapshot.definition().nodes().get("triage");
        var task = new Task("42", "c", "t", "u", "查核");
        assertThatThrownBy(() -> executor.workingDirectory(task, flow, node)).hasMessageContaining("禁止退回");
        when(workspace.exists("42")).thenReturn(true);
        flow.workspacePath = tmp.resolve("wrong-repo").toString();
        assertThatThrownBy(() -> executor.workingDirectory(task, flow, node)).hasMessageContaining("路徑不符");
    }
    @Test void oldMainRepoSessionCannotResumeInTaskWorkspace() throws Exception {
        Path taskRepo = tmp.resolve("task-repo"); Files.createDirectories(taskRepo.resolve(".git"));
        when(workspace.pathFor("42")).thenReturn(taskRepo); when(workspace.exists("42")).thenReturn(true);
        flow.workspacePath = taskRepo.toString(); flow.node = "triage";
        var task = new Task("42", "c", "t", "u", "查核");
        task.continuation(new com.zipe.aiteam.task.Checkpoint("42", "triage", "claude-code", "opus",
                "old-session", "\n工作目錄：" + repo + "\n", null, null));
        assertThatThrownBy(() -> executor.execute(task, flow, flow.snapshot.definition().nodes().get("triage")))
                .hasMessageContaining("禁止沿用錯誤目錄");
        verify(runtime, never()).execute(any(), any());
    }
    @Test void qaUsesSnapshotModelAndReasoningEffort() throws Exception {
        flow = new FlowRun(TeamCatalog.load(Path.of("teams/spring-starters.json"), key -> repo.toString()));
        flow.node = "plan";
        when(runtime.id()).thenReturn("codex");
        executor = new ConfiguredExecutor(props, new RuntimeRegistry(List.of(runtime)), mock(ExecutionSandbox.class), workspace, store, preflight);
        AtomicReference<AgentRuntime.RuntimeRequest> captured = new AtomicReference<>();
        when(runtime.execute(any(), any())).thenAnswer(c -> { captured.set(c.getArgument(0)); return null; });
        executor.execute(new Task("42", "c", "t", "u", "測試"), flow, flow.snapshot.definition().nodes().get("plan"));
        assertThat(captured.get().model()).isEqualTo("gpt-5.6-sol");
        assertThat(captured.get().reasoningEffort()).isEqualTo("medium");
        assertThat(captured.get().prompt()).contains("qa-java", "qa-reactor", "主要證據", "不得要求放寬 .git 權限");
    }
    @TempDir Path tmp;
    AiTeamProperties props = mock(AiTeamProperties.class);
    WorkspaceManager workspace = mock(WorkspaceManager.class);
    AgentRuntime runtime = mock(AgentRuntime.class);
    TaskStore store = mock(TaskStore.class);
    ConfiguredExecutor executor;
    FlowRun flow;
    Path repo;
    @BeforeEach void setup() throws Exception {
        repo = tmp.resolve("repo"); Files.createDirectories(repo.resolve(".git"));
        Files.createDirectories(repo.resolve("tests")); Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/main.ts"), "source");
        Files.writeString(repo.resolve("AGENTS.md"), "本專案使用 TypeScript，不可修改正式憑證");
        Files.createDirectories(tmp.resolve("user"));
        flow = new FlowRun(TeamCatalog.load(Path.of("teams/node-web.json"), key -> repo.toString()));
        when(props.homePath()).thenReturn(tmp.resolve("app")); when(props.userHome()).thenReturn(tmp.resolve("user"));
        when(workspace.pathFor("42")).thenReturn(repo);
        when(runtime.id()).thenReturn("claude-code"); when(runtime.statePaths(any())).thenReturn(List.of(tmp.resolve("state")));
        when(runtime.escalationPins(any())).thenReturn(List.of());
        executor = new ConfiguredExecutor(props, new RuntimeRegistry(List.of(runtime)), mock(ExecutionSandbox.class), workspace, store, preflight);
    }
    @Test void arbitraryRoleRuntimePromptAndToolsComeFromSnapshot() throws Exception {
        flow.node = "frontend"; Task task = new Task("42", "c", "t", "u", "新增畫面");
        AtomicReference<AgentRuntime.RuntimeRequest> captured = new AtomicReference<>();
        when(runtime.execute(any(), any())).thenAnswer(c -> { captured.set(c.getArgument(0)); return null; });
        executor.execute(task, flow, flow.snapshot.definition().nodes().get("frontend"));
        var request = captured.get();
        assertThat(request.role()).isEqualTo("frontend"); assertThat(request.model()).isEqualTo("sonnet");
        assertThat(request.allowedTools()).contains("Bash(npm *)").doesNotContain("Bash(mvn *)");
        assertThat(request.prompt()).contains("Node Web", "TypeScript", "新增畫面");
        assertThat(Files.readString(request.systemPromptFile())).isEqualTo(flow.snapshot.assets().get("prompts/implement.md"));
        verify(store).saveCheckpoint(argThat(cp -> cp.role().equals("frontend") && cp.runtime().equals("claude-code")));
    }
    @Test void limitedNodeCannotWriteProductOrWholeWorkspace() throws Exception {
        var policy = executor.policy(flow, flow.snapshot.definition().nodes().get("verification"), runtime, repo);
        assertThat(policy.writablePaths()).contains(repo.resolve("tests"), repo.resolve(".git"))
                .doesNotContain(repo, repo.resolve("src"));
        assertThat(policy.readOnlyPins()).contains(repo.resolve("src/main.ts"), repo.resolve(".git/config"));
    }
    @Test void reviewerIsReadOnlyRegardlessOfRoleName() throws Exception {
        var policy = executor.policy(flow, flow.snapshot.definition().nodes().get("review"), runtime, repo);
        assertThat(policy.writablePaths()).containsExactly(tmp.resolve("state"));
    }
    @Test void missingLimitedDirectoriesFailClosed() throws Exception {
        Path empty = tmp.resolve("empty"); Files.createDirectory(empty);
        assertThatThrownBy(() -> executor.policy(flow, flow.snapshot.definition().nodes().get("verification"), runtime, empty))
                .hasMessageContaining("找不到任何");
    }
    @Test void acceptanceContractIsNotSilentlyTruncatedAtOldReportLimit() throws Exception {
        var baseline = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("acceptance", "x".repeat(15000) + "驗收末端");
        flow.receipts.put("intake", baseline); flow.node = "frontend";
        AtomicReference<AgentRuntime.RuntimeRequest> captured = new AtomicReference<>();
        when(runtime.execute(any(), any())).thenAnswer(c -> { captured.set(c.getArgument(0)); return null; });
        executor.execute(new Task("42", "c", "t", "u", "mail"), flow, flow.snapshot.definition().nodes().get("frontend"));
        assertThat(captured.get().prompt()).contains("驗收末端");
    }
    @Test void oversizedContractStopsExplicitlyInsteadOfDroppingRequirements() {
        var baseline = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("acceptance", "x".repeat(128001));
        flow.receipts.put("intake", baseline); flow.node = "frontend";
        assertThatThrownBy(() -> executor.execute(new Task("42", "c", "t", "u", "mail"), flow, flow.snapshot.definition().nodes().get("frontend")))
                .hasMessageContaining("禁止截斷驗收契約");
    }
}
