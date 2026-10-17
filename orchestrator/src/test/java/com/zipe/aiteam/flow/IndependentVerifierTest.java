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
    void comparisonPlan() throws Exception {
        String planId = flow.snapshot.definition().nodes().entrySet().stream()
                .filter(e -> e.getValue().gate() == TeamDefinition.Gate.TEST_PLAN).findFirst().orElseThrow().getKey();
        flow.receipts.put(planId, new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"scenarios\":[{\"operation_ids\":[\"compare-unchanged-files\"],\"unchanged_paths\":[\"README.md\"]}]}"));
        flow.comparisonBase = "b".repeat(40);
        when(workspace.compareFiles("42", flow.comparisonBase, head, List.of("README.md")))
                .thenReturn(List.of(new WorkspaceManager.FileIdentity("README.md", "100644 blob abc", "100644 blob abc")));
    }
    @Test void comparisonIsSystemEvidenceBoundToPlanBaseAndPersistedLog() throws Exception {
        comparisonPlan(); verifyNow();
        assertThat(verifier.blockingReason(flow, head)).isNull();
        assertThat(flow.independentEvidence.fileComparison().files()).hasSize(1);
        flow.comparisonBase = "c".repeat(40);
        assertThat(verifier.blockingReason(flow, head)).contains("檔案比對收據");
        flow.comparisonBase = "b".repeat(40);
        Files.writeString(Path.of(flow.independentEvidence.fileComparison().log()), "[]");
        assertThat(verifier.blockingReason(flow, head)).contains("紀錄遺失或內容不符");
    }
    @Test void changedOldFileFailsBeforeBuildAndCannotUseOldReceipt() throws Exception {
        verifyNow(); comparisonPlan();
        assertThat(verifier.blockingReason(flow, head)).contains("檔案比對收據");
        calls.clear();
        when(workspace.compareFiles("42", flow.comparisonBase, head, List.of("README.md")))
                .thenReturn(List.of(new WorkspaceManager.FileIdentity("README.md", "100644 blob abc", "")));
        assertThatThrownBy(this::verifyNow).isInstanceOf(IndependentVerifier.CheckFailed.class);
        assertThat(calls).isEmpty();
    }
    @Test void comparisonReceiptCannotSurviveChangedPlanAndRoundTripsWithOldReceipts() throws Exception {
        comparisonPlan(); verifyNow();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var receipt = flow.independentEvidence;
        assertThat(mapper.readValue(mapper.writeValueAsString(receipt), IndependentVerifier.Receipt.class)).isEqualTo(receipt);
        var old = mapper.valueToTree(receipt);
        ((com.fasterxml.jackson.databind.node.ObjectNode) old).remove("fileComparison");
        assertThat(mapper.treeToValue(old, IndependentVerifier.Receipt.class).fileComparison()).isNull();
        flow.receipts.values().forEach(p -> ((com.fasterxml.jackson.databind.node.ObjectNode)p).put("summary", "changed plan"));
        assertThat(verifier.blockingReason(flow, head)).contains("檔案比對收據");
    }
    @Test void documentOnlyShortcutStillRequiresSystemFileComparison() throws Exception {
        enableDocOnly("*.md"); comparisonPlan();
        when(workspace.changedPathsSinceBase("42")).thenReturn(List.of("other.md"));
        verifyNow();
        assertThat(flow.independentEvidence.docOnly()).isTrue();
        assertThat(flow.independentEvidence.fileComparison()).isNotNull();
        assertThat(verifier.blockingReason(flow, head)).isNull();
        assertThat(calls).isEmpty();
        when(workspace.compareFiles("42", flow.comparisonBase, head, List.of("README.md")))
                .thenReturn(List.of(new WorkspaceManager.FileIdentity("README.md", "100644 blob abc", "100644 blob def")));
        assertThatThrownBy(this::verifyNow).isInstanceOf(IndependentVerifier.CheckFailed.class);
    }
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
    @Test void configuredCommandsShareTheDedicatedDependencyCacheButCloneDoesNot() throws Exception {
        // #1044：空的 HOME 讓 Gradle 每輪重下載，四次驗證都貼著 1800 秒上限。
        verifyNow();
        Path cache = tmp.resolve("app/verification-cache").toAbsolutePath().normalize();
        assertThat(cache).isDirectory();
        assertThat(policies.get(0).writablePaths()).doesNotContain(cache);
        assertThat(policies.subList(2, 5)).allMatch(p -> p.writablePaths().contains(cache));
        assertThat(IndependentVerifier.cacheEnvironment(cache, policies.get(0))).isEmpty();
        // 反斜線不進環境變數（Windows 路徑只出現在本機測試；驗證只在 Linux 執行）。
        Assumptions.assumeFalse(cache.toString().contains("\\"));
        assertThat(IndependentVerifier.cacheEnvironment(cache, policies.get(2)))
                .containsExactly("GRADLE_USER_HOME=" + cache.resolve("gradle"), "npm_config_cache=" + cache.resolve("npm"),
                        "MAVEN_OPTS=-Dmaven.repo.local=" + cache.resolve("m2/repository"));
        assertThat(IndependentVerifier.cacheEnvironment(Path.of("/a b/cache"),
                new SandboxPolicy(List.of(Path.of("/a b/cache")), List.of(), 1, 1, 1))).isEmpty();
    }
    @Test void exitAtTheDeadlineIsATimeoutNotATestFailure() {
        long deadline = 100_000_000_000L;
        assertThat(IndependentVerifier.timedOut(deadline, deadline - IndependentVerifier.TIMEOUT_MARGIN_NANOS + 1)).isTrue();
        assertThat(IndependentVerifier.timedOut(deadline, deadline + 5)).isTrue();
        assertThat(IndependentVerifier.timedOut(deadline, deadline - IndependentVerifier.TIMEOUT_MARGIN_NANOS - 1)).isFalse();
        var timeout = new IndependentVerifier.VerificationTimeout(List.of("npm", "test"), tmp.resolve("check-4.log"));
        assertThat(timeout).isNotInstanceOf(IndependentVerifier.CheckFailed.class);
        assertThat(timeout.getMessage()).contains("時間上限", "不是測試失敗", "[npm, test]");
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
        assertThat(verifier.blockingReason(flow, head)).contains("紀錄遺失");
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

    // ---- 純文件變更：依系統查得的變更略過建置測試（#1012） ----

    void enableDocOnly(String... patterns) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(flow.snapshot.definition());
        var array = ((com.fasterxml.jackson.databind.node.ObjectNode) json.path("project").path("verification")).putArray("docOnlyPaths");
        for (String p : patterns) array.add(p);
        flow.snapshot = new TeamDefinition.Snapshot(flow.snapshot.revision(), mapper.treeToValue(json, TeamDefinition.class),
                flow.snapshot.repository(), flow.snapshot.assets());
    }

    @Test void documentationOnlyChangeSkipsBuildCommandsWithAVerifiableReceipt() throws Exception {
        enableDocOnly("*.md", "docs/**", ".agents/**");
        when(workspace.changedPathsSinceBase("42")).thenReturn(List.of(".agents/skills/test-confirmation/SKILL.md", "docs/rag/General rule.md"));
        verifyNow();
        assertThat(calls).isEmpty();
        assertThat(flow.independentEvidence.docOnly()).isTrue();
        assertThat(flow.independentEvidence.commands()).isEmpty();
        assertThat(flow.independentEvidence.docOnlyChanges()).contains(".agents/skills/test-confirmation/SKILL.md");
        assertThat(verifier.blockingReason(flow, head)).isNull();
        assertThat(PrReport.independent(flow.independentEvidence)).contains("純文件變更");
    }

    @Test void anyNonDocumentOrBuildFileRunsEverything() throws Exception {
        enableDocOnly("*.md", "docs/**", "**/*.md");
        for (var changed : List.of(
                List.of("README.md", "src/main/java/App.java"),          // 混有程式檔
                List.of("docs/guide.md", "pom.xml"),                       // 建置檔
                List.of("docs/init.sql"),                                 // docs 路徑不是純文字證明
                List.of("docs/install.sh"),
                List.of("service/src/main/resources/prompt.md"),           // 符合 **/*.md 但在 src 底下：強制全跑
                List.of("docs/App.java", "src/main/java/App.java"),        // 程式檔改名搬進 docs（--no-renames 列出兩端）
                List.of(".github/workflows/ci.md"),                        // CI 目錄
                List.<String>of())) {                                      // 沒有變更
            calls.clear(); flow.verificationHistory.clear(); // 同一個 commit 會重用上一輪結果；這裡要驗的是「需要跑」的判定
            when(workspace.changedPathsSinceBase("42")).thenReturn(changed);
            verifyNow();
            assertThat(flow.independentEvidence.docOnly()).as(changed.toString()).isFalse();
            assertThat(calls).as(changed.toString()).hasSize(5);
        }
    }

    @Test void withoutDocOnlyPathsEveryChangeRunsEverything() throws Exception {
        enableDocOnly();
        when(workspace.changedPathsSinceBase("42")).thenReturn(List.of("README.md"));
        verifyNow();
        assertThat(flow.independentEvidence.docOnly()).isFalse();
        assertThat(calls).hasSize(5);
    }

    @Test void forgedDocumentReceiptIsRejected() throws Exception {
        enableDocOnly("*.md", "docs/**");
        when(workspace.changedPathsSinceBase("42")).thenReturn(List.of("README.md"));
        verifyNow();
        var r = flow.independentEvidence;
        flow.independentEvidence = new IndependentVerifier.Receipt(r.head(), r.baseline(), r.configuration(), r.startedAt(),
                r.completedAt(), List.of(), List.of("README.md", "src/main/java/App.java"));
        assertThat(verifier.blockingReason(flow, head)).contains("純文件收據");
        flow.independentEvidence = new IndependentVerifier.Receipt(r.head(), r.baseline(), r.configuration(), r.startedAt(),
                r.completedAt(), List.of(), List.of());
        assertThat(verifier.blockingReason(flow, head)).contains("純文件收據");
    }

    // ---- 依變更檔案只跑相關指令、重用相同輸入的結果（#1044：只改文件也每輪跑 23～30 分鐘） ----

    /** node-web 的三個指令：npm ci、npm test 屬 web/**，npm run build 屬 api/**；site/**、.env.example 由 QA 驗證。 */
    void enableScopes() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(flow.snapshot.definition());
        var v = (com.fasterxml.jackson.databind.node.ObjectNode) json.path("project").path("verification");
        v.putArray("docOnlyPaths").add("*.md");
        var scopes = v.putArray("commandScopes");
        scopes.addArray().add("web/**"); scopes.addArray().add("web/**"); scopes.addArray().add("api/**");
        v.putArray("qaOnlyPaths").add("site/**").add(".env.example");
        var definition = mapper.treeToValue(json, TeamDefinition.class); TeamCatalog.validate(definition);
        flow.snapshot = new TeamDefinition.Snapshot(flow.snapshot.revision(), definition, flow.snapshot.repository(), flow.snapshot.assets());
        tree(List.of("100644 a1\tweb/src/a.ts", "100644 b1\tapi/App.java", "100644 c1\tsite/sidebars.ts", "100644 d1\tREADME.md"));
    }
    void tree(List<String> entries) throws Exception { when(workspace.treeEntries("42", head)).thenReturn(entries); }
    long commandCalls() { return calls.stream().filter(c -> !c.contains("clone") && !c.contains("checkout")).count(); }

    @Test void documentationSiteAndTemplateChangesSkipEveryCommandWithoutCloning() throws Exception {
        enableScopes();
        when(workspace.changedPathsSinceBase("42")).thenReturn(List.of(".env.example", "site/sidebars.ts", "site/package.json", "README.md"));
        verifyNow();
        assertThat(calls).isEmpty();
        assertThat(flow.independentEvidence.decisions()).extracting(VerificationPlan.Decision::action)
                .containsOnly(VerificationPlan.Action.SKIP);
        assertThat(verifier.blockingReason(flow, head)).isNull();
        assertThat(PrReport.independent(flow.independentEvidence)).contains("略過 3", "⏭️ 略過");
        // 之後才出現 web 變更：略過的指令重算後需要跑，收據作廢。
        when(workspace.changedPathsSinceBase("42")).thenReturn(List.of(".env.example", "web/src/a.ts"));
        assertThat(verifier.blockingReason(flow, head)).contains("略過的指令重算後需要執行");
    }
    @Test void onlyCommandsWhoseScopeChangedRun() throws Exception {
        enableScopes();
        when(workspace.changedPathsSinceBase("42")).thenReturn(List.of("web/src/a.ts", "site/docs/x.md"));
        verifyNow();
        assertThat(commandCalls()).isEqualTo(2);
        assertThat(flow.independentEvidence.decisions()).extracting(VerificationPlan.Decision::action)
                .containsExactly(VerificationPlan.Action.RUN, VerificationPlan.Action.RUN, VerificationPlan.Action.SKIP);
        assertThat(flow.independentEvidence.commands()).hasSize(2);
        assertThat(verifier.blockingReason(flow, head)).isNull();
        // 範圍內容改變（指紋不同）：收據失效。
        tree(List.of("100644 a2\tweb/src/a.ts", "100644 b1\tapi/App.java", "100644 c1\tsite/sidebars.ts", "100644 d1\tREADME.md"));
        assertThat(verifier.blockingReason(flow, head)).contains("輸入指紋已失效");
    }
    @Test void fileOutsideEveryScopeRunsEverything() throws Exception {
        enableScopes();
        for (var changed : List.of(List.of("scripts/deploy.sh"), List.of("package.json"), List.<String>of())) {
            calls.clear(); flow.verificationHistory.clear();
            when(workspace.changedPathsSinceBase("42")).thenReturn(changed);
            verifyNow();
            assertThat(commandCalls()).as(changed.toString()).isEqualTo(3);
        }
        calls.clear(); flow.verificationHistory.clear();
        when(workspace.changedPathsSinceBase("42")).thenThrow(new WorkspaceManager.WorkspaceException("no base"));
        verifyNow();
        assertThat(commandCalls()).isEqualTo(3);
        assertThat(flow.independentEvidence.decisions().getFirst().reason()).contains("查不到");
    }
    @Test void sameInputsReusePassedResultsUntilConfigurationOrLogsChange() throws Exception {
        verifyNow();
        assertThat(commandCalls()).isEqualTo(3);
        calls.clear(); flow.independentEvidence = null;
        verifyNow(); // 同一個 commit 再驗一次（例如回到驗收階段）：全部重用，不 clone。
        assertThat(calls).isEmpty();
        assertThat(flow.independentEvidence.decisions()).extracting(VerificationPlan.Decision::action)
                .containsOnly(VerificationPlan.Action.REUSE);
        assertThat(verifier.blockingReason(flow, head)).isNull();
        assertThat(PrReport.independent(flow.independentEvidence)).contains("♻️ 重用");
        Files.writeString(Path.of(flow.independentEvidence.decisions().getFirst().log()), "forged");
        assertThat(verifier.blockingReason(flow, head)).contains("紀錄遺失或雜湊不符");
        // 紀錄被改過或配置版本不同，都不能重用。
        calls.clear(); verifyNow();
        assertThat(flow.independentEvidence.decisions().getFirst().action()).isEqualTo(VerificationPlan.Action.RUN);
        flow.snapshot = new TeamDefinition.Snapshot("other-revision", flow.snapshot.definition(), flow.snapshot.repository(), flow.snapshot.assets());
        calls.clear(); verifyNow();
        assertThat(commandCalls()).isEqualTo(3);
        assertThat(flow.verificationHistory).hasSizeLessThanOrEqualTo(VerificationPlan.HISTORY_LIMIT);
    }

    @Test void decisionsAndHistorySurvivePersistenceAndOldFlowsStillParse() throws Exception {
        enableScopes();
        when(workspace.changedPathsSinceBase("42")).thenReturn(List.of("web/src/a.ts"));
        verifyNow();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        FlowRun restored = mapper.readValue(mapper.writeValueAsString(flow), FlowRun.class);
        assertThat(restored.independentEvidence.decisions()).isEqualTo(flow.independentEvidence.decisions());
        assertThat(restored.verificationHistory).hasSize(2);
        assertThat(verifier.blockingReason(restored, head)).isNull();
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(mapper.writeValueAsString(flow));
        json.remove("verificationHistory");
        assertThat(mapper.treeToValue(json, FlowRun.class).verificationHistory).isEmpty();
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
