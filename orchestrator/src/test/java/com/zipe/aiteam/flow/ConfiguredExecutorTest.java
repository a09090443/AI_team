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

    @Test void builtInInspectionIsExplainedOnlyToPlanAndVerification() {
        var nodes = flow.snapshot.definition().nodes();
        assertThat(ConfiguredExecutor.inspectionNotice(flow, nodes.get("plan")))
                .contains(OperationContract.INSPECT_CONTENT, "不是指令", "operation_ids", "程式行為");
        assertThat(ConfiguredExecutor.inspectionNotice(flow, nodes.get("verification")))
                .contains(OperationContract.INSPECT_CONTENT, "已提交", "command");
        assertThat(ConfiguredExecutor.inspectionNotice(flow, nodes.get("implementation"))).isEmpty();
        assertThat(ConfiguredExecutor.inspectionNotice(flow, nodes.get("triage"))).isEmpty();
    }

    @Test void verificationSeesSystemComputedChangedFiles() throws Exception {
        var task = new Task("42", "c", "t", "u", "改文件");
        assertThat(executor.changedFiles(task, flow)).isEmpty();
        flow.workspaceReady = true;
        when(workspace.exists("42")).thenReturn(true);
        when(workspace.changedPathsSinceBase("42")).thenReturn(List.of(".agents/skills/test-confirmation/SKILL.md"));
        assertThat(executor.changedFiles(task, flow)).contains("系統查得", ".agents/skills/test-confirmation/SKILL.md");
        when(workspace.changedPathsSinceBase("42")).thenThrow(new WorkspaceManager.WorkspaceException("no base"));
        assertThat(executor.changedFiles(task, flow)).contains("查不到").doesNotContain("SKILL.md");
    }

    @Test void intakeIsToldThatAllowedProtectedPathsAreDelegatedNotStopped() throws Exception {
        // #1014：需求整理一看到 .claude/ 就停下來問人；配置了改派時要說明那些路徑做得到。
        var definition = TeamCatalog.load(Path.of("teams/bestpartner.json"), key -> repo.toString()).definition();
        AgentRuntime claude = mock(AgentRuntime.class);
        when(claude.protectedWritePaths()).thenReturn(List.of(".claude/", ".git/"));
        AgentRuntime codex = mock(AgentRuntime.class);
        when(codex.protectedWritePaths()).thenReturn(List.of());
        String notice = ConfiguredExecutor.protectedPathNotice(definition, "intake", id -> id.equals("codex") ? codex : claude);
        assertThat(notice).contains(".claude/").contains("例外", ".claude/rules/**", "Backend Agent（codex）", "不要因此停下來問人");
    }

    @Test void docOnlyReceiptExplainsThatSkippingBuildIsByDesign() {
        flow.independentEvidence = new IndependentVerifier.Receipt("h", "b", "c", "s", "e", List.of(), List.of("README.md"));
        assertThat(ConfiguredExecutor.receiptView(flow, flow.snapshot.definition().nodes().get("verification")))
                .contains("純文件變更", "README.md", "不是查無建置證據");
    }

    @Test void implementationIsToldNotToBuildForDocOnlyChanges() throws Exception {
        // #1018：只改 docs-site 的一行 Markdown，改派的 Codex 角色仍自行跑 Gradle、撞沙箱後回報受阻。
        var paths = TeamCatalog.load(Path.of("teams/bestpartner.json"), key -> repo.toString()).definition()
                .project().verification().docOnlyPaths();
        assertThat(DocOnlyChanges.qualifies(paths, List.of("docs-site/docs/architecture/tech-stack.md"))).isTrue();
        assertThat(DocOnlyChanges.qualifies(paths, List.of("docs-site/blog/2026-10-04-release.md"))).isTrue();
        assertThat(DocOnlyChanges.qualifies(paths, List.of("docs-site/docusaurus.config.ts"))).isFalse();
        assertThat(DocOnlyChanges.qualifies(paths, List.of("docs-site/src/components/Home.tsx"))).isFalse();
        assertThat(DocOnlyChanges.implementationNotice(paths))
                .contains("docs-site/docs/**", "不要執行後端／前端的編譯與測試操作", "不要因為沒有建置或測試證據而回報 BLOCKED", "照建置規範完整執行");
        assertThat(DocOnlyChanges.implementationNotice(null)).isEmpty();
        assertThat(DocOnlyChanges.implementationNotice(List.of())).isEmpty();
    }

    @Test void delegatedPartAndPrimaryImplementerGetMatchingBoundaries() {
        var fb = new TeamDefinition.Fallback("builder_codex", List.of(".claude/rules/**", ".claude/skills/**"));
        assertThat(ProtectedPathFallback.partNotice(fb, "Backend Agent"))
                .contains(".claude/rules/**", "其餘變更（包含 .agents/", "不要執行建置或測試操作", "不要 commit");
        var part = new FlowRun.DelegatedPart("builder_codex", "h0", "h1", List.of(".claude/rules/a.md"), "改了 a.md 第 3 行");
        assertThat(ProtectedPathFallback.primaryNotice(part, "Backend Agent（codex）"))
                .contains("commit h1", ".claude/rules/a.md", "改了 a.md 第 3 行", "不要修改或還原這些檔案", "建置、測試");
        assertThat(ProtectedPathFallback.partViolations(List.of(".claude/rules/a.md", ".agents/skills/x/SKILL.md", ".claude/settings.json"), fb))
                .containsExactly(".agents/skills/x/SKILL.md", ".claude/settings.json");
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
    @Test void dialogueSeesTheParentWorkspaceStateIncludingUncommittedFiles() throws Exception {
        // #1017：QA 對話只看到最新 commit，就答「不是沒有 commit」，漏掉它上一輪留下的 3 個未提交測試檔。
        FlowRun parent = new FlowRun(flow.snapshot); parent.workspaceReady = true;
        when(store.flow("41")).thenReturn(java.util.Optional.of(parent));
        when(workspace.exists("41")).thenReturn(true);
        when(workspace.headSha("41")).thenReturn("f2a7d9b");
        when(workspace.uncommittedPaths("41")).thenReturn(List.of("tests/a.test.js", "tests/b.test.js"));
        flow.dialogue = true; flow.parentId = "41";

        assertThat(executor.parentWorkspaceState(flow)).contains("系統查得").contains("最新 commit=f2a7d9b")
                .contains("有 2 個檔案改了但尚未 commit：tests/a.test.js、tests/b.test.js");

        when(workspace.uncommittedPaths("41")).thenReturn(List.of());
        assertThat(executor.parentWorkspaceState(flow)).contains("沒有未提交的修改");
    }
    @Test void dialogueRepliesMustBePlainAndEndWithWhatTheUserCanDo() {
        assertThat(ConfiguredExecutor.DIALOGUE_REPLY_RULES).contains("第一句直接回答問題").contains("用白話")
                .contains("不可只看最新 commit").contains("你可以怎麼做").contains("不確定就說不確定");
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
    @Test void missingExplicitLimitedDirectoriesArePreparedNotRejected() throws Exception {
        // #1015：只改文件的實作沒跑建置，build/ 不存在，驗收的操作預檢就判定「寫入超出沙箱政策」。
        Path empty = tmp.resolve("empty"); Files.createDirectories(empty.resolve(".git"));
        var policy = executor.policy(flow, flow.snapshot.definition().nodes().get("verification"), runtime, empty);
        assertThat(policy.writablePaths()).contains(empty.resolve("tests"), empty.resolve("dist")).doesNotContain(empty);
        assertThat(empty.resolve("dist")).isDirectory();
    }
    @Test void wildcardPolicyWithNoExistingDirectoryStillFailsClosed() throws Exception {
        Path empty = tmp.resolve("empty"); Files.createDirectory(empty);
        assertThatThrownBy(() -> PathRules.writableRoots(empty, List.of("**/src/test/**")))
                .hasMessageContaining("找不到任何");
        assertThat(empty.resolve("src")).doesNotExist();
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

    // ---- 提示中的指令權限說明必須和實際授予的一致 ----

    private TeamDefinition springDefinition() throws Exception {
        Path repo = tmp.resolve("spring-repo");
        Files.createDirectories(repo.resolve(".git"));
        return TeamCatalog.load(Path.of("teams/spring-starters.json"), key -> repo.toString()).definition();
    }

    @Test void grantedOperationsAreListedSoTheAgentDoesNotRefuseThem() throws Exception {
        var definition = springDefinition();
        AgentRuntime codex = mock(AgentRuntime.class);
        when(codex.permitsCommand(any(), any())).thenReturn(true);

        String text = ConfiguredExecutor.commandPatterns(definition.roles().get("tester"), codex,
                OperationContract.forNode(definition, "verification"));

        // #1016：只看到「只能 mvn／git／ls」，驗收 agent 就沒去執行已授予的文件隔離操作。
        assertThat(text).contains("qa-docs-dir").contains("mkdir -p target/qa-docs").contains("qa-docs-snapshot")
                .contains("不以樣式白名單限制").doesNotContain("只能執行下列樣式");
    }

    @Test void claudeRolesStillSeeTheirPatternsAndAnyOperationTheyCannotRun() throws Exception {
        var definition = springDefinition();
        var claude = new ClaudeCodeRuntime(null, null);
        var builder = definition.roles().get("builder");

        String text = ConfiguredExecutor.commandPatterns(builder, claude, Map.of(
                "docs-install", new TeamDefinition.Operation(List.of("implementation"), "doc-site", List.of("npm", "ci"), List.of()),
                "publish", new TeamDefinition.Operation(List.of("implementation"), ".", List.of("npm", "publish"), List.of())));

        assertThat(text).contains("只能執行下列樣式").contains("Bash(npm ci)");
        assertThat(text.substring(0, text.indexOf("執行不了"))).contains("docs-install").doesNotContain("publish");
        assertThat(text.substring(text.indexOf("執行不了"))).contains("publish");
    }

    @Test void claudeRolesAreToldTheAllowedWayToRepeatCommandsAndReadLongOutput() throws Exception {
        var definition = springDefinition();
        var claude = new ClaudeCodeRuntime(null, null);

        String text = ConfiguredExecutor.commandPatterns(definition.roles().get("builder"), claude, Map.of());

        // #1017：為了重跑不穩定測試用迴圈、> /tmp、Bash grep 讀長輸出檔，全部被拒到回合耗盡。
        assertThat(text).contains("分成多次工具呼叫").contains("工作目錄外的路徑")
                .contains("用 Read 或 Grep 工具讀那個檔案").contains("用 Read／Grep／Glob 工具");
    }

    @Test void alternativesOnlyMentionToolsTheRoleActuallyHas() {
        var role = new TeamDefinition.Role("Runner", "claude-code", "", "p.md", null, null,
                List.of("Bash(npm test)"), null);

        String text = ConfiguredExecutor.allowedAlternatives(role);

        assertThat(text).contains("分成多次工具呼叫").doesNotContain("Read").doesNotContain("Grep").doesNotContain("Glob");
    }

    /** spring-starters：builder 是 Claude Code，tester（計畫、驗收）是 Codex。 */
    private static AgentRuntime runtimeOf(String id) {
        return id.equals("claude-code") ? new ClaudeCodeRuntime(null, null) : new CodexRuntime(null, null);
    }

    @Test void claudeImplementationIsToldUpFrontWhichPathsItCanNeverWrite() throws Exception {
        String text = ConfiguredExecutor.protectedPathNotice(springDefinition(), "implementation", ConfiguredExecutorTest::runtimeOf);

        // #1021：試過才被拒，還填了 boundary_conflict，讓整份實作有被否決的風險。
        assertThat(text).contains(".claude/").contains(".mvn/").contains("maven-wrapper.properties")
                .contains("不要嘗試").contains("human_decision").contains("這不是 boundary_conflict");
    }

    @Test void delegatedCodexPartIsNotToldTheOriginalRolesLimits() throws Exception {
        // bestpartner #1021：分段改派的 Codex 拿到 Claude 的「.claude/ 一律拒寫、不要嘗試」，只做唯讀就回報受阻。
        assertThat(ConfiguredExecutor.protectedPathNotice(springDefinition(), "implementation", "tester",
                ConfiguredExecutorTest::runtimeOf)).isEmpty();
        assertThat(ConfiguredExecutor.protectedPathNotice(springDefinition(), "implementation", "builder",
                ConfiguredExecutorTest::runtimeOf)).contains("不要嘗試");
    }

    @Test void requirementPlanningAndTriageNodesLearnTheImplementersLimits() throws Exception {
        var definition = springDefinition();
        for (String node : List.of("intake", "design", "plan", "triage", "review")) {
            String text = ConfiguredExecutor.protectedPathNotice(definition, node, ConfiguredExecutorTest::runtimeOf);
            // #1021：驗收條件點名 .claude/rules，分流又提議開放權限後重派。
            assertThat(text).as(node).contains(".claude/").contains("不要把修改這些檔案列為實作的驗收")
                    .contains("不得提出「開放權限後重派」");
        }
    }

    @Test void codexWritersGetNoProtectedPathNotice() throws Exception {
        var definition = springDefinition();
        // 驗收節點會寫測試檔，但 runtime 是 Codex，沒有這類限制。
        assertThat(ConfiguredExecutor.protectedPathNotice(definition, "verification", ConfiguredExecutorTest::runtimeOf)).isEmpty();
        // 整個團隊都不用 Claude Code 時，唯讀節點也不該看到。
        assertThat(ConfiguredExecutor.protectedPathNotice(definition, "intake", id -> new CodexRuntime(null, null))).isEmpty();
        assertThat(ConfiguredExecutor.protectedPathNotice(definition, "approval", ConfiguredExecutorTest::runtimeOf)).isEmpty();
    }

    @Test void codexRolesDoNotGetPatternAdvice() throws Exception {
        var definition = springDefinition();
        AgentRuntime codex = mock(AgentRuntime.class);
        when(codex.permitsCommand(any(), any())).thenReturn(true);

        String text = ConfiguredExecutor.commandPatterns(definition.roles().get("tester"), codex, Map.of());

        assertThat(text).doesNotContain("分成多次工具呼叫");
    }
}
