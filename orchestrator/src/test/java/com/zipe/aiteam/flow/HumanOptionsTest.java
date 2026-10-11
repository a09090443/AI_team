package com.zipe.aiteam.flow;

import com.zipe.aiteam.task.Task;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class HumanOptionsTest {
    private final Task task = new Task("1014", "c", "t", "u", "多組 SMTP 容錯");
    @TempDir Path tmp;
    private FlowRun flow;

    @BeforeEach void setup() throws Exception {
        Path repo = tmp.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        flow = new FlowRun(TeamCatalog.load(Path.of("teams/spring-starters.json"), key -> repo.toString()));
    }

    private String text() { return HumanOptions.render(task, flow, true, true, null); }

    @Test void stoppedAtTheTestPlanOffersARequirementChangeWithACopyableFormat() {
        flow.node = "plan"; flow.baselineRevision = "baseline-1";

        assertThat(HumanOptions.options(task, flow, true, true, null))
                .anyMatch(o -> o.kind().equals(HumanOptions.REQUIREMENT) && o.label().equals("需求要改"));
        assertThat(text()).contains("需求清單本身有問題").contains("補充 #1014 核准需求變更 <要怎麼改與原因");
    }

    @Test void buttonVersionNeverTeachesTypingButTheTextFallbackStillDoes() {
        // 使用者要求：有按鈕就不要再教人打「補充 #N …」；要填內容的選項改按鈕開視窗。按鈕送不出去時才用打字版。
        flow.node = "plan"; flow.baselineRevision = "baseline-1";
        String withButtons = HumanOptions.render(task, flow, true, true, null, HumanOptions.Workspace.NONE, false, false);
        assertThat(withButtons).doesNotContain("補充 #").contains("按下方「需求要改」，在跳出的視窗填寫")
                .contains("按下方「告訴它還缺什麼」，在跳出的視窗填寫").contains("停止不等於完成");
        assertThat(HumanOptions.render(task, flow, true, true, null, HumanOptions.Workspace.NONE, false, true))
                .contains("補充 #1014 核准需求變更");
        assertThat(HumanOptions.options(task, flow, true, true, null))
                .filteredOn(HumanOptions.Option::input).extracting(HumanOptions.Option::kind)
                .contains(HumanOptions.REQUIREMENT, HumanOptions.SUPPLEMENT);
    }

    @Test void aStoppedTaskCanAlwaysMoveToANewTaskWithTheLatestRules() {
        assertThat(HumanOptions.options(task, flow, true, true, null))
                .anyMatch(o -> o.kind().equals(HumanOptions.SUCCESSOR) && o.label().equals("改用新任務繼續")
                        && HumanOptions.SUCCESSOR_COMMAND.equals(o.command()));
        var dirty = new HumanOptions.Workspace("h", false, java.util.List.of("src/test/A.java"), "fp9");
        assertThat(HumanOptions.options(task, flow, true, true, null, dirty))
                .anyMatch(o -> o.label().equals("保存修改並改用新任務繼續") && o.command().equals("承接成新任務 保存 fp9"));
    }

    @Test void noRequirementChangeOptionBeforeThereIsABaseline() {
        flow.node = "plan"; flow.baselineRevision = null;

        assertThat(HumanOptions.options(task, flow, true, true, null))
                .noneMatch(o -> o.kind().equals(HumanOptions.REQUIREMENT));
    }

    @Test void alwaysExplainsThatPlainResumeChangesNothing() {
        assertThat(text()).contains("你現在可以做的事")
                .contains("告訴它還缺什麼")
                .contains("只寫「續跑」不會改變上一輪的判斷依據")
                .contains("停止不等於完成");
    }

    @Test void mentionsFullReportOnlyInBriefMode() {
        assertThat(HumanOptions.render(task, flow, true, true, null)).contains("看完整報告");
        assertThat(HumanOptions.render(task, flow, true, false, null)).doesNotContain("看完整報告");
    }

    @Test void offersRequirementChangeApprovalOnlyWhenPending() {
        assertThat(text()).doesNotContain("需求要改");
        flow.changeTarget = "intake";
        assertThat(text()).contains("需求要改").contains("核准需求變更 <要怎麼改與原因");
    }

    @Test void offersRemediationRecoveryOnlyAfterVerificationExhaustedItsRetries() {
        flow.workspaceReady = true;
        assertThat(text()).doesNotContain("請它重驗");

        var verification = flow.snapshot.definition().nodes().get("verification");
        flow.visits.put("verification", verification.maxVisits()); flow.repeats.put("verification", verification.maxVisits());
        assertThat(text()).contains("請它重驗").contains("核准人工修復重驗 <驗收節點> <commit>");
    }

    @Test void remediationIsNotOfferedWithoutAWorkspace() {
        var verification = flow.snapshot.definition().nodes().get("verification");
        flow.visits.put("verification", verification.maxVisits()); flow.repeats.put("verification", verification.maxVisits());
        assertThat(text()).doesNotContain("請它重驗");
    }

    @Test void offersPlanApprovalWhenTheCurrentNodeStillNeedsIt() {
        flow.node = "verification";
        assertThat(text()).contains("核准測試計畫");
    }

    @Test void whenNothingElseAppliesTheOnlyWayOutIsANewTask() throws Exception {
        flow.node = "triage";
        // triage 已經做出結論，原樣重跑沒有意義，所以連「繼續」也不提供；但停下的任務不能沒有出路。
        flow.receipts.put("triage", new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"route\":\"human\"}"));
        assertThat(HumanOptions.options(task, flow, false, false, null)).extracting(HumanOptions.Option::kind)
                .containsExactly(HumanOptions.SUCCESSOR);
        assertThat(HumanOptions.render(task, flow, false, false, null)).contains("改用新任務繼續").doesNotContain("繼續執行");
    }

    // ---- 按鈕 ----

    @Test void extensionOptionCarriesTheTokenSoNobodyHasToRetypeIt() throws Exception {
        String token = "a".repeat(64);
        // 目前節點已做出結論，直接續跑不會前進，這時才該推延長額度。
        flow.receipts.put(flow.node, new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"route\":\"human\"}"));
        var options = HumanOptions.options(task, flow, true, true, token);

        var extend = options.stream().filter(o -> HumanOptions.EXTEND.equals(o.kind())).findFirst().orElseThrow();
        assertThat(extend.button()).isTrue();
        assertThat(extend.command()).isEqualTo("核准延長自動交接 " + token);
        assertThat(HumanOptions.render(task, flow, true, true, token)).contains("再給系統兩次機會").contains(token);
    }

    @Test void noExtensionOptionWithoutAToken() {
        assertThat(HumanOptions.options(task, flow, true, true, null))
                .noneMatch(o -> HumanOptions.EXTEND.equals(o.kind()));
        assertThat(HumanOptions.options(task, flow, true, true, "  "))
                .noneMatch(o -> HumanOptions.EXTEND.equals(o.kind()));
    }

    @Test void freeTextOptionsAreNeverTurnedIntoButtons() {
        flow.changeTarget = "intake";
        flow.workspaceReady = true;
        flow.visits.put("verification", flow.snapshot.definition().nodes().get("verification").maxVisits()); flow.repeats.put("verification", flow.snapshot.definition().nodes().get("verification").maxVisits());

        assertThat(HumanOptions.options(task, flow, true, true, "t".repeat(64)))
                .filteredOn(o -> java.util.Set.of(HumanOptions.REQUIREMENT, HumanOptions.REMEDIATE,
                        HumanOptions.PLAN, HumanOptions.SUPPLEMENT).contains(o.kind()))
                .isNotEmpty()
                .allSatisfy(o -> assertThat(o.button()).isFalse());
    }

    // ---- 恢復與繼續 ----

    private com.fasterxml.jackson.databind.JsonNode successfulImplementation() throws Exception {
        var report = (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"execution_status\":\"COMPLETED\","
                + "\"failure_report\":null,\"status\":\"SUCCESS\",\"summary\":\"ok\","
                + "\"quality_gate\":{\"verify_passed\":true},\"boundary_conflict\":null}");
        report.put("issue_revision", IssueTracker.revision(flow));
        report.putArray("issue_updates"); report.putArray("issue_checks");
        return report;
    }

    @Test void offersRestoreButtonForAWronglyRejectedImplementation() throws Exception {
        flow.node = "verification"; flow.workspaceReady = true;
        flow.blockedFindings.put("implementation", successfulImplementation());

        var options = HumanOptions.options(task, flow, true, true, null, new HumanOptions.Workspace("fa96f9f", true));

        var restore = options.stream().filter(o -> HumanOptions.RESTORE.equals(o.kind())).findFirst().orElseThrow();
        assertThat(restore.button()).isTrue();
        assertThat(restore.command()).isEqualTo("核准恢復前置契約 implementation fa96f9f");
        assertThat(options).noneMatch(o -> HumanOptions.RESUME.equals(o.kind()));
    }

    @Test void noRestoreButtonWhenTheWorkspaceIsDirty() throws Exception {
        flow.node = "verification"; flow.workspaceReady = true;
        flow.blockedFindings.put("implementation", successfulImplementation());

        assertThat(HumanOptions.options(task, flow, true, true, null, new HumanOptions.Workspace("fa96f9f", false)))
                .noneMatch(o -> HumanOptions.RESTORE.equals(o.kind()));
    }

    private void overLimitAtTriageWithRegisteredIncident() {
        flow.node = "triage";
        flow.transitions = flow.snapshot.definition().maxTransitions();
        flow.automaticHandoff = "a".repeat(64);
        flow.automaticHandoffKeys.add("incident:" + flow.automaticHandoff);
        flow.automaticHandoffs = AutomaticHandoff.LIMIT;
    }

    @Test void offersContinueWhenTheSameIncidentCanStillTakeAHop() {
        overLimitAtTriageWithRegisteredIncident();

        var options = HumanOptions.options(task, flow, true, true, "t".repeat(64));

        assertThat(options).anyMatch(o -> HumanOptions.RESUME.equals(o.kind()) && "續跑".equals(o.command()));
        // 直接繼續就能前進時，不再推延長額度。
        assertThat(options).noneMatch(o -> HumanOptions.EXTEND.equals(o.kind()));
    }

    @Test void doesNotOfferContinueForANodeThatAlreadyReachedItsConclusion() throws Exception {
        overLimitAtTriageWithRegisteredIncident();
        flow.receipts.put("triage", new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"route\":\"human\"}"));

        var options = HumanOptions.options(task, flow, true, true, "t".repeat(64));

        assertThat(options).noneMatch(o -> HumanOptions.RESUME.equals(o.kind()));
        assertThat(options).anyMatch(o -> HumanOptions.EXTEND.equals(o.kind()));
    }

    @Test void offersContinueWhenTheNodeWasInterruptedBeforeReportingAgain() throws Exception {
        // 上一輪留下的受阻結論還在，但這一輪已啟動、尚未交出報告就被系統層打斷。
        flow.node = "intake";
        flow.blockedFindings.put("intake", new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"detail\":\"上一輪\"}"));
        assertThat(HumanOptions.options(task, flow, true, true, null))
                .noneMatch(o -> HumanOptions.RESUME.equals(o.kind()));

        flow.activeNode = "intake";

        assertThat(HumanOptions.options(task, flow, true, true, null))
                .anyMatch(o -> HumanOptions.RESUME.equals(o.kind()) && "續跑".equals(o.command()));
    }

    @Test void offersContinueWhenAMissingPrerequisiteWillBeRedoneAutomatically() {
        flow.node = "review";

        assertThat(HumanOptions.options(task, flow, true, true, null))
                .anyMatch(o -> HumanOptions.RESUME.equals(o.kind()));
    }

    @Test void doesNotOfferContinueWhenTheIncidentIsNew() {
        overLimitAtTriageWithRegisteredIncident();
        flow.automaticHandoffKeys.clear();

        assertThat(HumanOptions.options(task, flow, true, true, null))
                .noneMatch(o -> HumanOptions.RESUME.equals(o.kind()));
    }

    @Test void doesNotOfferContinueWhenHopsAreExhausted() {
        overLimitAtTriageWithRegisteredIncident();
        flow.automaticHandoffHops = AutomaticHandoff.HOP_LIMIT;

        assertThat(HumanOptions.options(task, flow, true, true, null))
                .noneMatch(o -> HumanOptions.RESUME.equals(o.kind()));
    }

    // ---- agent 提出的選擇題 ----

    private com.fasterxml.jackson.databind.JsonNode decisionReport() throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"execution_status":"COMPLETED","route":"human",
                 "human_decision":{"headline":"h","question":"smtps 要一起支援嗎？",
                   "options":[{"label":"另開任務處理","consequence":"照現況完成"},
                              {"label":"這次一起支援","consequence":"重跑實作與驗收"},
                              {"label":"","consequence":"空標籤不應出現"}],
                   "recommendation":"r","also_needed":[]}}
                """);
    }

    @Test void decisionOptionsBecomeButtonsThatSendTheChoiceBackAsTheOwnersDecision() throws Exception {
        flow.node = "triage";
        flow.receipts.put("triage", decisionReport());

        var decisions = HumanOptions.options(task, flow, true, true, null).stream()
                .filter(o -> HumanOptions.DECISION.equals(o.kind())).toList();

        assertThat(decisions).extracting(HumanOptions.Option::label).containsExactly("另開任務處理", "這次一起支援");
        assertThat(decisions).allSatisfy(o -> assertThat(o.button()).isTrue());
        assertThat(decisions.getFirst().command())
                .startsWith("負責人決定：smtps 要一起支援嗎？")
                .contains("選擇：另開任務處理").contains("照現況完成");
    }

    @Test void decisionCommandsCanNeverBeMistakenForAnApprovalCommand() throws Exception {
        flow.node = "triage";
        var report = (com.fasterxml.jackson.databind.node.ObjectNode) decisionReport();
        ((com.fasterxml.jackson.databind.node.ObjectNode) report.path("human_decision").path("options").get(0))
                .put("label", "核准延長自動交接 abc");
        flow.receipts.put("triage", report);

        assertThat(HumanOptions.options(task, flow, true, true, null))
                .filteredOn(o -> HumanOptions.DECISION.equals(o.kind()))
                .allSatisfy(o -> assertThat(o.command()).startsWith("負責人決定："));
    }

    @Test void newerBlockedFindingWinsOverAnOlderReceipt() throws Exception {
        flow.node = "triage";
        flow.receipts.put("triage", new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"route\":\"human\"}"));
        flow.blockedFindings.put("triage", decisionReport());

        assertThat(HumanOptions.options(task, flow, true, true, null))
                .anyMatch(o -> HumanOptions.DECISION.equals(o.kind()));
    }

    @Test void textVersionListsDecisionLabelsCompactly() throws Exception {
        flow.node = "triage";
        flow.receipts.put("triage", decisionReport());

        String text = HumanOptions.render(task, flow, true, true, null);

        assertThat(text).contains("直接選一個：另開任務處理／這次一起支援");
        assertThat(text).doesNotContain("負責人決定：");
    }

    @Test void whileWaitingForRequirementChangeApprovalTheChoiceIsTheApprovalItself() throws Exception {
        // #1014：等待需求變更核准時只接受「核准需求變更」，送一般補充會被擋，按了兩次都沒反應。
        flow.node = "triage";
        flow.changeTarget = "intake";
        flow.receipts.put("triage", decisionReport());

        var decisions = HumanOptions.options(task, flow, true, true, null).stream()
                .filter(o -> HumanOptions.DECISION.equals(o.kind())).toList();

        assertThat(decisions).extracting(HumanOptions.Option::label)
                .containsExactly("核准：另開任務處理", "核准：這次一起支援");
        assertThat(decisions).allSatisfy(o -> assertThat(o.command()).startsWith("核准需求變更 smtps 要一起支援嗎？"));
        assertThat(HumanOptions.render(task, flow, true, true, null)).contains("按下去就是核准需求變更");
    }

    // ---- 自動處理次數全部用完 ----

    // ---- 自動處理次數全部用完 ----

    private void exhaust() {
        flow.node = "triage";
        flow.transitions = flow.snapshot.definition().maxTransitions() + 16;
        flow.automaticHandoffExtraBudget = AutomaticHandoff.MAX_EXTRA_BUDGET;
        flow.automaticHandoffs = AutomaticHandoff.effectiveLimit(flow);
        flow.automaticHandoff = null;
        flow.lastBlockReason = null;
    }

    @Test void exhaustedTaskSaysSoPlainlyAndOffersOnlyContinuation() throws Exception {
        exhaust();
        flow.changeTarget = "intake";
        flow.receipts.put("triage", decisionReport());

        var options = HumanOptions.options(task, flow, true, true, "t".repeat(64),
                new HumanOptions.Workspace("fa96f9f", true));
        assertThat(options).extracting(HumanOptions.Option::kind)
                .containsExactly(HumanOptions.SUCCESSOR, HumanOptions.REPORT);
        assertThat(options.getFirst().command()).isEqualTo(HumanOptions.SUCCESSOR_COMMAND);

        String text = HumanOptions.render(task, flow, true, true, "t".repeat(64), new HumanOptions.Workspace("fa96f9f", true));
        assertThat(text).contains("這個任務已經無法再自動往下跑").contains("不是故障")
                .contains("commit=fa96f9f（已全部提交）").contains("承接成新任務")
                .doesNotContain("核准：").doesNotContain("再給系統兩次機會");
    }

    @Test void exhaustedTaskWithUncommittedFilesNamesThemAndOffersToSaveBeforeContinuing() {
        exhaust();
        var ws = new HumanOptions.Workspace("f2a7d9b", false,
                java.util.List.of("src/test/MailFailoverTest.java", "src/test/MailTimeoutTest.java"), "fp123");

        var options = HumanOptions.options(task, flow, true, true, null, ws);

        assertThat(options.getFirst().label()).isEqualTo("保存未提交的修改並承接");
        assertThat(options.getFirst().command()).isEqualTo("承接成新任務 保存 fp123");
        String text = HumanOptions.render(task, flow, true, true, null, ws);
        assertThat(text).contains("2 個檔案改了但還沒 commit").contains("src/test/MailFailoverTest.java")
                .contains("沒有 commit 的權限").contains("保存未提交的修改並承接")
                .doesNotContain("承接前請先處理");
    }

    @Test void longUncommittedListIsShortenedWithACount() {
        var paths = java.util.stream.IntStream.range(0, 13).mapToObj(i -> "f" + i).toList();
        String listed = new HumanOptions.Workspace("h", false, paths, "fp").listUncommitted(10);
        assertThat(listed).contains("f9").doesNotContain("f10").contains("另外 3 個檔案");
    }

    @Test void exhaustedEvenWithOneEventLeftWhenTheStoppedNodeCannotUseIt() {
        // #1014：核准需求變更後停在需求節點，還剩一個事件額度，但需求節點不是任何節點的受阻交接目標，
        // 那個額度根本用不上。舊判斷只看剩幾次，於是仍提供按鈕，按了立刻被擋。
        flow.node = "intake";
        flow.transitions = flow.snapshot.definition().maxTransitions() + 16;
        flow.automaticHandoffExtraBudget = AutomaticHandoff.MAX_EXTRA_BUDGET;
        flow.automaticHandoffs = AutomaticHandoff.effectiveLimit(flow) - 1;
        flow.automaticHandoff = null;
        flow.lastBlockReason = "上一次受阻原因";

        assertThat(HumanOptions.exhausted(flow)).isTrue();
    }

    @Test void notExhaustedWhileAnExtensionWouldLetTheNextStepThrough() {
        exhaust();
        // triage 是受阻交接目標，有受阻原因就能建立事件；額度滿了但還能延長。
        flow.lastBlockReason = "驗收受阻";
        flow.automaticHandoffExtraBudget = AutomaticHandoff.MAX_EXTRA_BUDGET - AutomaticHandoff.EXTENSION_SIZE;
        flow.automaticHandoffs = AutomaticHandoff.effectiveLimit(flow);
        assertThat(HumanOptions.exhausted(flow)).isFalse();
    }

    @Test void extensionDoesNotCountWhenThereIsNoEvidenceToExtend() {
        exhaust();
        flow.automaticHandoffExtraBudget = 0;
        assertThat(HumanOptions.exhausted(flow)).isTrue();
    }

    @Test void notExhaustedWhileARegisteredIncidentStillHasHops() {
        exhaust();
        flow.automaticHandoff = "a".repeat(64);
        flow.automaticHandoffKeys.add("incident:" + flow.automaticHandoff);
        flow.automaticHandoffHops = 3;
        assertThat(HumanOptions.exhausted(flow)).isFalse();
    }

    @Test void notExhaustedBelowTheTotalStepLimit() {
        exhaust();
        flow.transitions = 0;
        assertThat(HumanOptions.exhausted(flow)).isFalse();
    }

    @Test void succeededTaskOffersNothingAndPointsToTheNewTask() {
        exhaust();
        flow.successor = "1016";
        assertThat(HumanOptions.options(task, flow, true, true, null)).isEmpty();
        assertThat(HumanOptions.render(task, flow, true, true, null)).contains("已由 *任務 #1016* 承接");
    }

    @Test void answeredQuestionAskedAgainGetsNoButtonsButAnExplanation() throws Exception {
        flow.node = "triage";
        flow.receipts.put("triage", decisionReport());
        flow.context = FlowContext.append("", "使用者 u 補充：負責人決定：smtps 要一起支援嗎？　選擇：另開任務處理（照現況完成）");

        assertThat(HumanOptions.options(task, flow, true, true, null))
                .noneMatch(o -> HumanOptions.DECISION.equals(o.kind()));
        assertThat(HumanOptions.render(task, flow, true, true, null)).contains("這個問題你已經回答過了");
    }

    // ---- 選項的 action：系統直接執行的結案與承接（#1022） ----

    private com.fasterxml.jackson.databind.JsonNode actionReport() throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"execution_status":"COMPLETED","route":"human",
                 "human_decision":{"headline":"h","question":"要怎麼收尾？",
                   "options":[{"label":"自己開 PR，結案","consequence":"PR 由你處理","action":"close"},
                              {"label":"改用新任務","consequence":"用新規則重跑","action":"successor"},
                              {"label":"再等等","consequence":"維持暫停","action":"decide"}],
                   "recommendation":"r","also_needed":[]}}
                """);
    }

    @Test void closeOptionSendsTheCloseCommandWithASystemNotice() throws Exception {
        flow.node = "triage";
        flow.receipts.put("triage", actionReport());

        var decisions = HumanOptions.options(task, flow, true, true, null).stream()
                .filter(o -> HumanOptions.DECISION.equals(o.kind())).toList();

        assertThat(decisions).extracting(HumanOptions.Option::label)
                .containsExactly("自己開 PR，結案", "改用新任務", "再等等");
        assertThat(decisions.get(0).command()).startsWith(HumanOptions.CLOSE_COMMAND + " 要怎麼收尾？")
                .doesNotStartWith("負責人決定：");
        assertThat(decisions.get(0).hint()).startsWith("PR 由你處理").contains("系統直接結案").contains("工作區保留");
        assertThat(decisions.get(1).command()).isEqualTo(HumanOptions.SUCCESSOR_COMMAND);
        assertThat(decisions.get(2).command()).startsWith("負責人決定：");
    }

    @Test void successorOptionCarriesTheSaveFingerprintAndIsDroppedWhenNotAvailable() throws Exception {
        flow.node = "triage";
        flow.receipts.put("triage", actionReport());
        var dirty = new HumanOptions.Workspace("h", false, java.util.List.of("src/A.java"), "fp7");

        assertThat(HumanOptions.options(task, flow, true, true, null, dirty))
                .filteredOn(o -> HumanOptions.DECISION.equals(o.kind()))
                .anyMatch(o -> o.command().equals(HumanOptions.SUCCESSOR_SAVE_COMMAND + " fp7"));

        flow.dialogue = true;
        assertThat(HumanOptions.options(task, flow, true, true, null))
                .filteredOn(o -> HumanOptions.DECISION.equals(o.kind()))
                .extracting(HumanOptions.Option::label).doesNotContain("改用新任務");
    }

    @Test void publishOptionIsDroppedWhenTheEvidenceCannotPublish() throws Exception {
        flow.node = "triage";
        var report = (com.fasterxml.jackson.databind.node.ObjectNode) actionReport();
        ((com.fasterxml.jackson.databind.node.ObjectNode) report.path("human_decision").path("options").get(2))
                .put("label", "直接開 PR").put("action", "publish");
        flow.receipts.put("triage", report);

        // 沒有驗收、審查結果：按了也開不出來，選項與固定按鈕都不出現。
        var options = HumanOptions.options(task, flow, true, true, null, new HumanOptions.Workspace("h", true));
        assertThat(options).extracting(HumanOptions.Option::label).doesNotContain("直接開 PR", "開 PR");
    }

    @Test void closeIsNeverTurnedIntoARequirementChangeApproval() throws Exception {
        flow.node = "triage";
        flow.changeTarget = "intake";
        flow.receipts.put("triage", actionReport());

        assertThat(HumanOptions.options(task, flow, true, true, null))
                .filteredOn(o -> o.label().equals("自己開 PR，結案"))
                .singleElement().satisfies(o -> assertThat(o.command()).startsWith(HumanOptions.CLOSE_COMMAND));
    }

    @Test void repeatedQuestionStillOffersTheDirectActionsButNotTheHandBackChoice() throws Exception {
        flow.node = "triage";
        flow.receipts.put("triage", actionReport());
        flow.context = FlowContext.append("", "使用者 u 補充：負責人決定：要怎麼收尾？　選擇：再等等（維持暫停）");

        assertThat(HumanOptions.options(task, flow, true, true, null))
                .filteredOn(o -> HumanOptions.DECISION.equals(o.kind()))
                .extracting(HumanOptions.Option::label).containsExactly("自己開 PR，結案", "改用新任務");
        assertThat(HumanOptions.render(task, flow, true, true, null)).doesNotContain("這個問題你已經回答過了");
    }

    @Test void stuckAtLimitOffersNoDecisionSupplementOrContinue() throws Exception {
        flow.node = "plan";
        flow.visits.put("plan", flow.snapshot.definition().nodes().get("plan").maxVisits()); flow.repeats.put("plan", flow.snapshot.definition().nodes().get("plan").maxVisits());
        flow.receipts.put("plan", decisionReport());

        var options = HumanOptions.options(task, flow, true, true, null);

        assertThat(options).extracting(HumanOptions.Option::kind)
                .doesNotContain(HumanOptions.DECISION, HumanOptions.SUPPLEMENT, HumanOptions.RESUME);
        assertThat(HumanOptions.render(task, flow, true, true, null))
                .contains("這一步已經重試到上限").contains("目前沒有可用的恢復方式");
    }

    @Test void stuckAtLimitWithADeployedConfigOffersToReviewIt() {
        flow.node = "plan";
        flow.visits.put("plan", flow.snapshot.definition().nodes().get("plan").maxVisits()); flow.repeats.put("plan", flow.snapshot.definition().nodes().get("plan").maxVisits());

        assertThat(HumanOptions.options(task, flow, true, true, null, HumanOptions.Workspace.NONE, true))
                .anyMatch(o -> HumanOptions.CONFIG_UPDATE.equals(o.kind()) && "檢視配置更新".equals(o.command()));
    }

    @Test void readOnlyReportOptionIsAButton() {
        var report = HumanOptions.options(task, flow, true, true, null).stream()
                .filter(o -> HumanOptions.REPORT.equals(o.kind())).findFirst().orElseThrow();
        assertThat(report.button()).isTrue();
        assertThat(report.command()).isEqualTo("檢視完整報告");
    }
}
