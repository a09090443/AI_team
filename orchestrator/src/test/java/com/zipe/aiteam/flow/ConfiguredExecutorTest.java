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
    @Test void rulesProposalUsesFreshReadOnlySessionAndDoesNotReplaceCheckpoint() throws Exception {
        flow.node = "implementation";
        var captured = org.mockito.ArgumentCaptor.forClass(AgentRuntime.RuntimeRequest.class);
        var originals = List.of(new RulesProposal.Original(".claude/rules/guide.md", "original-hash", "old rules"));
        executor.proposeRules(new Task("42", "c", "t", "u", "更新 rules"), flow,
                flow.snapshot.definition().nodes().get("implementation"), originals);
        verify(runtime).execute(captured.capture(), any());
        var request = captured.getValue();
        assertThat(request.allowedTools()).containsExactly("Read", "Grep", "Glob");
        assertThat(request.resumeSessionId()).isNull();
        assertThat(request.sandboxPolicy().writablePaths()).doesNotContain(repo);
        assertThat(request.prompt()).contains("original-hash", "old rules", ".claude/rules/guide.md");
        assertThat(request.timeout()).isLessThanOrEqualTo(java.time.Duration.ofSeconds(300));
        verify(store, never()).saveCheckpoint(any());
    }
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
    @Test void completedParentCanAnswerFromEvidenceAfterSourceCleanup() throws Exception {
        var parent=new FlowRun(flow.snapshot);parent.workspaceReady=true;parent.workspacePath=repo.toString();
        when(store.flow("1048")).thenReturn(Optional.of(parent));
        var completed=new Task("1048","c","t","u","deploy");completed.state(com.zipe.aiteam.task.Task.State.COMPLETED);
        when(store.find("1048")).thenReturn(Optional.of(completed));when(props.homePath()).thenReturn(tmp);
        flow.dialogue=true;flow.parentId="1048";flow.context="saved deployment evidence";
        Path evidence=executor.workingDirectory(new Task("1049","c","t","u","網址無法打開"),flow,flow.snapshot.definition().nodes().get("triage"));
        assertThat(evidence).isEqualTo(tmp.resolve("dialogue-evidence/1049"));
        assertThat(Files.readString(evidence.resolve("deployment-evidence.txt"))).contains("不是產品repository","saved deployment evidence");
        assertThat(evidence.resolve(".git")).isDirectory();
    }
    @Test void serviceContextReachesTheRuntimePrompt() throws Exception {
        // 範本預設不啟用服務目標；這裡模擬實例配置填入 serviceTarget。
        var loaded = TeamCatalog.load(Path.of("teams/gradle-fullstack.json"), key -> repo.toString());
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(loaded.definition());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("project")).set("serviceTarget", new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"name\":\"example-dev\",\"checks\":{\"prepare\":\"service-prepare\",\"start\":\"service-start\",\"health\":\"service-health\"}}"));
        flow = new FlowRun(new TeamDefinition.Snapshot(loaded.revision(),
                new com.fasterxml.jackson.databind.ObjectMapper().treeToValue(tree, TeamDefinition.class), loaded.repository(), loaded.assets()));
        flow.node = "intake";
        when(store.serviceHistory("c", "example-dev", "42")).thenReturn(List.of(
                new TaskStore.ServiceHistory("1036", "2026-10-11", "{\"service_status\":\"RUNNING\",\"checks\":[]}")));
        AtomicReference<AgentRuntime.RuntimeRequest> captured = new AtomicReference<>();
        when(runtime.execute(any(), any())).thenAnswer(c -> { captured.set(c.getArgument(0)); return null; });
        executor.execute(new Task("42","c","t","u","部署"),flow,flow.snapshot.definition().nodes().get("intake"));
        assertThat(captured.get().prompt()).contains("服務部署歷史與恢復入口", "1036", "2026-10-11", "目前未核實");
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
    @Test void projectSkillsAreIndexedForEveryStageAndOnlyReviewMayPropose() throws Exception {
        Path taskRepo = tmp.resolve("task-repo"); Files.createDirectories(taskRepo.resolve(".git"));
        Files.createDirectories(taskRepo.resolve(".agents/skills/db-migration"));
        Files.writeString(taskRepo.resolve(".agents/skills/db-migration/SKILL.md"), "---\nname: db-migration\ndescription: 改資料表時使用\n---\n只在 skill 本文出現的句子");
        when(workspace.pathFor("42")).thenReturn(taskRepo); when(workspace.exists("42")).thenReturn(true);
        AtomicReference<AgentRuntime.RuntimeRequest> captured = new AtomicReference<>();
        when(runtime.execute(any(), any())).thenAnswer(c -> { captured.set(c.getArgument(0)); return null; });

        // 未設定 projectSkills 的配置：目錄裡有 skill 也不列索引、審查 schema 不變。
        flow.workspacePath = taskRepo.toString(); flow.node = "review";
        executor.execute(new Task("42", "c", "t", "u", "查核"), flow, flow.snapshot.definition().nodes().get("review"));
        assertThat(captured.get().prompt()).doesNotContain("專案 skill 清單", "skill_proposal");
        assertThat(captured.get().jsonSchema()).doesNotContain("skill_proposal");

        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(flow.snapshot.definition());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("project")).putObject("projectSkills")
                .put("directory", ".agents/skills").put("proposals", true).put("maxOpenProposals", 2);
        flow = new FlowRun(new TeamDefinition.Snapshot(flow.snapshot.revision(), json.treeToValue(tree, TeamDefinition.class),
                flow.snapshot.repository(), flow.snapshot.assets()));
        flow.workspacePath = taskRepo.toString();

        flow.node = "triage";
        executor.execute(new Task("42", "c", "t", "u", "查核"), flow, flow.snapshot.definition().nodes().get("triage"));
        assertThat(captured.get().prompt()).contains("專案 skill 清單", "db-migration — 改資料表時使用")
                .doesNotContain("只在 skill 本文出現的句子", "skill_proposal");
        assertThat(captured.get().jsonSchema()).doesNotContain("skill_proposal");

        flow.node = "review";
        executor.execute(new Task("42", "c", "t", "u", "查核"), flow, flow.snapshot.definition().nodes().get("review"));
        assertThat(captured.get().prompt()).contains("專案 skill 清單", "【專案 skill 提案：skill_proposal】");
        assertThat(captured.get().jsonSchema()).contains("skill_proposal");

        // skill 提案任務自己的審查不再提案。
        flow.skillProposal = new FlowRun.SkillProposalLink("41", "db-migration");
        executor.execute(new Task("42", "c", "t", "u", "查核"), flow, flow.snapshot.definition().nodes().get("review"));
        assertThat(captured.get().jsonSchema()).doesNotContain("skill_proposal");
        assertThat(captured.get().prompt()).contains("專案 skill 清單").doesNotContain("【專案 skill 提案");
    }
    @Test void focusedPromptDoesNotRepeatRulesTheRuntimeLoadsItself() throws Exception {
        Path taskRepo = tmp.resolve("task-repo"); Files.createDirectories(taskRepo.resolve(".git"));
        Files.writeString(taskRepo.resolve("AGENTS.md"), "只在規則檔出現的句子");
        when(workspace.pathFor("42")).thenReturn(taskRepo); when(workspace.exists("42")).thenReturn(true);
        flow.workspacePath = taskRepo.toString(); flow.node = "triage";
        AtomicReference<AgentRuntime.RuntimeRequest> captured = new AtomicReference<>();
        when(runtime.execute(any(), any())).thenAnswer(c -> { captured.set(c.getArgument(0)); return null; });
        var triage = flow.snapshot.definition().nodes().get("triage");
        // Claude Code 只讀 CLAUDE.md：沒有匯入就照舊附上，不讓角色失去專案規則。
        executor.execute(new Task("42", "c", "t", "u", "查核"), flow, triage);
        assertThat(captured.get().prompt()).contains("只在規則檔出現的句子");
        Files.writeString(taskRepo.resolve("CLAUDE.md"), "請參考 `@AGENTS.md` 的說明\n");
        assertThat(HandoffContext.rulesFileLoadedByRuntime(flow, runtime, taskRepo)).isFalse();

        Files.writeString(taskRepo.resolve("CLAUDE.md"), "# 說明\n\n@AGENTS.md\n\n## 其他\n");
        executor.execute(new Task("42", "c", "t", "u", "查核"), flow, triage);
        assertThat(captured.get().prompt()).doesNotContain("只在規則檔出現的句子").contains("已由執行環境自動載入");

        Files.delete(taskRepo.resolve("CLAUDE.md"));
        when(runtime.capabilities()).thenReturn(java.util.Set.of(AgentRuntime.Capability.NATIVE_AGENTS_MAP));
        assertThat(HandoffContext.rulesFileLoadedByRuntime(flow, runtime, taskRepo)).isTrue();
        flow.dialogue = true;
        assertThat(HandoffContext.rulesFileLoadedByRuntime(flow, runtime, taskRepo)).isFalse();
    }
    @Test void focusedStagesOnlyGetTheSystemNotesTheyUse() {
        var nodes = flow.snapshot.definition().nodes();
        for (String id : List.of("intake", "triage", "service", "service_triage"))
            assertThat(HandoffContext.needsServiceHistory(flow, nodes.get(id))).as(id).isTrue();
        for (String id : List.of("plan", "implementation", "verification", "review"))
            assertThat(HandoffContext.needsServiceHistory(flow, nodes.get(id))).as(id).isFalse();
        for (String id : List.of("plan", "verification", "review", "triage"))
            assertThat(HandoffContext.needsComparisonNotice(flow, nodes.get(id))).as(id).isTrue();
        for (String id : List.of("intake", "implementation", "service"))
            assertThat(HandoffContext.needsComparisonNotice(flow, nodes.get(id))).as(id).isFalse();
        // 直接對話不屬於精簡交接，維持原本的提示內容。
        flow.dialogue = true;
        assertThat(HandoffContext.needsServiceHistory(flow, nodes.get("implementation"))).isTrue();
        assertThat(HandoffContext.needsComparisonNotice(flow, nodes.get("implementation"))).isTrue();
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

    @Test void focusedReassignmentKeepsContractsAndFindingsButMovesOldReportsOutOfThePrompt() throws Exception {
        var loaded = TeamCatalog.load(Path.of("teams/gradle-fullstack.json"), key -> repo.toString());
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var definition = json.valueToTree(loaded.definition());
        // 範例的漂移檢查需由產品提供腳本與雜湊，交接測試不執行產品操作。
        ((com.fasterxml.jackson.databind.node.ObjectNode) definition.path("project").path("operations")).remove("drift-scan");
        flow = new FlowRun(new TeamDefinition.Snapshot(loaded.revision(), json.treeToValue(definition, TeamDefinition.class),
                loaded.repository(), loaded.assets()));
        flow.workspacePath = repo.toString();
        when(workspace.exists("42")).thenReturn(true);
        flow.receipts.put("intake", json.createObjectNode().put("summary", "requirement contract"));
        flow.receipts.put("design", json.createObjectNode().put("summary", "older design report"));
        flow.receipts.put("plan", json.createObjectNode().put("summary", "acceptance plan"));
        flow.receipts.put("triage", json.createObjectNode().put("summary", "triage report details"));
        flow.blockedFindings.put("review", json.createObjectNode().put("summary", "open review finding"));
        flow.context = FlowContext.append("", "使用者補充：保留目前的操作方式");
        flow.context = FlowContext.append(flow.context, "節點 review 受阻：\n系統核對未過\n報告：old duplicate report");
        flow.node = "triage";
        HandoffContext.recordTransition(flow, "implementation");
        flow.node = "implementation";
        flow.assignments.put("implementation", new AgentDispatch.Assignment("triage", "implementation",
                "builder", "修正審查發現", "補測試後重驗"));
        var request = org.mockito.ArgumentCaptor.forClass(AgentRuntime.RuntimeRequest.class);

        executor.execute(new Task("42", "c", "t", "u", "修正缺陷"), flow, flow.snapshot.definition().nodes().get(flow.node));

        verify(runtime).execute(request.capture(), any());
        String prompt = request.getValue().prompt();
        Path reference = request.getValue().systemPromptFile().resolveSibling("implementation-handoff-reports.json");
        assertThat(prompt).contains("補測試後重驗", "requirement contract", "acceptance plan", "open review finding",
                        "保留目前的操作方式", "系統核對未過", reference.toAbsolutePath().toString())
                .doesNotContain("older design report", "triage report details", "old duplicate report");
        assertThat(prompt.indexOf("補測試後重驗")).isLessThan(prompt.indexOf("requirement contract"));
        assertThat(Files.readString(reference)).contains("older design report", "triage report details", "open review finding");
        assertThat(reference.startsWith(repo)).isFalse();

        flow.receipts.keySet().removeAll(List.of("design", "triage"));
        flow.blockedFindings.clear();
        HandoffContext.appendReports(new StringBuilder(), flow, flow.snapshot.definition().nodes().get(flow.node), reference);
        assertThat(reference).doesNotExist();
    }

    @Test void focusedFixedRouteKeepsPreviousReportAndReviewDependencies() throws Exception {
        flow.node = "deploy";
        HandoffContext.recordTransition(flow, "review");
        flow.node = "review";
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        flow.receipts.put("implementation", json.createObjectNode().put("summary", "older backend details"));
        flow.receipts.put("frontend", json.createObjectNode().put("summary", "frontend implementation"));
        flow.receipts.put("verification", json.createObjectNode().put("summary", "verification results"));
        flow.receipts.put("deploy", json.createObjectNode().put("summary", "previous stage deployment"));

        StringBuilder prompt = new StringBuilder();
        HandoffContext.appendReports(prompt, flow, flow.snapshot.definition().nodes().get(flow.node), tmp.resolve("reports.json"));

        assertThat(prompt).contains("frontend implementation", "verification results", "previous stage deployment")
                .doesNotContain("older backend details");
        assertThat(Files.readString(tmp.resolve("reports.json"))).contains("older backend details");
    }

    @Test void failedSystemCheckTellsDownstreamItIsAHardGate() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        StringBuilder review = new StringBuilder();
        flow.blockedFindings.put("review", mapper.readTree("{\"verdict\":\"REQUEST_CHANGES\"}"));
        ConfiguredExecutor.appendBlockedFindings(review, flow);
        assertThat(review).contains("尚未解除的受阻發現").doesNotContain("硬閘門");

        // #1001：分流在這種狀態下提供了「接受測試無法執行」，負責人選了流程卻無路可走。
        StringBuilder check = new StringBuilder();
        flow.blockedFindings.put("verification", mapper.createObjectNode()
                .put("source", IndependentVerifier.FINDING_SOURCE).put("exit_code", 1).put("failure_summary", "2 tests failed"));
        ConfiguredExecutor.appendBlockedFindings(check, flow);
        assertThat(check).contains("硬閘門", "close", "successor", "also_needed", "接受無法核實");
    }

    @Test void knownBaselineFailuresReachEveryNode() throws Exception {
        Path taskRepo = tmp.resolve("task-repo"); Files.createDirectories(taskRepo.resolve(".git"));
        when(workspace.pathFor("42")).thenReturn(taskRepo); when(workspace.exists("42")).thenReturn(true);
        flow.workspacePath = taskRepo.toString(); flow.node = "triage";
        var baseline = mock(BaselineVerifier.class);
        when(baseline.notice(flow.snapshot.definition())).thenReturn("\n基準驗證結果（系統事實，非建議）：gradle test 程式或測試失敗");
        executor.baseline = baseline;
        AtomicReference<AgentRuntime.RuntimeRequest> captured = new AtomicReference<>();
        when(runtime.execute(any(), any())).thenAnswer(c -> { captured.set(c.getArgument(0)); return null; });

        executor.execute(new Task("42", "c", "t", "u", "查核"), flow, flow.snapshot.definition().nodes().get("triage"));

        assertThat(captured.get().prompt()).contains("基準驗證結果（系統事實", "gradle test");
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

    @Test void nodesThatCanChooseDeploymentOnlySeeUndeliveredCommits() throws Exception {
        // #1007：承接任務的 intake 不知道工作區帶著前任務的 commit，選了不開 PR 的只部署路線。
        var task = new Task("42", "c", "t", "u", "部署範例 API");
        var nodes = flow.snapshot.definition().nodes();
        flow.workspaceReady = true;
        when(workspace.exists("42")).thenReturn(true);
        when(workspace.commitsSinceBase("42")).thenReturn(List.of("9b3e4fe 新增範例"));
        assertThat(executor.pendingCommits(task, flow, nodes.get("intake"))).containsExactly("9b3e4fe 新增範例");
        assertThat(executor.pendingCommits(task, flow, nodes.get("implementation"))).isEmpty();
        when(workspace.commitsSinceBase("42")).thenThrow(new WorkspaceManager.WorkspaceException("no base"));
        assertThat(executor.pendingCommits(task, flow, nodes.get("intake"))).isEmpty();
    }

    @Test void intakeIsToldThatAllowedProtectedPathsAreDelegatedNotStopped() throws Exception {
        // #1014：需求整理一看到 .claude/ 就停下來問人；配置了改派時要說明那些路徑做得到。
        var definition = TeamCatalog.load(Path.of("teams/gradle-fullstack.json"), key -> repo.toString()).definition();
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
        var paths = TeamCatalog.load(Path.of("teams/gradle-fullstack.json"), key -> repo.toString()).definition()
                .project().verification().docOnlyPaths();
        assertThat(DocOnlyChanges.qualifies(paths, List.of("docs-site/docs/architecture/tech-stack.md"))).isTrue();
        assertThat(DocOnlyChanges.qualifies(paths, List.of("docs-site/blog/2026-10-04-release.md"))).isTrue();
        assertThat(DocOnlyChanges.qualifies(paths, List.of("docs-site/docusaurus.config.ts"))).isFalse();
        assertThat(DocOnlyChanges.qualifies(paths, List.of("docs-site/src/components/Home.tsx"))).isFalse();
        assertThat(DocOnlyChanges.implementationNotice(paths))
                .contains("docs-site/docs/**", "不要執行後端／前端的編譯與測試操作", "不要因為沒有建置或測試證據而回報 BLOCKED", "照建置規範完整執行")
                // #1004：照規則略過建置後填 false 被擋，分流又要求補跑完整建置。
                .contains("整個任務分支", "verify_passed", "不因沒有執行產品建置而填 false");
        assertThat(DocOnlyChanges.implementationNotice(null)).isEmpty();
        assertThat(DocOnlyChanges.implementationNotice(List.of())).isEmpty();
    }
    @Test void mavenLibraryTreatsAgentRuleMarkdownAsDocsButNotTheirSettings() throws Exception {
        // #1004：.claude/rules 的 md 不算純文件，一行版本修正也跑了整套 mvn clean verify 並撞上不穩定測試。
        var paths = TeamCatalog.load(Path.of("teams/maven-library.json"), key -> repo.toString()).definition()
                .project().verification().docOnlyPaths();
        assertThat(DocOnlyChanges.qualifies(paths, List.of("AGENTS.md", ".claude/rules/project-overview.md", ".agents/skills/x/SKILL.md"))).isTrue();
        assertThat(DocOnlyChanges.qualifies(paths, List.of(".claude/settings.json"))).isFalse();
        assertThat(DocOnlyChanges.qualifies(paths, List.of(".claude/rules/a.md", "base-spring-boot-starter/src/test/java/A.java"))).isFalse();
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
    private AgentRuntime.RuntimeRequest resumeIntakeAfterIdle(long idleMinutes) throws Exception {
        flow.node = "intake";
        var node = flow.snapshot.definition().nodes().get("intake");
        var role = flow.snapshot.definition().roles().get(node.role());
        when(workspace.refreshOrigin(any(), any()))
                .thenReturn(new WorkspaceManager.Freshness(true, java.time.Instant.parse("2026-11-07T00:00:00Z")));
        Path transcript = Files.writeString(tmp.resolve("old-session.jsonl"), "{\"type\":\"assistant\"}\n");
        Files.setLastModifiedTime(transcript, java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minus(java.time.Duration.ofMinutes(idleMinutes))));
        when(runtime.sessionTranscript(any(), eq("old-session"))).thenReturn(java.util.Optional.of(transcript));
        when(props.resumeIdleMinutes()).thenReturn(60);
        var task = new Task("42", "c", "t", "u", "查核");
        Path cwd = repo.toAbsolutePath().normalize();
        task.continuation(new com.zipe.aiteam.task.Checkpoint("42", "intake", role.runtime(), java.util.Objects.toString(role.model(), ""),
                "old-session", "\n工作目錄：" + cwd + "\n\n工作目錄：" + repo + "\n", null, null));
        AtomicReference<AgentRuntime.RuntimeRequest> captured = new AtomicReference<>();
        when(runtime.execute(any(), any())).thenAnswer(c -> { captured.set(c.getArgument(0)); return null; });
        executor.execute(task, flow, node);
        return captured.get();
    }
    @Test void longIdleSessionStartsFreshWithPreviousTranscriptAvailable() throws Exception {
        var request = resumeIntakeAfterIdle(130);
        assertThat(request.resumeSessionId()).isNull();
        assertThat(request.prompt()).contains("長時間中斷後改開新 session", ".ai-team-context/previous-session-old-session.jsonl");
        assertThat(repo.resolve(".ai-team-context/previous-session-old-session.jsonl")).exists();
        assertThat(Files.readString(repo.resolve(".git/info/exclude"))).contains("/.ai-team-context/");
    }
    @Test void recentSessionIsStillResumed() throws Exception {
        var request = resumeIntakeAfterIdle(20);
        assertThat(request.resumeSessionId()).isEqualTo("old-session");
        assertThat(request.prompt()).doesNotContain("長時間中斷後改開新 session");
        assertThat(repo.resolve(".ai-team-context")).doesNotExist();
    }
    @Test void qaUsesSnapshotModelAndReasoningEffort() throws Exception {
        flow = new FlowRun(TeamCatalog.load(Path.of("teams/maven-library.json"), key -> repo.toString()));
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
    @Test void readOnlyNodeInTheMainRepositoryFetchesFirstAndToldHowFreshTheRemoteIs() throws Exception {
        // #1056：intake／design 讀到舊的 origin/dev，猜錯衝突檔。主 repo 執行前先更新，並告訴 agent 記錄的時間。
        flow.node = "intake";
        AtomicReference<AgentRuntime.RuntimeRequest> captured = new AtomicReference<>();
        when(runtime.execute(any(), any())).thenAnswer(c -> { captured.set(c.getArgument(0)); return null; });
        String base = flow.snapshot.definition().project().baseBranch();
        when(workspace.refreshOrigin(repo.toAbsolutePath().normalize(), base))
                .thenReturn(new WorkspaceManager.Freshness(true, java.time.Instant.parse("2026-10-23T14:06:00Z")));
        executor.execute(new Task("42", "c", "t", "u", "修正 PR 衝突"), flow, flow.snapshot.definition().nodes().get("intake"));
        assertThat(captured.get().prompt()).contains("主 repo 遠端記錄（origin/*）已於 2026-10-23 22:06:00（台北時間） 更新")
                // #1003：本機 HEAD 落後時，agent 不得把它當成目前版本。
                .contains("以 origin/" + base + " 為準，不要用 HEAD");

        // 主 repo 已快轉到基準：工作樹就是最新版本，可直接讀檔。
        when(workspace.refreshOrigin(repo.toAbsolutePath().normalize(), base))
                .thenReturn(new WorkspaceManager.Freshness(true, java.time.Instant.parse("2026-10-23T14:06:00Z"), "4e2d0764d1aa0000000000000000000000000000"));
        executor.execute(new Task("42", "c", "t", "u", "做 API"), flow, flow.snapshot.definition().nodes().get("intake"));
        assertThat(captured.get().prompt()).contains("主 repo 工作樹已同步到 origin/" + base + "（4e2d0764d1aa）")
                .doesNotContain("不要用 HEAD");

        when(workspace.refreshOrigin(any(), any())).thenReturn(new WorkspaceManager.Freshness(false, null));
        executor.execute(new Task("42", "c", "t", "u", "修正 PR 衝突"), flow, flow.snapshot.definition().nodes().get("intake"));
        assertThat(captured.get().prompt()).contains("更新失敗").contains("無法確認最新狀態");
    }
    @Test void workspaceNodesDoNotFetchTheMainRepository() throws Exception {
        Path ws = tmp.resolve("ws"); Files.createDirectories(ws.resolve(".git"));
        flow.node = "implementation"; flow.workspaceReady = true;
        when(workspace.pathFor("42")).thenReturn(ws);
        when(runtime.execute(any(), any())).thenReturn(null);
        executor.execute(new Task("42", "c", "t", "u", "做登入"), flow, flow.snapshot.definition().nodes().get("implementation"));
        verify(workspace, never()).refreshOrigin(any(), any());
    }
    @Test void consultAnswersReadOnlyWithoutTouchingTheAskersCheckpoint() throws Exception {
        flow.snapshot = ConsultationTest.collaborative(flow.snapshot, 1, 4);
        flow.node = "plan"; flow.visits.put("plan", 1);
        var plan = flow.snapshot.definition().nodes().get("plan");
        var request = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("summary", "計畫草稿：登入情境");
        request.putArray("consult").addObject().put("role", "builder").put("question", "登入 API 回傳哪些錯誤碼？").put("why", "反例要用");
        var pending = Consultation.begin(flow, plan, Consultation.parse(flow, plan, request), request);
        AtomicReference<AgentRuntime.RuntimeRequest> captured = new AtomicReference<>();
        when(runtime.execute(any(), any())).thenAnswer(c -> { captured.set(c.getArgument(0)); return null; });

        executor.consult(new Task("42", "c", "t", "u", "做登入"), flow, pending, 0);

        var sent = captured.get();
        assertThat(sent.role()).isEqualTo("plan-consult-1-1");
        assertThat(sent.allowedTools()).containsExactly("Read", "Grep", "Glob");
        assertThat(sent.sandboxPolicy().writablePaths()).doesNotContain(repo);
        assertThat(sent.resumeSessionId()).isNull();
        assertThat(sent.jsonSchema()).contains("\"answer\"", "\"confidence\"");
        assertThat(sent.prompt()).contains("登入 API 回傳哪些錯誤碼？", "計畫草稿：登入情境", "團隊共享看板");
        assertThat(Files.readString(sent.systemPromptFile())).contains("唯讀", "另一位團隊角色", "台灣口語的繁體中文", "不得出現任何簡體字");
        verify(store, never()).saveCheckpoint(any());
    }
    @Test void collaborativeSnapshotAddsBoardConsultAndAnswersToTheStage() throws Exception {
        flow.snapshot = ConsultationTest.collaborative(flow.snapshot, 1, 4);
        flow.node = "plan"; flow.visits.put("plan", 1);
        var plan = flow.snapshot.definition().nodes().get("plan");
        var request = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("summary", "d");
        request.putArray("consult").addObject().put("role", "builder").put("question", "分頁大小？").put("why", "w");
        var pending = Consultation.begin(flow, plan, Consultation.parse(flow, plan, request), request);
        Consultation.record(flow, pending, 0, new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("answer", "預設 20 筆").put("confidence", "MEDIUM").put("evidence", ""));
        AtomicReference<AgentRuntime.RuntimeRequest> captured = new AtomicReference<>();
        when(runtime.execute(any(), any())).thenAnswer(c -> { captured.set(c.getArgument(0)); return null; });

        executor.execute(new Task("42", "c", "t", "u", "做登入"), flow, plan);

        assertThat(captured.get().prompt()).contains("團隊共享看板", "諮詢問答", "預設 20 筆");
        assertThat(captured.get().jsonSchema()).contains("\"board_updates\"");
        // 本次執行的一輪已用完（consultPerVisit=1）：不再開放 consult 欄位。
        assertThat(captured.get().jsonSchema()).doesNotContain("\"consult\"");
    }
    @Test void legacySnapshotStageHasNoCollaborationText() throws Exception {
        flow.node = "plan";
        AtomicReference<AgentRuntime.RuntimeRequest> captured = new AtomicReference<>();
        when(runtime.execute(any(), any())).thenAnswer(c -> { captured.set(c.getArgument(0)); return null; });
        executor.execute(new Task("42", "c", "t", "u", "做登入"), flow, flow.snapshot.definition().nodes().get("plan"));
        assertThat(captured.get().prompt()).doesNotContain("團隊共享看板", "角色諮詢", "諮詢問答");
        assertThat(captured.get().jsonSchema()).doesNotContain("board_updates", "\"consult\"");
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
    @Test void closedAndUnknownParentNeverReceiveExecutionCommand() {
        for(var state:List.of(Task.State.COMPLETED,Task.State.PR_CREATED,Task.State.STAGE_RUNNING,Task.State.AWAIT_HUMAN))
            assertThat(ConfiguredExecutor.dialogueInstructions("QA",state)).doesNotContain("執行 <要做的事>","@QA 執行");
        assertThat(ConfiguredExecutor.dialogueInstructions("QA",Task.State.PR_CREATED)).contains("原任務已結案");
        assertThat(ConfiguredExecutor.dialogueInstructions("QA",null)).contains("沒有原任務");
    }
    @Test void dialogueFactsDescribeStateWithoutGrantingProductOperations() {
        var parent=new Task("41","c","t","u","done");parent.state(Task.State.COMPLETED);
        when(store.find("41")).thenReturn(Optional.of(parent));
        flow.dialogue=true;flow.parentId="41";
        var facts=executor.executionFacts(new Task("42","c","t","u","status"),flow,flow.snapshot.definition().nodes().get("triage"));
        assertThat(facts).contains("COMPLETED","ANSWER_FROM_EVIDENCE","\"can_resume_parent\":false","\"can_modify_product\":false")
                .doesNotContain("\"operations\"");
    }
    @Test void dialogueRepliesMustBePlainAndEndWithWhatTheUserCanDo() {
        assertThat(ConfiguredExecutor.DIALOGUE_REPLY_RULES).contains("第一句直接回答問題").contains("用白話")
                .contains("不可只看最新 commit").contains("你可以怎麼做").contains("不確定就說不確定")
                .contains("Slack").contains("```");
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
        // 角色背景是 UI/UX prompt，實作規則由節點 instructions 帶入（2026-11-08）。
        assertThat(Files.readString(request.systemPromptFile())).isEqualTo(flow.snapshot.assets().get("prompts/frontend-uiux.md")
                + "\n\n【團隊共用工作規則】\n" + flow.snapshot.assets().get("prompts/shared.md")
                + "\n\n【本次階段指示，優先於角色的一般工作說明】\n" + flow.snapshot.assets().get("prompts/implement.md")
                + "\n\n" + flow.snapshot.assets().get(IssueTracker.CONTRACT) + com.zipe.aiteam.runtime.OutputLanguage.RULE);
        verify(store).saveCheckpoint(argThat(cp -> cp.role().equals("frontend") && cp.runtime().equals("claude-code")));
    }
    @Test void limitedNodeCannotWriteProductOrWholeWorkspace() throws Exception {
        var policy = executor.policy(flow, flow.snapshot.definition().nodes().get("verification"), runtime, repo);
        assertThat(policy.writablePaths()).contains(repo.resolve("tests"), repo.resolve(".git"))
                .doesNotContain(repo, repo.resolve("src"));
        assertThat(policy.readOnlyPins()).contains(repo.resolve("src/main.ts"), repo.resolve(".git/config"));
    }
    @Test void dependencyTreesAreNotPinnedAsProductFiles() throws Exception {
        // #1029：node_modules 裡上千個 package.json 被釘選，Codex 參數超過 Linux 單一參數上限而 E2BIG。
        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws.resolve("ui/node_modules/lodash/node_modules/x"));
        Files.writeString(ws.resolve("ui/package.json"), "{}");
        Files.writeString(ws.resolve("ui/node_modules/lodash/package.json"), "{}");
        Files.writeString(ws.resolve("ui/node_modules/lodash/node_modules/x/package.json"), "{}");
        assertThat(ConfiguredExecutor.protectedPins(ws, List.of("**/package.json"))).containsExactly(ws.resolve("ui/package.json"));
    }
    @Test void untrackedOperationOutputsAreNotPinned() throws Exception {
        // #1044：QA 副本 target/qa-docs 裡的 package.json 被釘成唯讀，下一輪清理得到 Device or resource busy。
        Path ws = tmp.resolve("ws-out");
        Files.createDirectories(ws.resolve("target/qa-docs/docs-site"));
        Files.createDirectories(ws.resolve("docs-site"));
        Files.writeString(ws.resolve("docs-site/package.json"), "{}");
        Files.writeString(ws.resolve("target/qa-docs/docs-site/package.json"), "{}");
        assertThat(ConfiguredExecutor.protectedPins(ws, List.of("**/package.json"), Set.of("target/qa-docs")))
                .containsExactly(ws.resolve("docs-site/package.json"));
        // 只略過完全相同的輸出目錄，不擴及父目錄或名稱相近的目錄。
        assertThat(ConfiguredExecutor.protectedPins(ws, List.of("**/package.json"), Set.of("target/qa")))
                .contains(ws.resolve("target/qa-docs/docs-site/package.json"));
    }
    @Test void limitedPolicySkipsOnlyOutputsTheWorkspaceReportsAsUntracked() throws Exception {
        var verification = flow.snapshot.definition().nodes().get("verification");
        when(workspace.untrackedOutputDirs(eq(repo), anyCollection())).thenReturn(List.of("src"));
        // 系統只略過工作區回報為「沒有追蹤檔」的目錄；這裡故意回報 src 以確認略過確實生效。
        assertThat(executor.policy(flow, verification, runtime, repo).readOnlyPins()).doesNotContain(repo.resolve("src/main.ts"));
        verify(workspace).untrackedOutputDirs(repo, Set.of("node_modules", "dist", "coverage", "ai-team-sandbox/.agent-service"));
        when(workspace.untrackedOutputDirs(eq(repo), anyCollection())).thenReturn(List.of());
        assertThat(executor.policy(flow, verification, runtime, repo).readOnlyPins()).contains(repo.resolve("src/main.ts"));
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
        return TeamCatalog.load(Path.of("teams/maven-library.json"), key -> repo.toString()).definition();
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

    /** #1055：Codex 的 network proxy 會擋 localhost 的 HTTP；本機測試網路節點的固定操作要帶 NO_PROXY 前綴。 */
    @Test void localTestNetworkOperationsCarryTheLoopbackNoProxyPrefix() throws Exception {
        var definition = springDefinition();
        AgentRuntime codex = mock(AgentRuntime.class);
        when(codex.permitsCommand(any(), any())).thenReturn(true);
        var operations = OperationContract.forNode(definition, "verification");
        String local = ConfiguredExecutor.commandPatterns(definition.roles().get("tester"), codex, operations, true);
        assertThat(local).contains("env NO_PROXY=localhost,127.0.0.1,::1 no_proxy=localhost,127.0.0.1,::1 mkdir -p target/qa-docs")
                .contains("不要拿掉");
        assertThat(ConfiguredExecutor.commandPatterns(definition.roles().get("tester"), codex, operations, false)).doesNotContain("NO_PROXY");
    }

    /** #1055：系統已在全新 clone 通過的指令列給 QA，請它引用收據、不要在沙箱重跑。 */
    @Test void verificationIsToldWhichCommandsTheSystemAlreadyPassed() throws Exception {
        var flow = new FlowRun(TeamCatalog.load(Path.of("teams/maven-library.json"), key -> repo.toString()));
        var decisions = List.of(
                new VerificationPlan.Decision(0, List.of("npm", "ci"), VerificationPlan.Action.RUN, "", List.of(), "f0", "l0", "s0", null),
                new VerificationPlan.Decision(1, List.of("npm", "test"), VerificationPlan.Action.REUSE, "", List.of(), "f1", "l1", "s1", "h0"),
                new VerificationPlan.Decision(2, List.of("gradle", "test"), VerificationPlan.Action.SKIP, "未涉及", List.of(), "f2", null, null, null));
        flow.independentEvidence = new IndependentVerifier.Receipt("h1", "b", "c", "s", "e", List.of(), null, null, decisions, List.of(), "42");
        var verification = flow.snapshot.definition().nodes().values().stream()
                .filter(n -> n.gate() == TeamDefinition.Gate.VERIFICATION).findFirst().orElseThrow();
        String notice = ConfiguredExecutor.receiptReuseNotice(flow, verification);
        assertThat(notice).contains("commit h1").contains("・npm ci").contains("・npm test").doesNotContain("gradle test").contains("不要在沙箱重跑");
        var review = flow.snapshot.definition().nodes().values().stream()
                .filter(n -> n.gate() == TeamDefinition.Gate.REVIEW).findFirst().orElseThrow();
        assertThat(ConfiguredExecutor.receiptReuseNotice(flow, review)).isEmpty();
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

    /** maven-library：builder 是 Claude Code，tester（計畫、驗收）是 Codex。 */
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
        // #1021：分段改派的 Codex 拿到 Claude 的「.claude/ 一律拒寫、不要嘗試」，只做唯讀就回報受阻。
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
