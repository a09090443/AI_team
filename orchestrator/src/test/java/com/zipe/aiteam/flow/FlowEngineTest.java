package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.publish.PullRequestPublisher;
import com.zipe.aiteam.runtime.*;
import com.zipe.aiteam.slack.SlackNotifier;
import com.zipe.aiteam.store.*;
import com.zipe.aiteam.task.*;
import com.zipe.aiteam.workspace.WorkspaceManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FlowEngineTest {
    @Test void legacyFlowJsonInitializesManualRecoveryTrackingFields() throws Exception {
        ObjectNode json=mapper.valueToTree(new FlowRun(catalog.snapshot()));
        json.remove(List.of("remediationRecoveryGrants","remediationRecoveryKeys","attemptHeads",
                "automaticHandoff","automaticHandoffs","automaticHandoffExtraBudget","automaticHandoffHops",
                "automaticHandoffKeys","automaticHandoffExtensionKeys","blockedFindings","spinoffs","spinoffOf"));
        FlowRun restored=mapper.treeToValue(json,FlowRun.class);
        assertThat(restored.spinoffs).isEmpty();
        assertThat(restored.spinoffOf).isNull();
        assertThat(restored.blockedFindings).isEmpty();
        assertThat(restored.remediationRecoveryGrants).isEmpty();
        assertThat(restored.remediationRecoveryKeys).isEmpty();
        assertThat(restored.attemptHeads).isEmpty();
        assertThat(restored.automaticHandoff).isNull();
        assertThat(restored.automaticHandoffs).isZero();
        assertThat(restored.automaticHandoffExtraBudget).isZero();
        assertThat(restored.automaticHandoffHops).isZero();
        assertThat(restored.automaticHandoffKeys).isEmpty();
        assertThat(restored.automaticHandoffExtensionKeys).isEmpty();
    }

    @Test void manualRemediationRecoveryRequiresNewCleanCommitAndFreshPlanApproval() throws Exception {
        var blocked=blockedVerificationAtLimit();
        // #1014 類型的舊快照沒有 attemptHeads，必須能從既有 beforeHead 安全判定。
        blocked.attemptHeads.clear();blocked.beforeHead="head1";store.saveFlow(task.id(),blocked);
        String revision=blocked.snapshot.revision();
        String fixed="2222222222222222222222222222222222222222";
        when(workspace.headSha("42")).thenReturn(fixed);
        List<Runnable> jobs=new ArrayList<>();

        engine.resume(task,"核准人工修復重驗 verification "+fixed,"u",jobs::add);
        var pending=store.flow(task.id()).orElseThrow();
        assertThat(pending.recoveryGrants).containsEntry("verification",revision);
        assertThat(pending.remediationRecoveryGrants).containsEntry("verification",fixed);
        assertThat(pending.remediationRecoveryKeys).containsExactly(revision+":verification:"+fixed);
        assertThat(pending.planApprovals).isEmpty();
        assertThat(pending.visits.get("verification")).isEqualTo(3);
        assertThat(jobs).isEmpty();

        engine.resume(task,"核准人工修復重驗 verification "+fixed,"u",jobs::add);
        verify(slack).post(any(),any(),any(),contains("一次重驗授權已使用"));

        engine.resume(task,"核准測試計畫 plan "+Traceability.digest(pending.receipts.get("plan")),"u",jobs::add);
        assertThat(jobs).hasSize(1); mode="normal"; jobs.getFirst().run();
        var completed=store.flow(task.id()).orElseThrow();
        assertThat(completed.visits.get("verification")).isEqualTo(4);
        assertThat(completed.recoveryGrants).isEmpty();
        assertThat(completed.remediationRecoveryGrants).isEmpty();
        assertThat(completed.remediationRecoveryKeys).containsExactly(revision+":verification:"+fixed);
        assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        verifyNoInteractions(publisher);
    }

    @Test void manualRemediationRecoveryRejectsUnchangedOrDirtyWorkspace() throws Exception {
        blockedVerificationAtLimit();
        List<Runnable> jobs=new ArrayList<>();
        engine.resume(task,"核准人工修復重驗 verification head1","u",jobs::add);
        verify(slack).post(any(),any(),any(),contains("沒有新的修復 commit"));
        when(workspace.headSha("42")).thenReturn("2222222222222222222222222222222222222222");
        when(workspace.clean("42")).thenReturn(false);
        engine.resume(task,"核准人工修復重驗 verification 2222222222222222222222222222222222222222","u",jobs::add);
        verify(slack).post(any(),any(),any(),contains("未提交修改"));
        assertThat(jobs).isEmpty();
    }

    @Test void cliUsageErrorRestoresManualRemediationGrantForSameCommit() throws Exception {
        var blocked=blockedVerificationAtLimit();
        String fixed="2222222222222222222222222222222222222222";
        when(workspace.headSha("42")).thenReturn(fixed);
        List<Runnable> jobs=new ArrayList<>();
        engine.resume(task,"核准人工修復重驗 verification "+fixed,"u",jobs::add);
        var pending=store.flow(task.id()).orElseThrow();
        engine.resume(task,"核准測試計畫 plan "+Traceability.digest(pending.receipts.get("plan")),"u",jobs::add);
        mode="usage"; jobs.getFirst().run();

        var restored=store.flow(task.id()).orElseThrow();
        assertThat(restored.visits.get("verification")).isEqualTo(3);
        assertThat(restored.recoveryGrants).containsEntry("verification",blocked.snapshot.revision());
        assertThat(restored.remediationRecoveryGrants).containsEntry("verification",fixed);
        assertThat(restored.remediationRecoveryKeys).isEmpty();
        assertThat(restored.planApprovals).containsKey("plan");
    }

    @Test void blockedFindingIsKeptOutsideContextAndSurvivesSaturation() throws Exception {
        var blocked = blockedVerificationAtLimit();

        // 受阻報告不進 receipts，只有 blockedFindings 保得住。
        assertThat(blocked.receipts).doesNotContainKey("verification");
        assertThat(blocked.blockedFindings).containsKey("verification");
        assertThat(blocked.blockedFindings.get("verification").path("uncertainties").get(0).asText())
                .isEqualTo("SMTP 無法核實");

        // context 飽和後仍必須送得出最新事件，這是 #1014 卡死的直接原因。
        blocked.context = "填充".repeat(FlowContext.MAX);
        store.saveFlow(task.id(), blocked);
        List<Runnable> jobs = new ArrayList<>();
        engine.resume(task, "補充說明", "u", jobs::add);

        assertThat(store.flow(task.id()).orElseThrow().context).contains("補充說明");
    }

    @Test void blockedFindingClearsOnlyWhenThatNodeItselfPasses() throws Exception {
        var initial = store.flow(task.id()).orElseThrow();
        initial.blockedFindings.put("intake", mapper.readTree("{\"detail\":\"舊缺陷\"}"));
        // triage 在這條正常路徑上不會執行，代表尚未有人處理的開放缺陷。
        initial.blockedFindings.put("triage", mapper.readTree("{\"detail\":\"仍未解除\"}"));
        store.saveFlow(task.id(), initial);

        engine.run(task);

        var flow = store.flow(task.id()).orElseThrow();
        assertThat(flow.blockedFindings).doesNotContainKey("intake").containsKey("triage");
    }

    @Test void agentExplainsTheDecisionItselfAndTheStopMessageOffersTheChoicesAsButtons() throws Exception {
        // #1014：triage 把三件事寫在 4000 字的 summary 裡，Slack 只露出前 500 字，
        // 最後變成使用者要另外去問。需要人決定的事必須由角色本人完整說清楚，並能一鍵選擇。
        mode = "blockedDecision";
        engine.run(task);

        assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.AWAIT_HUMAN);
        verify(slack).postConfigured(any(), any(), any(), argThat((String t) ->
                t.contains("*為什麼停下來*") && t.contains("smtps 這次要一起支援嗎？")
                && t.contains("A．另開任務處理") && t.contains("重跑實作與驗收") && t.contains("建議另開任務")));
        verify(slack).post(any(), any(), any(), argThat((String t) ->
                t.contains("需要你決定") && t.contains("請看上方") && t.contains("直接選一個：另開任務處理／這次一起支援")
                && !t.contains("A．另開任務處理")), any());
    }

    // ---- 純詢問的發現開成新需求 ----

    @Test void questionWithFindingsOffersSpinoffButtonsAfterCompleting() {
        // #1006：PM 回答時發現文件計數錯誤，只能寫進 out_of_scope；使用者要能一鍵開成新需求。
        mode = "question";
        engine.run(task);

        assertThat(task.state()).isEqualTo(Task.State.COMPLETED);
        assertThat(calls).containsExactly("intake:coordinator");
        verify(slack).post(any(), eq("c"), eq("t"), argThat((String t) -> t.contains("回答時另外發現")
                && t.contains("1. *文件計數改 69*：修正文件計數：68 改為 69") && t.contains("2. *補 API 範例*：補上 API 範例")),
                argThat(blocks -> blocks != null && blocks.size() == 2));
        assertThat(FlowEngine.spinoffTitles(store.flow(task.id()).orElseThrow())).containsExactly("文件計數改 69", "補 API 範例");
    }

    @Test void spinoffTitlesFallBackToNumbersWhenMissingOrMisaligned() {
        // 舊快照沒有 out_of_scope_titles；長度對不上時無法確定對應，不可貼錯標題。
        mode = "question";
        engine.run(task);
        var flow = store.flow(task.id()).orElseThrow();
        var report = (ObjectNode) flow.receipts.get("intake");
        report.remove("out_of_scope_titles");
        assertThat(FlowEngine.spinoffTitles(flow)).containsExactly(null, null);
        report.putArray("out_of_scope_titles").add("只有一個");
        assertThat(FlowEngine.spinoffTitles(flow)).containsExactly(null, null);
        report.putArray("out_of_scope_titles").add("文件計數改 69").add(" ");
        assertThat(FlowEngine.spinoffTitles(flow)).containsExactly("文件計數改 69", null);
    }

    @Test void noSpinoffButtonsWithoutFindingsOrForImplementationTasks() {
        mode = "questionEmpty";
        engine.run(task);
        assertThat(task.state()).isEqualTo(Task.State.COMPLETED);
        verify(slack, never()).post(any(), any(), any(), argThat((String t) -> t.contains("回答時另外發現")), any());
        // 實作類任務的 out_of_scope 是刻意排除的範圍，不是待處理的發現。
        assertThat(FlowEngine.spinoffItems(store.flow(task.id()).orElseThrow())).isEmpty();
        var implementation = store.flow(task.id()).orElseThrow();
        implementation.receipts.put("intake", mapper.createObjectNode().put("route", "implementation")
                .set("out_of_scope", mapper.createArrayNode().add("不做 smtps")));
        assertThat(FlowEngine.spinoffItems(implementation)).isEmpty();
    }

    @Test void spinoffOpensANewTaskInANewThreadOnlyOncePerFinding() {
        mode = "question";
        engine.run(task);
        when(slack.post(any(), eq("c"), isNull(), anyString())).thenReturn("new-thread");
        List<Runnable> jobs = new ArrayList<>();

        engine.spinoff(task, 1, "u", jobs::add);

        var parent = store.flow(task.id()).orElseThrow();
        String childId = parent.spinoffs.get(1);
        assertThat(childId).isNotNull().isNotEqualTo(task.id());
        var childTask = store.find(childId).orElseThrow();
        var child = store.flow(childId).orElseThrow();
        assertThat(childTask.slackThreadTs()).isEqualTo("new-thread");
        var queuedChild = (com.zipe.aiteam.task.TaskOrchestrator.QueuedTask) jobs.getFirst();
        assertThat(queuedChild.task().id()).isEqualTo(childTask.id());
        assertThat(queuedChild.task().slackThreadTs()).isEqualTo("new-thread");
        assertThat(childTask.requestText()).isEqualTo("修正文件計數：68 改為 69");
        assertThat(childTask.requesterUserId()).isEqualTo("u");
        assertThat(child.acceptanceOwner).isEqualTo("u");
        assertThat(child.spinoffOf).isEqualTo(task.id());
        assertThat(child.node).isEqualTo(child.snapshot.definition().entry());
        assertThat(child.planApprovals).isEmpty();
        assertThat(child.context).contains("由任務 #42 的發現開立").contains("API 共 69 個，文件寫 68 個");
        assertThat(jobs).hasSize(1);
        verify(slack).post(any(), eq("c"), eq("t"), argThat((String t) -> t.contains("任務 #" + childId) && t.contains("新討論串")));

        // 重複按同一項：不建立第二個任務。
        jobs.clear();
        engine.spinoff(task, 1, "u", jobs::add);
        assertThat(jobs).isEmpty();
        assertThat(store.flow(task.id()).orElseThrow().spinoffs).containsOnlyKeys(1).containsEntry(1, childId);
        verify(slack).post(any(), eq("c"), eq("t"), argThat((String t) -> t.contains("已經開成任務 #" + childId)));
    }

    @Test void spinoffRefusesOutsidersMissingItemsAndUnfinishedTasks() {
        mode = "question";
        engine.run(task);
        List<Runnable> jobs = new ArrayList<>();

        engine.spinoff(task, 1, "intruder", jobs::add);
        engine.spinoff(task, 3, "u", jobs::add);
        task.state(Task.State.AWAIT_HUMAN); store.save(task);
        engine.spinoff(task, 1, "u", jobs::add);

        assertThat(jobs).isEmpty();
        assertThat(store.flow(task.id()).orElseThrow().spinoffs).isEmpty();
        verify(slack, never()).post(any(), eq("c"), isNull(), anyString());
    }

    // ---- 從配置推導白話、承接成新任務 ----

    @Test void nextStepLabelsComeFromTheConfigurationNotFromHardcodedNodeNames() {
        var flow = store.flow(task.id()).orElseThrow();
        var triage = flow.snapshot.definition().nodes().get("triage");
        var labels = FlowEngine.routeLabels(flow, triage);
        String implementer = flow.snapshot.definition().roles()
                .get(flow.snapshot.definition().nodes().get(triage.routes().get("implementation")).role()).name();

        assertThat(labels.apply("implementation")).isEqualTo("交給 " + implementer + " 實作修正（implementation）");
        assertThat(labels.apply("human")).contains("需要你決定");
        assertThat(labels.apply("未配置的路線")).isEqualTo("未配置的路線");
    }

    private void exhaustAtTriage() {
        var flow = store.flow(task.id()).orElseThrow();
        flow.node = "triage";
        flow.transitions = flow.snapshot.definition().maxTransitions() + 5;
        flow.automaticHandoffExtraBudget = AutomaticHandoff.MAX_EXTRA_BUDGET;
        flow.automaticHandoffs = AutomaticHandoff.effectiveLimit(flow);
        flow.workspaceReady = true;
        flow.workspacePath = tmp.resolve("work").toAbsolutePath().normalize().toString();
        flow.context = "使用者 u 補充：核准需求變更 465 全部一起修";
        store.saveFlow(task.id(), flow);
        provisioned = true;
        task.state(Task.State.AWAIT_HUMAN); store.save(task);
    }

    @Test void exhaustedTaskCanBeContinuedAsANewTaskFromItsLatestCommit() throws Exception {
        exhaustAtTriage();
        when(slack.post(any(), eq("c"), isNull(), anyString())).thenReturn("new-thread");
        when(workspace.provisionFrom(any(), any(), anyString(), anyString(), anyString(), anyString())).thenReturn(tmp);
        when(workspace.pathFor(argThat((String id) -> !"42".equals(id)))).thenReturn(tmp.resolve("child"));
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(task, "承接成新任務", "u", jobs::add);

        var parent = store.flow(task.id()).orElseThrow();
        assertThat(parent.successor).isNotNull();
        var child = store.flow(parent.successor).orElseThrow();
        var childTask = store.find(parent.successor).orElseThrow();
        assertThat(childTask.slackThreadTs()).isEqualTo("new-thread");
        assertThat(childTask.requestText()).isEqualTo(task.requestText());
        assertThat(child.continuationOf).isEqualTo(task.id());
        assertThat(child.workspaceReady).isTrue();
        assertThat(child.planApprovals).isEmpty();
        assertThat(child.context).contains("承接自任務 #42").contains("核准需求變更 465 全部一起修");
        verify(workspace).provisionFrom(any(), any(), anyString(), eq("42"), eq("feature/task42"), eq("head1"));
        assertThat(jobs).hasSize(1);

        // 承接後原任務不再接受任何操作。
        jobs.clear();
        engine.resume(task, "續跑", "u", jobs::add);
        assertThat(jobs).isEmpty();
        assertThat(store.flow(task.id()).orElseThrow().successor).isEqualTo(parent.successor);
    }

    private void leaveUncommittedQaTests(List<String> paths) throws Exception {
        workspaceClean = false;
        when(workspace.uncommittedPaths("42")).thenReturn(paths);
        when(workspace.uncommittedFingerprint("42")).thenReturn("fp-seen");
        when(workspace.commitPaths(eq("42"), anyList(), anyString())).thenAnswer(c -> { workspaceClean = true; return "head2abcdef"; });
    }

    @Test void continuationWithUncommittedChangesExplainsWhichFilesAndOffersToSaveThem() throws Exception {
        // #1017：只回「沒有建立承接任務：IllegalArgumentException: 原任務工作區有未提交的修改，請先處理再承接」，沒有出路。
        exhaustAtTriage();
        leaveUncommittedQaTests(List.of("tests/mail-failover.test.js", "tests/mail-timeout.test.js"));
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(task, "承接成新任務", "u", jobs::add);

        assertThat(store.flow(task.id()).orElseThrow().successor).isNull();
        assertThat(jobs).isEmpty();
        verify(workspace, never()).commitPaths(any(), any(), any());
        var text = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(slack, atLeastOnce()).post(any(), eq("c"), eq("t"), text.capture(), any());
        assertThat(text.getAllValues()).anySatisfy(t -> assertThat(t)
                .contains("還沒有承接").contains("tests/mail-failover.test.js").contains("tests/mail-timeout.test.js")
                .contains("保存未提交的修改並承接").doesNotContain("Exception"));
    }

    @Test void savingUncommittedChangesCommitsExactlyThoseFilesThenCreatesTheSuccessor() throws Exception {
        exhaustAtTriage();
        leaveUncommittedQaTests(List.of("tests/mail-failover.test.js"));
        when(slack.post(any(), eq("c"), isNull(), anyString())).thenReturn("new-thread");
        when(workspace.provisionFrom(any(), any(), anyString(), anyString(), anyString(), anyString())).thenReturn(tmp);
        when(workspace.pathFor(argThat((String id) -> !"42".equals(id)))).thenReturn(tmp.resolve("child"));
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(task, "承接成新任務 保存 fp-seen", "u", jobs::add);

        verify(workspace).commitPaths(eq("42"), eq(List.of("tests/mail-failover.test.js")), contains("u 於承接時核准"));
        assertThat(store.flow(task.id()).orElseThrow().successor).isNotNull();
        assertThat(jobs).hasSize(1);
        verify(audit).record(eq("42"), eq("u"), eq(AuditLog.ACTION_RECOVERY), contains("承接前保存未提交修改 1 個檔案"));
    }

    @Test void savingIsRefusedWhenTheChangesMovedAfterTheMessageOrTouchProtectedPaths() throws Exception {
        exhaustAtTriage();
        leaveUncommittedQaTests(List.of("tests/mail-failover.test.js"));
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(task, "承接成新任務 保存 fp-old", "u", jobs::add);
        verify(slack).post(any(), eq("c"), eq("t"), contains("又變動了"));

        var protectedPath = store.flow(task.id()).orElseThrow().snapshot.definition().project().protectedPaths().getFirst()
                .replace("**", "x").replace("*", "x");
        when(workspace.uncommittedPaths("42")).thenReturn(List.of(protectedPath));
        engine.resume(task, "承接成新任務 保存 fp-seen", "u", jobs::add);
        verify(slack).post(any(), eq("c"), eq("t"), contains("受保護的路徑"));

        engine.resume(task, "承接成新任務 保存 fp-seen", "someone-else", jobs::add);

        verify(workspace, never()).commitPaths(any(), any(), any());
        assertThat(store.flow(task.id()).orElseThrow().successor).isNull();
        assertThat(jobs).isEmpty();
    }

    @Test void ownerCloseEndsTheTaskDirectlyWithoutHandingBackToTriage() throws Exception {
        // #1022：「確認結案」以前只會送回分流，按了五次每次都重跑分流後又停下。
        task.state(Task.State.AWAIT_HUMAN); store.save(task);
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(task, HumanOptions.CLOSE_COMMAND + " 要怎麼收尾？　選擇：自己開 PR，結案", "u", jobs::add);

        assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.ABORTED);
        assertThat(jobs).isEmpty();
        verify(audit).record(eq("42"), eq("u"), eq(AuditLog.ACTION_OWNER_CLOSE), contains("自己開 PR，結案"));
        verify(slack).post(any(), eq("c"), eq("t"), contains("已由 <@u> 結案"));
        verify(executor, never()).execute(any(), any(), any());
    }

    @Test void ownerCloseIsRefusedForOthersAndWhenNotWaitingForAHuman() throws Exception {
        when(props.allowedUsers()).thenReturn(Set.of("u", "someone-else"));
        task.state(Task.State.AWAIT_HUMAN); store.save(task);
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(task, HumanOptions.CLOSE_COMMAND, "someone-else", jobs::add);
        verify(slack).post(any(), eq("c"), eq("t"), contains("只有驗收負責人可以結案"));
        assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.AWAIT_HUMAN);

        task.state(Task.State.WAITING_QUOTA); store.save(task);
        engine.resume(task, HumanOptions.CLOSE_COMMAND, "u", jobs::add);
        verify(slack).post(any(), eq("c"), eq("t"), contains("只有停下等人時才能結案"));
        assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.WAITING_QUOTA);
        verify(audit, never()).record(any(), any(), eq(AuditLog.ACTION_OWNER_CLOSE), any());
    }

    @Test void ownerCanMoveAStoppedTaskToANewTaskWithTheLatestRulesEvenBeforeItIsExhausted() throws Exception {
        // #1019：還能延長，但舊快照的審查規則只會繼續退回；改進後的規則只能透過新任務套用。
        task.state(Task.State.AWAIT_HUMAN); store.save(task);
        var flow = store.flow(task.id()).orElseThrow();
        flow.workspaceReady = true; flow.workspacePath = tmp.resolve("work").toString(); store.saveFlow(task.id(), flow);
        provisioned = true;
        when(slack.post(any(), eq("c"), isNull(), anyString())).thenReturn("new-thread");
        when(workspace.provisionFrom(any(), any(), anyString(), anyString(), anyString(), anyString())).thenReturn(tmp);
        when(workspace.pathFor(argThat((String id) -> !"42".equals(id)))).thenReturn(tmp.resolve("child"));
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(task, "承接成新任務", "someone-else", jobs::add);
        assertThat(store.flow(task.id()).orElseThrow().successor).isNull();

        engine.resume(task, "承接成新任務", "u", jobs::add);

        var parent = store.flow(task.id()).orElseThrow();
        assertThat(parent.successor).isNotNull();
        assertThat(jobs).hasSize(1);
        verify(slack).post(any(), eq("c"), isNull(), contains("決定改用新任務繼續，套用目前最新的團隊規則"));
        assertThat(store.flow(parent.successor).orElseThrow().context).contains("負責人決定以新任務、最新配置繼續原任務");
    }

    @Test void continuationRefusesADirtyWorkspace() throws Exception {
        exhaustAtTriage();
        workspaceClean = false;
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(task, "承接成新任務", "u", jobs::add);

        assertThat(store.flow(task.id()).orElseThrow().successor).isNull();
        // 改為貼出含選項的停止訊息（白話說明＋保存按鈕），不再是單行例外字串。
        verify(slack).post(any(), any(), any(), contains("還沒 commit"), any());
    }

    @Test void onlyTheAcceptanceOwnerCanContinueATask() throws Exception {
        exhaustAtTriage();
        when(props.allowedUsers()).thenReturn(Set.of("u", "someone-else"));
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(task, "承接成新任務", "someone-else", jobs::add);

        assertThat(store.flow(task.id()).orElseThrow().successor).isNull();
        assertThat(jobs).isEmpty();
    }

    @Test void statusCommandRepostsOptionsWithoutChangingAnything() throws Exception {
        exhaustAtTriage();
        var before = mapper.writeValueAsString(store.flow(task.id()).orElseThrow());
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(task, "狀態", "u", jobs::add);

        assertThat(jobs).isEmpty();
        assertThat(mapper.writeValueAsString(store.flow(task.id()).orElseThrow())).isEqualTo(before);
        verify(slack, atLeastOnce()).post(any(), any(), any(), contains("承接成新任務"));
    }

    @Test void deniedToolTurnsAnOtherwiseCleanReportIntoABlock() throws Exception {
        // #1014：coordinator 完全沒有 Bash，triage 卻回報查核完成並直接分流。
        var flowBefore = store.flow(task.id()).orElseThrow();
        ObjectNode definition = mapper.valueToTree(flowBefore.snapshot.definition());
        ((ObjectNode) definition.path("roles").path("coordinator")).putArray("tools").add("Read").add("Grep").add("Glob");
        flowBefore.snapshot = new TeamDefinition.Snapshot(flowBefore.snapshot.revision(),
                mapper.treeToValue(definition, TeamDefinition.class), flowBefore.snapshot.repository(), flowBefore.snapshot.assets());
        store.saveFlow(task.id(), flowBefore);
        deniedAt = "intake";
        engine.run(task);

        var flow = store.flow(task.id()).orElseThrow();
        assertThat(flow.blockedFindings).containsKey("intake");
        assertThat(flow.receipts).doesNotContainKey("intake");
        assertThat(flow.lastBlockReason).contains("本輪有工具呼叫遭拒").contains("Bash");
        assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.AWAIT_HUMAN);
    }

    @Test void callShapeMismatchDoesNotBlockAReadOnlyNode() throws Exception {
        // #1016：分流有 git 權限但用了 `git -C <路徑> log`；不該因此每輪重跑到用完次數。
        deniedAt = "intake";
        engine.run(task);

        var flow = store.flow(task.id()).orElseThrow();
        assertThat(flow.receipts).containsKey("intake");
        assertThat(flow.blockedFindings).doesNotContainKey("intake");
    }

    @Test void commandFailurePreservesEvidenceAndContinuesInsteadOfInventingPermissionGap() throws Exception {
        mode = "commandFailure";
        deniedAt = "intake";
        engine.run(task);
        var flow = store.flow(task.id()).orElseThrow();
        assertThat(flow.receipts).containsKey("intake");
        assertThat(flow.blockedFindings).doesNotContainKey("intake");
        assertThat(flow.context).contains("不代表權限拒絕", "new blank line at EOF");
        assertThat(calls).anyMatch(c -> c.startsWith("implementation:"));
    }

    @Test void deniedToolDoesNotBlockAWorkspaceNodeWhoseOutputIsAnArtifact() throws Exception {
        // #1014：implementation 成功並提交，只因為 for 迴圈跑 mvn 被拒就被否決，導致 verification 永遠啟動不了。
        deniedAt = "implementation";
        engine.run(task);

        var flow = store.flow(task.id()).orElseThrow();
        assertThat(flow.receipts).containsKey("implementation");
        assertThat(flow.blockedFindings).doesNotContainKey("implementation");
        assertThat(calls).anyMatch(c -> c.startsWith("verification:"));
    }

    @Test void deniedToolDoesNotDoubleBlockAnAlreadyBlockedReport() throws Exception {
        deniedAt = "verification";
        var blocked = blockedVerificationAtLimit();

        // 原本的阻礙原因要保留，不能被工具遭拒的訊息取代。
        assertThat(blocked.lastBlockReason).doesNotContain("本輪有工具呼叫遭拒");
    }

    private FlowRun blockedVerificationAtLimit() throws Exception {
        var initial=store.flow(task.id()).orElseThrow();
        ObjectNode def=mapper.valueToTree(initial.snapshot.definition());
        ((ObjectNode)def.path("project")).put("requirePlanApproval",true);
        initial.snapshot=new TeamDefinition.Snapshot(initial.snapshot.revision(),mapper.treeToValue(def,TeamDefinition.class),initial.snapshot.repository(),initial.snapshot.assets());
        store.saveFlow(task.id(),initial);engine.run(task);
        var waiting=store.flow(task.id()).orElseThrow();
        List<Runnable> jobs=new ArrayList<>();
        engine.resume(task,"核准測試計畫 plan "+Traceability.digest(waiting.receipts.get("plan")),"u",jobs::add);
        mode="blocked";jobs.getFirst().run();
        var blocked=store.flow(task.id()).orElseThrow();
        blocked.visits.put("verification",blocked.snapshot.definition().nodes().get("verification").maxVisits()); blocked.repeats.put("verification",blocked.snapshot.definition().nodes().get("verification").maxVisits());
        blocked.recoveryRevisions.add(blocked.snapshot.revision());
        store.saveFlow(task.id(),blocked);
        assertThat(blocked.attemptHeads).containsEntry("verification","head1");
        task=store.find(task.id()).orElseThrow();
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
        return blocked;
    }

    @Test void migratedConfigurationNeedsExplicitSingleRecoveryAndFreshPlanApproval() throws Exception {
        var initial=store.flow(task.id()).orElseThrow();
        ObjectNode def=mapper.valueToTree(initial.snapshot.definition());
        ((ObjectNode)def.path("project")).put("requirePlanApproval",true);
        initial.snapshot=new TeamDefinition.Snapshot("before",mapper.treeToValue(def,TeamDefinition.class),initial.snapshot.repository(),initial.snapshot.assets());
        store.saveFlow(task.id(),initial);engine.run(task);
        var flow=store.flow(task.id()).orElseThrow();
        ((ObjectNode)def.path("project")).put("testInstructions","fixed capability instructions");
        var target=new TeamDefinition.Snapshot("after",mapper.treeToValue(def,TeamDefinition.class),flow.snapshot.repository(),flow.snapshot.assets());
        flow.node="triage";flow.visits.put("verification",3); flow.repeats.put("verification",3);
        TeamMigration.apply(flow,target,TeamMigration.preview(flow.snapshot,target).token());store.saveFlow(task.id(),flow);
        List<Runnable> jobs=new ArrayList<>();
        engine.resume(task,"核准恢復驗收 verification wrong","u",jobs::add);
        assertThat(store.flow(task.id()).orElseThrow().recoveryGrants).isEmpty();
        engine.resume(task,"核准恢復驗收 verification after","u",jobs::add);
        var pending=store.flow(task.id()).orElseThrow();
        assertThat(pending.recoveryGrants).containsEntry("verification","after");
        assertThat(pending.planApprovals).isEmpty();assertThat(jobs).isEmpty();
        int before=pending.transitions;
        engine.resume(task,"核准測試計畫 plan "+Traceability.digest(pending.receipts.get("plan")),"u",jobs::add);
        assertThat(jobs).hasSize(1);jobs.getFirst().run();
        var completed=store.flow(task.id()).orElseThrow();
        assertThat(completed.visits.get("verification")).isEqualTo(4);
        assertThat(completed.recoveryGrants).isEmpty();
        assertThat(completed.recoveryRevisions).containsExactly("after");
        assertThat(completed.transitions).isGreaterThan(before);
        assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        verifyNoInteractions(publisher);
        completed.activeNode=null;
        assertThatThrownBy(()->VerificationRecovery.authorize(completed,"verification","after","u")).hasMessageContaining("已使用");
    }
    /** 參數被 CLI 拒絕時模型完全沒跑，計數與一次性授權必須原樣還原（#1014）。 */
    @Test void cliUsageErrorRestoresVisitAndRecoveryGrantBecauseNoModelRan() throws Exception {
        var initial=store.flow(task.id()).orElseThrow();
        ObjectNode def=mapper.valueToTree(initial.snapshot.definition());
        ((ObjectNode)def.path("project")).put("requirePlanApproval",true);
        initial.snapshot=new TeamDefinition.Snapshot("before",mapper.treeToValue(def,TeamDefinition.class),initial.snapshot.repository(),initial.snapshot.assets());
        store.saveFlow(task.id(),initial);engine.run(task);
        var flow=store.flow(task.id()).orElseThrow();
        ((ObjectNode)def.path("project")).put("testInstructions","fixed capability instructions");
        var target=new TeamDefinition.Snapshot("after",mapper.treeToValue(def,TeamDefinition.class),flow.snapshot.repository(),flow.snapshot.assets());
        flow.node="triage";flow.visits.put("verification",3); flow.repeats.put("verification",3);
        TeamMigration.apply(flow,target,TeamMigration.preview(flow.snapshot,target).token());store.saveFlow(task.id(),flow);
        List<Runnable> jobs=new ArrayList<>();
        engine.resume(task,"核准恢復驗收 verification after","u",jobs::add);
        var pending=store.flow(task.id()).orElseThrow();
        int transitionsBefore=pending.transitions;
        engine.resume(task,"核准測試計畫 plan "+Traceability.digest(pending.receipts.get("plan")),"u",jobs::add);
        mode="usage";
        assertThat(jobs).hasSize(1);jobs.getFirst().run();

        var saved=store.flow(task.id()).orElseThrow();
        assertThat(saved.visits.get("verification")).isEqualTo(3);
        assertThat(saved.transitions).isEqualTo(transitionsBefore);
        assertThat(saved.recoveryGrants).containsEntry("verification","after");
        assertThat(saved.recoveryRevisions).doesNotContain("after");
        assertThat(saved.activeNode).isNull();
        assertThat(saved.planApprovals).containsKey("plan");
        assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.AWAIT_HUMAN);
        // 模型根本沒被呼叫，直接續跑就是正確的下一步，因此會附「繼續執行」按鈕。
        verify(slack).post(any(),any(),any(),argThat((String t)->t.contains("本次沒有真的執行 agent")&&t.contains("已還原")
                &&t.contains("繼續執行")),any());
        verifyNoInteractions(publisher);
    }
    @Test void retryCapDoesNotConsumeAnotherVisitOrHideOriginalFailure() {
        when(props.briefReports()).thenReturn(true);
        var flow = store.flow(task.id()).orElseThrow();
        int limit = flow.snapshot.definition().nodes().get(flow.node).maxVisits();
        flow.visits.put(flow.node, limit); flow.repeats.put(flow.node, limit); flow.transitions = 9;
        flow.lastBlockReason = "QA bwrap: Can't mkdir .agents: Read-only file system";
        store.saveFlow(task.id(), flow);
        engine.run(task); engine.run(task);
        var saved = store.flow(task.id()).orElseThrow();
        assertThat(saved.transitions).isEqualTo(9);
        assertThat(saved.visits.get(flow.node)).isEqualTo(limit);
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
        verifyNoInteractions(executor, publisher);
        // 摘要模式下「看完整報告」是按鈕，所以走帶 blocks 的多載。
        verify(slack, times(2)).post(any(), any(), any(), argThat((String text) ->
                text.contains("不是 token 或用量不足") && text.contains("QA bwrap:")
                && text.contains("本次沒有啟動任何 agent") && text.contains("檢視完整報告")
                && text.length() < 1800 && !text.contains("具體答案")), any());
    }
    @Test void oldBlockedSnapshotCanAutomaticallyReachResponsibleTriageDespiteBothCaps() {
        var flow = store.flow(task.id()).orElseThrow();
        flow.node = "triage";
        flow.lastBlockReason = "verification：SC-04 缺少精確失敗測試";
        flow.visits.put("triage", flow.snapshot.definition().nodes().get("triage").maxVisits()); flow.repeats.put("triage", flow.snapshot.definition().nodes().get("triage").maxVisits());
        flow.transitions = flow.snapshot.definition().maxTransitions();
        store.saveFlow(task.id(), flow);

        engine.run(task);

        var saved = store.flow(task.id()).orElseThrow();
        assertThat(calls).containsExactly("triage:coordinator");
        assertThat(saved.automaticHandoffs).isEqualTo(1);
        assertThat(saved.visits.get("triage")).isEqualTo(4);
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
    }

    @Test void boundedAutomaticHandoffRoutesRepairAndReverificationWithoutResettingHistory() {
        mode = "automaticRepair";

        engine.run(task);

        var saved = store.flow(task.id()).orElseThrow();
        assertThat(calls).containsSubsequence("verification:tester", "triage:coordinator",
                "implementation:builder", "frontend:frontend", "verification:tester", "review:security_auditor");
        assertThat(saved.automaticHandoffs).isEqualTo(1);
        assertThat(saved.automaticHandoffHops).isZero();
        assertThat(saved.transitions).isGreaterThan(saved.snapshot.definition().maxTransitions());
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        verifyNoInteractions(publisher);
    }

    @Test void automaticHandoffBudgetStillStopsARealLoop() {
        var flow = store.flow(task.id()).orElseThrow();
        flow.node = "triage";
        flow.lastBlockReason = "同一阻礙仍未解除";
        flow.automaticHandoff = "evidence";
        flow.automaticHandoffs = AutomaticHandoff.LIMIT;
        flow.visits.put("triage", flow.snapshot.definition().nodes().get("triage").maxVisits()); flow.repeats.put("triage", flow.snapshot.definition().nodes().get("triage").maxVisits());
        store.saveFlow(task.id(), flow);

        engine.run(task);

        assertThat(calls).isEmpty();
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
        // 額度用完時會附延長授權按鈕，因此走的是帶 blocks 的多載。
        verify(slack).post(any(), any(), any(), contains("系統自己處理過的問題 6 件，上限 6 件"), any());
    }
    @Test void legacyPerHopKeysAreCollapsedBeforeApplyingIncidentBudget() {
        var flow = store.flow(task.id()).orElseThrow();
        String first = "a".repeat(64), second = "b".repeat(64);
        flow.node = "triage"; flow.lastBlockReason = "新的可修復問題"; flow.automaticHandoff = "fresh";
        flow.automaticHandoffKeys.addAll(List.of(first + ":verification:7", first + ":triage:5",
                second + ":verification:8", second + ":triage:6",
                "incident:" + "c".repeat(64), "incident:" + "d".repeat(64)));
        flow.automaticHandoffs = 6;
        flow.visits.put("triage", flow.snapshot.definition().nodes().get("triage").maxVisits()); flow.repeats.put("triage", flow.snapshot.definition().nodes().get("triage").maxVisits());
        store.saveFlow(task.id(), flow);

        engine.run(task);

        var saved = store.flow(task.id()).orElseThrow();
        assertThat(calls).containsExactly("triage:coordinator");
        assertThat(saved.automaticHandoffs).isEqualTo(5);
        assertThat(saved.automaticHandoffKeys).contains("incident:" + first, "incident:" + second,
                "incident:" + "c".repeat(64), "incident:" + "d".repeat(64), "incident:fresh");
        verify(audit).record(eq("42"), eq(AuditLog.ACTOR_SYSTEM), eq(AuditLog.ACTION_RECOVERY),
                contains("6→4"));
    }
    @Test void acceptanceOwnerCanGrantBoundedNonReplayableIncidentExtension() {
        var flow = store.flow(task.id()).orElseThrow();
        flow.node = "triage"; flow.lastBlockReason = "第七個獨立問題"; flow.automaticHandoff = "seventh";
        for (int i = 0; i < AutomaticHandoff.LIMIT; i++) flow.automaticHandoffKeys.add("incident:used-" + i);
        flow.automaticHandoffs = AutomaticHandoff.LIMIT;
        flow.visits.put("triage", flow.snapshot.definition().nodes().get("triage").maxVisits()); flow.repeats.put("triage", flow.snapshot.definition().nodes().get("triage").maxVisits());
        store.saveFlow(task.id(), flow); engine.run(task);
        String token = AutomaticHandoff.extensionToken(store.flow(task.id()).orElseThrow(), null);
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(task, "核准延長自動交接 " + token, "u", jobs::add);

        var extended = store.flow(task.id()).orElseThrow();
        assertThat(extended.automaticHandoffExtraBudget).isEqualTo(AutomaticHandoff.EXTENSION_SIZE);
        assertThat(AutomaticHandoff.effectiveLimit(extended)).isEqualTo(8);
        assertThat(extended.automaticHandoffExtensionKeys).containsExactly(token);
        assertThat(jobs).hasSize(1);
        engine.resume(task, "核准延長自動交接 " + token, "u", jobs::add);
        assertThat(jobs).hasSize(1);
    }
    @Test void afterConfigUpdateTheStopMessageOffersAnExtensionThatActuallyRunsTheNextStep() {
        // #1017：套用配置更新會清空 automaticHandoff 與額外額度，已用 10 件保留、上限回到 6。
        // 舊版判斷「可以延長」卻產生不出授權碼，停止訊息沒有延長也沒有承接；修正前即使有碼也要連按三次。
        task.state(Task.State.AWAIT_HUMAN); store.save(task);
        var flow = store.flow(task.id()).orElseThrow();
        flow.node = "triage"; flow.activeNode = null; flow.automaticHandoff = null;
        flow.lastBlockReason = "review：提前停止備援的產品缺陷";
        for (int i = 0; i < 10; i++) flow.automaticHandoffKeys.add("incident:used-" + i);
        flow.automaticHandoffs = 10; flow.automaticHandoffExtraBudget = 0;
        flow.transitions = flow.snapshot.definition().maxTransitions() + 27;
        store.saveFlow(task.id(), flow);
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(task, "狀態", "u", jobs::add);

        String token = AutomaticHandoff.extensionToken(store.flow(task.id()).orElseThrow(), null);
        assertThat(token).isNotNull();
        verify(slack).post(any(), eq("c"), eq("t"), anyString(),
                argThat(blocks -> blocks != null && blocks.toString().contains("核准延長自動交接 " + token)));

        engine.resume(task, "核准延長自動交接 " + token, "u", jobs::add);
        assertThat(jobs).hasSize(1);
        jobs.getFirst().run();

        assertThat(calls).first().isEqualTo("triage:coordinator");
        assertThat(store.flow(task.id()).orElseThrow().automaticHandoffs).isGreaterThanOrEqualTo(11);
    }
    @Test void sameBlockedIncidentCanTraverseConfiguredNodesButStillHasHopLimit() {
        var flow = store.flow(task.id()).orElseThrow();
        flow.node = "triage"; flow.automaticHandoff = "same-incident";
        flow.automaticHandoffKeys.add("incident:same-incident");
        flow.automaticHandoffs = 4; flow.automaticHandoffHops = AutomaticHandoff.HOP_LIMIT;
        flow.visits.put("triage", flow.snapshot.definition().nodes().get("triage").maxVisits()); flow.repeats.put("triage", flow.snapshot.definition().nodes().get("triage").maxVisits());
        store.saveFlow(task.id(), flow);

        engine.run(task);

        assertThat(calls).isEmpty();
        assertThat(store.flow(task.id()).orElseThrow().automaticHandoffs).isEqualTo(4);
        verify(slack).post(any(), any(), any(), contains("同一件問題轉手 12 站，上限 12 站"));
    }
    @Test void totalCapDoesNotIncrementNodeVisit() {
        var flow = store.flow(task.id()).orElseThrow();
        flow.transitions = flow.snapshot.definition().maxTransitions();
        store.saveFlow(task.id(), flow); engine.run(task);
        var saved = store.flow(task.id()).orElseThrow();
        assertThat(saved.transitions).isEqualTo(flow.transitions);
        assertThat(saved.visits).isEmpty();
        verifyNoInteractions(executor, publisher);
    }
    @Test void migrationRequiresExactApprovalAndPersistsHistoryWithoutEnqueue() throws Exception {
        task.state(Task.State.AWAIT_HUMAN); store.save(task);
        var old = catalog.snapshot(); ObjectNode definition = mapper.valueToTree(old.definition());
        ((ObjectNode)definition.path("project")).put("buildInstructions", "reviewed build instructions");
        var next = new TeamDefinition.Snapshot("next", mapper.treeToValue(definition, TeamDefinition.class), old.repository(), old.assets());
        when(catalog.snapshot()).thenReturn(next);
        List<Runnable> jobs = new ArrayList<>();
        engine.resume(task, "檢視配置更新", "u", jobs::add);
        assertThat(store.flow(task.id()).orElseThrow().snapshot).isEqualTo(old);
        engine.resume(task, "核准配置更新 wrong", "u", jobs::add);
        assertThat(store.flow(task.id()).orElseThrow().snapshot).isEqualTo(old);
        engine.resume(task, "核准配置更新 " + TeamMigration.preview(old, next).token(), "u", jobs::add);
        var saved = store.flow(task.id()).orElseThrow();
        assertThat(saved.snapshot).isEqualTo(next); assertThat(saved.migrationHistory).containsExactly(old);
        assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(jobs).isEmpty(); verifyNoInteractions(executor, publisher);
    }
    @Test void nodeStuckAtItsLimitByAConfigGapCanRunAgainAfterTheConfigIsFixed() throws Exception {
        // #1016：測試計畫節點缺少文件驗收操作，連續受阻到重試上限。按選項、補充都不會重新執行；
        // 管理者補好配置並部署後，必須有一條「檢視 → 核准 → 重新執行這一步」的路。
        var flow = store.flow(task.id()).orElseThrow();
        var old = flow.snapshot;
        flow.node = "plan";
        flow.visits.put("plan", old.definition().nodes().get("plan").maxVisits()); flow.repeats.put("plan", old.definition().nodes().get("plan").maxVisits());
        flow.receipts.put("intake", report(TeamDefinition.Gate.REQUIREMENTS));
        store.saveFlow(task.id(), flow);
        task.state(Task.State.AWAIT_HUMAN); store.save(task);
        List<Runnable> jobs = new ArrayList<>();

        // 卡在上限、尚無新配置：不提供會讓人誤以為能繼續的選項。
        engine.resume(task, "狀態", "u", jobs::add);
        verify(slack, atLeastOnce()).post(any(), any(), any(), argThat((String t) ->
                t.contains("這一步已經重試到上限") && t.contains("目前沒有可用的恢復方式") && !t.contains("繼續執行")));

        ObjectNode definition = mapper.valueToTree(old.definition());
        ((ObjectNode)definition.path("project")).put("testInstructions", "補上文件驗收操作");
        var next = new TeamDefinition.Snapshot("next", mapper.treeToValue(definition, TeamDefinition.class), old.repository(), old.assets());
        when(catalog.snapshot()).thenReturn(next);

        engine.resume(task, "狀態", "u", jobs::add);
        verify(slack, atLeastOnce()).post(any(), any(), any(), argThat((String t) -> t.contains("請先按「檢視配置更新」")), any());

        engine.resume(task, "核准配置更新 " + TeamMigration.preview(old, next).token(), "u", jobs::add);
        verify(slack, atLeastOnce()).post(any(), any(), any(), argThat((String t) -> t.contains("重新執行這一步")), any());
        assertThat(jobs).isEmpty();

        engine.resume(task, "核准恢復驗收 plan next", "u", jobs::add);
        var saved = store.flow(task.id()).orElseThrow();
        assertThat(saved.recoveryGrants).containsEntry("plan", "next");
        assertThat(saved.visits.get("plan")).isEqualTo(old.definition().nodes().get("plan").maxVisits());
        assertThat(jobs).hasSize(1);
    }

    // ---- 受保護路徑改派（#1014） ----

    /** 在任務快照加入 Codex 實作角色與實作節點的改派設定；原角色 runtime 視 .claude/ 為受保護路徑。 */
    void enableProtectedPathFallback() throws Exception {
        var flow = store.flow(task.id()).orElseThrow();
        ObjectNode json = mapper.valueToTree(flow.snapshot.definition());
        ObjectNode role = ((ObjectNode) json.path("roles").path("builder")).deepCopy();
        role.put("runtime", "codex").put("botTokenEnv", "SLACK_BOT_TOKEN_BUILDER_CODEX").put("appTokenEnv", "SLACK_APP_TOKEN_BUILDER_CODEX");
        ((ObjectNode) json.path("roles")).set("builder_codex", role);
        var fallback = ((ObjectNode) json.path("nodes").path("implementation")).putObject("protectedPathFallback");
        fallback.put("role", "builder_codex"); fallback.putArray("paths").add(".claude/rules/**");
        flow.snapshot = new TeamDefinition.Snapshot(flow.snapshot.revision(), mapper.treeToValue(json, TeamDefinition.class),
                flow.snapshot.repository(), flow.snapshot.assets());
        store.saveFlow(task.id(), flow);
        AgentRuntime claude = mock(AgentRuntime.class);
        when(claude.protectedWritePaths()).thenReturn(List.of(".claude/", ".git/"));
        when(claude.isProtectedWrite(anyString())).thenCallRealMethod();
        when(executor.runtimeFor("claude-code")).thenReturn(claude);
    }

    @Test void protectedFilesGoToTheFallbackRoleThenThePrimaryImplementerFinishes() throws Exception {
        // #1020：整個節點交給 Codex 時寫不了 .agents/、跑不了 Gradle。改成只交出受保護檔案，其餘由原角色完成。
        mode = "protectedRule";
        enableProtectedPathFallback();
        when(workspace.uncommittedPaths("42")).thenReturn(List.of(".claude/rules/api-endpoints.md"), List.of());
        // 只有改派段看得到這個變更；系統代為提交後，下游節點的起始 commit 已包含它。
        when(workspace.auditPaths(eq("42"), anyString())).thenReturn(List.of(".claude/rules/api-endpoints.md"), List.of());

        engine.run(task);

        assertThat(calls).containsSubsequence("implementation:builder_codex", "implementation:builder");
        var flow = store.flow(task.id()).orElseThrow();
        assertThat(flow.roleOverrides).isEmpty();
        assertThat(flow.delegatedParts.get("implementation").files()).containsExactly(".claude/rules/api-endpoints.md");
        assertThat(flow.delegatedParts.get("implementation").role()).isEqualTo("builder_codex");
        verify(workspace).commitPaths(eq("42"), eq(List.of(".claude/rules/api-endpoints.md")), contains("分段改派 builder_codex"));
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
    }

    @Test void delegatedPartReportingPartialStillHandsOverToThePrimaryImplementer() throws Exception {
        // #1054：codex 兩次都改好並提交受保護檔案，卻因 PARTIAL 被當成整個實作的結果，主實作段一次都沒跑。
        mode = "protectedRulePartial";
        enableProtectedPathFallback();
        when(workspace.uncommittedPaths("42")).thenReturn(List.of(".claude/rules/api-endpoints.md"), List.of());
        when(workspace.auditPaths(eq("42"), anyString())).thenReturn(List.of(".claude/rules/api-endpoints.md"), List.of());

        engine.run(task);

        assertThat(calls).containsSubsequence("implementation:builder_codex", "implementation:builder");
        assertThat(store.flow(task.id()).orElseThrow().delegatedParts).containsKey("implementation");
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
    }

    @Test void partialDelegatedPartThatChangedNothingIsNotTreatedAsDone() throws Exception {
        mode = "protectedRulePartial";
        enableProtectedPathFallback();
        when(workspace.auditPaths(eq("42"), anyString())).thenReturn(List.of());

        engine.run(task);

        assertThat(calls).contains("implementation:builder_codex").doesNotContain("implementation:builder");
        assertThat(store.flow(task.id()).orElseThrow().delegatedParts).doesNotContainKey("implementation");
    }

    @Test void delegatedPartTouchingAnythingOutsideTheAllowedPathsStops() throws Exception {
        // 改派段只准動允許範圍：連一般檔案（例如 .agents/ 的 skill）也不行，那是原角色的工作。
        mode = "protectedRule";
        enableProtectedPathFallback();
        when(workspace.auditPaths(eq("42"), anyString())).thenReturn(List.of(".claude/rules/api-endpoints.md", ".agents/skills/x/SKILL.md"));

        engine.run(task);

        assertThat(calls).contains("implementation:builder_codex").doesNotContain("implementation:builder")
                .noneMatch(c -> c.startsWith("verification:"));
        assertThat(task.state()).isNotEqualTo(Task.State.AWAIT_PR_APPROVAL);
        verify(workspace, never()).commitPaths(eq("42"), anyList(), contains("改派"));
        verify(audit).record(eq("42"), any(), eq(AuditLog.ACTION_DENIED), contains(".agents/skills/x/SKILL.md"));
    }

    @Test void delegatedPartCannotEditAnotherFileInTheSameAllowedDirectory() throws Exception {
        mode = "protectedRule";
        enableProtectedPathFallback();
        when(workspace.auditPaths(eq("42"), anyString())).thenReturn(List.of(".claude/rules/not-requested.md"));
        engine.run(task);
        assertThat(calls).contains("implementation:builder_codex").doesNotContain("implementation:builder")
                .noneMatch(c -> c.startsWith("verification:"));
        verify(workspace, never()).commitPaths(eq("42"), anyList(), contains("改派"));
    }

    @Test void reassignedImplementerTouchingSettingsStopsBeforeAnyDownstreamStep() throws Exception {
        mode = "protectedRule";
        enableProtectedPathFallback();
        when(workspace.auditPaths(eq("42"), anyString())).thenReturn(List.of(".claude/rules/api-endpoints.md", ".claude/settings.json"));

        engine.run(task);

        assertThat(calls).contains("implementation:builder_codex").noneMatch(c -> c.startsWith("verification:"));
        assertThat(task.state()).isNotEqualTo(Task.State.AWAIT_PR_APPROVAL);
        verify(workspace, never()).commitPaths(eq("42"), anyList(), contains("改派"));
        verify(audit).record(eq("42"), any(), eq(AuditLog.ACTION_DENIED), contains(".claude/settings.json"));
    }

    @Test void resumingAfterQuotaSkipsTheCompletedDelegatedPart() throws Exception {
        // 改派段已提交、原角色跑到一半額度用完：續跑只接原角色的 session，不重跑改派段。
        mode = "protectedRuleQuota";
        enableProtectedPathFallback();
        when(workspace.uncommittedPaths("42")).thenReturn(List.of(".claude/rules/api-endpoints.md"), List.of());
        when(workspace.auditPaths(eq("42"), anyString())).thenReturn(List.of(".claude/rules/api-endpoints.md"), List.of());
        engine.run(task);
        assertThat(task.state()).isEqualTo(Task.State.WAITING_QUOTA);
        assertThat(calls).containsSubsequence("implementation:builder_codex", "implementation:builder");

        calls.clear(); mode = "protectedRule"; List<Runnable> jobs = new ArrayList<>();
        engine.resume(task, null, "u", jobs::add); jobs.get(0).run();

        assertThat(calls).first().isEqualTo("implementation:builder");
        assertThat(calls).doesNotContain("implementation:builder_codex");
        verify(workspace, times(1)).commitPaths(eq("42"), anyList(), contains("分段改派"));
    }

    @Test void legacyWholeNodeReassignmentIsKeptForTasksThatAlreadyChoseIt() throws Exception {
        // 部署前已整節點改派的任務（例如 #1020）中途不換規則。
        mode = "protectedRule";
        enableProtectedPathFallback();
        var flow = store.flow(task.id()).orElseThrow();
        flow.roleOverrides.put("implementation", "builder_codex"); store.saveFlow(task.id(), flow);
        when(workspace.uncommittedPaths("42")).thenReturn(List.of(".claude/rules/api-endpoints.md"), List.of());
        when(workspace.auditPaths(eq("42"), anyString())).thenReturn(List.of(".claude/rules/api-endpoints.md"), List.of());

        engine.run(task);

        assertThat(calls).contains("implementation:builder_codex").doesNotContain("implementation:builder");
        assertThat(store.flow(task.id()).orElseThrow().delegatedParts).isEmpty();
        verify(workspace).commitPaths(eq("42"), eq(List.of(".claude/rules/api-endpoints.md")), contains("改派 builder_codex"));
    }

    @Test void ordinaryChangesStayWithTheConfiguredImplementer() throws Exception {
        enableProtectedPathFallback();
        engine.run(task);
        assertThat(calls).contains("implementation:builder").doesNotContain("implementation:builder_codex");
        assertThat(store.flow(task.id()).orElseThrow().roleOverrides).isEmpty();
    }

    @Test void approvedPlanCanBeViewedWhileTheTaskIsRunningWithoutApprovalButtons() {
        // 自動核准後的訊息叫人「檢視測試計畫」，但當時任務在執行，以前一律被「目前為 STAGE_RUNNING」擋掉。
        engine.run(task);
        task.state(Task.State.STAGE_RUNNING); store.save(task);
        when(slack.post(any(), any(), any(), anyString())).thenReturn("sent");
        List<Runnable> jobs = new ArrayList<>();
        engine.resume(task, "檢視測試計畫", "u", jobs::add);
        assertThat(jobs).isEmpty();
        verify(slack).post(any(), any(), any(), contains("這份計畫已核准"));
        verify(slack, never()).post(any(), any(), any(), contains("測試計畫已完整列出"), anyList());
        verify(slack, never()).post(any(), any(), any(), contains("不能新增續跑"));
        assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.STAGE_RUNNING);
    }
    @Test void planApprovalQueuesExactlyOnceButDoesNotPublish() throws Exception {
        var flow = store.flow(task.id()).orElseThrow();
        ObjectNode definition = mapper.valueToTree(flow.snapshot.definition());
        ((ObjectNode)definition.path("project")).put("requirePlanApproval", true);
        flow.snapshot = new TeamDefinition.Snapshot(flow.snapshot.revision(), mapper.treeToValue(definition, TeamDefinition.class), flow.snapshot.repository(), flow.snapshot.assets());
        store.saveFlow(task.id(), flow);
        engine.run(task);
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(calls).doesNotContain("verification:tester", "review:security_auditor");
        var waiting = store.flow(task.id()).orElseThrow();
        waiting.visits.put("triage", 3); waiting.repeats.put("triage", 3); store.saveFlow(task.id(), waiting);
        when(slack.post(any(), any(), any(), anyString())).thenReturn("sent");
        List<Runnable> displayJobs = new ArrayList<>();
        engine.resume(task, "檢視測試計畫", "u", displayJobs::add);
        assertThat(displayJobs).isEmpty();
        verify(slack).post(any(), any(), any(), contains("測試計畫已完整列出"), anyList());
        assertThat(store.flow(task.id()).orElseThrow().visits.get("triage")).isEqualTo(3);
        String digest = Traceability.digest(waiting.receipts.get("plan"));
        List<Runnable> jobs = new ArrayList<>();
        engine.resume(task, "核准測試計畫 plan wrong", "u", jobs::add);
        assertThat(store.flow(task.id()).orElseThrow().planApprovals).isEmpty();
        var decision = new com.zipe.aiteam.slack.PlanReview.Decision(task.id(), "plan", digest, waiting.snapshot.revision(), waiting.baselineRevision);
        engine.planDecision(task, "u", decision, true, "wrong-channel", task.slackThreadTs(), jobs::add);
        engine.planDecision(task, "stranger", decision, true, task.slackChannel(), task.slackThreadTs(), jobs::add);
        engine.planDecision(task, "u", new com.zipe.aiteam.slack.PlanReview.Decision(task.id(), "plan", digest, "stale", waiting.baselineRevision), true, task.slackChannel(), task.slackThreadTs(), jobs::add);
        assertThat(store.flow(task.id()).orElseThrow().planApprovals).isEmpty();
        assertThat(jobs).isEmpty();
        engine.planDecision(task, "u", decision, false, task.slackChannel(), task.slackThreadTs(), jobs::add);
        assertThat(store.flow(task.id()).orElseThrow().planApprovals).isEmpty();
        assertThat(store.flow(task.id()).orElseThrow().pendingPlanRevision).isEqualTo("plan");
        assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(jobs).isEmpty();
        engine.planDecision(task, "u", decision, true, task.slackChannel(), task.slackThreadTs(), jobs::add);
        engine.planDecision(task, "u", decision, true, task.slackChannel(), task.slackThreadTs(), jobs::add);
        assertThat(store.flow(task.id()).orElseThrow().pendingPlanRevision).isNull();
        assertThat(jobs).hasSize(1);
        assertThat(store.flow(task.id()).orElseThrow().planApprovals).containsKey("plan");
        assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.RESUME_QUEUED);
        engine.resume(task, "核准測試計畫 plan " + digest, "u", jobs::add);
        assertThat(jobs).hasSize(1);
        jobs.getFirst().run();
        assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        assertThat(store.flow(task.id()).orElseThrow().visits.get("triage")).isEqualTo(3);
        verifyNoInteractions(publisher);
    }
    @Test void explicitRecoveryRequiresValidApprovalAndQueuesOnlyOnce() throws Exception {
        var flow = store.flow(task.id()).orElseThrow();
        ObjectNode definition = mapper.valueToTree(flow.snapshot.definition());
        ((ObjectNode)definition.path("project")).put("requirePlanApproval", true);
        flow.snapshot = new TeamDefinition.Snapshot(flow.snapshot.revision(), mapper.treeToValue(definition, TeamDefinition.class), flow.snapshot.repository(), flow.snapshot.assets());
        store.saveFlow(task.id(), flow); engine.run(task);
        List<Runnable> jobs = new ArrayList<>();
        assertThatThrownBy(() -> engine.resumeApprovedPlan(task, jobs::add)).hasMessageContaining("沒有有效");
        var waiting = store.flow(task.id()).orElseThrow();
        var approval = PlanApproval.approve(waiting, "plan", Traceability.digest(waiting.receipts.get("plan")), "u");
        waiting.planApprovals.put("plan", approval); store.saveFlow(task.id(), waiting);
        engine.resumeApprovedPlan(task, jobs::add); engine.resumeApprovedPlan(task, jobs::add);
        assertThat(jobs).hasSize(1);
        assertThat(store.flow(task.id()).orElseThrow().planApprovals.get("plan")).isEqualTo(approval);
        jobs.getFirst().run();
        assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        verifyNoInteractions(publisher);
    }
    private Path operationJob(FlowRun f, String state) throws Exception {
        when(props.homePath()).thenReturn(tmp);
        f.workspaceReady=true; f.activeNode=f.node;
        Path dir=tmp.resolve("operation-jobs/42");Files.createDirectories(dir);
        ObjectNode record=mapper.createObjectNode();
        record.put("version",1).put("id","a".repeat(32)).put("task","42").put("node",f.node)
                .put("action","start").put("target","test-target").put("source_head","head1")
                .put("state",state).put("verified",true).put("updated",1).put("deadline",java.time.Instant.now().getEpochSecond()+60);
        if (state.equals("SUCCEEDED")) record.putObject("result").put("id","a".repeat(32))
                .put("action","start").put("target","test-target").put("exit_code",0);
        Path file=dir.resolve("a".repeat(32)+".json");Files.writeString(file,record.toString());return file;
    }
    @Test void pendingOperationWaitsWithoutAnotherModelOrNodeCount() throws Exception {
        FlowRun f=new FlowRun(catalog.snapshot());operationJob(f,"RUNNING");f.transitions=8;
        task.state(Task.State.STAGE_RUNNING);store.save(task);store.saveFlow(task.id(),f);
        engine.operationPoll=()->{try{operationJob(f,"SUCCEEDED");}catch(Exception e){throw new RuntimeException(e);}};
        assertThat(engine.reconcileOperations(task,f,f.snapshot.definition().nodes().get(f.node),false))
                .isEqualTo(FlowEngine.OperationDisposition.RESUME);
        assertThat(f.transitions).isEqualTo(8);assertThat(f.activeNode).isEqualTo(f.node);
        assertThat(f.context).contains("SUCCEEDED","只接續剩餘步驟");
        verifyNoInteractions(executor,publisher);
    }
    @Test void modelTimeoutReconcilesLateSuccessOnceWithoutNewStart() throws Exception {
        FlowRun f=new FlowRun(catalog.snapshot());operationJob(f,"SUCCEEDED");
        task.state(Task.State.STAGE_RUNNING);store.save(task);store.saveFlow(task.id(),f);
        assertThat(engine.reconcileOperations(task,f,f.snapshot.definition().nodes().get(f.node),true))
                .isEqualTo(FlowEngine.OperationDisposition.RESUME);
        assertThat(engine.reconcileOperations(task,f,f.snapshot.definition().nodes().get(f.node),true))
                .isEqualTo(FlowEngine.OperationDisposition.NONE);
        verifyNoInteractions(executor,publisher);
    }
    @Test void restartWithTrustedOperationQueuesSameTaskAndSnapshot() throws Exception {
        FlowRun f=new FlowRun(catalog.snapshot());operationJob(f,"RUNNING");
        task.state(Task.State.STAGE_RUNNING);store.save(task);store.saveFlow(task.id(),f);
        List<Runnable> queued=new ArrayList<>();engine.recover(task,queued::add);
        assertThat(queued).hasSize(1);assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.RESUME_QUEUED);
        assertThat(store.flow(task.id()).orElseThrow().snapshot.revision()).isEqualTo(f.snapshot.revision());
        verifyNoInteractions(executor,publisher);
    }
    @Test void abortedTaskNeverAutomaticallyResumesAnOperation() throws Exception {
        FlowRun f=new FlowRun(catalog.snapshot());operationJob(f,"RUNNING");
        task.state(Task.State.ABORTED);store.save(task);store.saveFlow(task.id(),f);
        assertThat(engine.reconcileOperations(task,f,f.snapshot.definition().nodes().get(f.node),false))
                .isEqualTo(FlowEngine.OperationDisposition.STOP);
        verifyNoInteractions(executor,publisher);
    }
    @Test void closedParentRejectsUnsupportedExecutionAdviceForAnyQuestion() {
        var parent=new Task("1048","c","t","u","deploy");parent.state(Task.State.PR_CREATED);store.save(parent);
        FlowRun f=store.flow(task.id()).orElseThrow();f.dialogue=true;f.parentId="1048";
        assertThat(engine.dialogueReplyReason(task,f,"請輸入 @QA Agent 執行 重跑測試")).contains("已結案");
        assertThat(engine.dialogueReplyReason(task,f,"原任務已完成，未進行新的部署。")).isNull();
    }
    @Test void restartQueuesPersistedServiceDialogueWithoutChangingParentOrCounts() throws Exception {
        enableService(true);when(props.homePath()).thenReturn(tmp);
        FlowRun f=store.flow(task.id()).orElseThrow();f.dialogue=true;f.dialogueRole="builder";f.parentId="1048";f.node="dialogue";
        f.transitions=12;store.saveFlow(task.id(),f);task.state(Task.State.STAGE_RUNNING);store.save(task);
        var target=f.snapshot.definition().project().serviceTarget().name();
        var context=tmp.resolve("service-context");Files.createDirectories(context);
        var registration=mapper.createObjectNode().put("target",target);registration.putArray("access_endpoints").addObject();
        Files.writeString(context.resolve(target+".json"),registration.toString());
        var path=tmp.resolve("service-support/42/job.json");Files.createDirectories(path.getParent());
        Files.writeString(path,mapper.createObjectNode().put("version",1).put("task","42").put("parent","1048").put("target",target)
                .put("state","RUNNING").put("generation",engine.supportGeneration(task,f)).put("deadline",java.time.Instant.now().getEpochSecond()+60).toString());
        List<Runnable> queued=new ArrayList<>();engine.recover(task,queued::add);
        assertThat(queued).hasSize(1);assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.RESUME_QUEUED);
        assertThat(store.flow("42").orElseThrow().transitions).isEqualTo(12);
        verifyNoInteractions(executor,publisher);
    }
    @Test void serviceDiagnosticRestartRequiresSameCleanSource() throws Exception {
        enableService(true);when(props.homePath()).thenReturn(tmp);
        FlowRun f=store.flow(task.id()).orElseThrow();f.node="service";f.activeNode="service";f.workspaceReady=true;
        f.transitions=8;f.visits.put("service",1);store.saveFlow(task.id(),f);
        task.state(Task.State.STAGE_RUNNING);store.save(task);
        var registration=mapper.createObjectNode().put("target","test-host");registration.putArray("access_endpoints").addObject();
        Files.createDirectories(tmp.resolve("service-context"));Files.writeString(tmp.resolve("service-context/test-host.json"),registration.toString());
        var path=tmp.resolve("service-support/42/job.json");Files.createDirectories(path.getParent());
        Files.writeString(path,mapper.createObjectNode().put("version",1).put("task","42").put("parent","42").put("target","test-host")
                .put("generation",engine.supportGeneration(task,f)).put("state","RUNNING").put("deadline",java.time.Instant.now().getEpochSecond()+60).toString());
        List<Runnable> queued=new ArrayList<>();engine.recover(task,queued::add);
        assertThat(queued).hasSize(1);assertThat(store.flow("42").orElseThrow().transitions).isEqualTo(8);
        queued.clear();workspaceHead="head2";task.state(Task.State.STAGE_RUNNING);store.save(task);
        engine.recover(task,queued::add);
        assertThat(queued).isEmpty();assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.AWAIT_HUMAN);
        verifyNoInteractions(executor,publisher);
    }
    @Test void serviceDialogueCorrectsWrongUrlWithoutHumanOrDeploying() throws Exception {
        when(props.homePath()).thenReturn(tmp);mode="dialogueWrongUrl";
        FlowRun f=store.flow(task.id()).orElseThrow();f.dialogue=true;f.dialogueRole="coordinator";f.node="dialogue";
        store.saveFlow(task.id(),f);
        task=new Task("42","c","t","u","網址無法打開");store.save(task);
        Path evidence=tmp.resolve("service-support/42/current.json");Files.createDirectories(evidence.getParent());
        Files.writeString(evidence,"{\"healthy\":true,\"endpoints\":[{\"url\":\"http://127.0.0.1:8088/view/chat\",\"audience\":\"Windows\",\"http_status\":200}]}" );
        engine.run(task);
        assertThat(store.find("42").orElseThrow().state()).withFailMessage("audit=%s calls=%s",mockingDetails(audit).getInvocations(),calls).isEqualTo(Task.State.COMPLETED);
        assertThat(calls).hasSize(2);
        assertThat(store.find("42").orElseThrow().stage("dialogue").orElseThrow().result().path("answer").asText()).contains("http://127.0.0.1:8088/view/chat");
        verifyNoInteractions(publisher,verifier);
    }
    @Test void completedServiceDialogueWithUnsupportedAdviceCanBeCorrected() throws Exception {
        enableService(true);FlowRun f=store.flow(task.id()).orElseThrow();f.dialogue=true;f.dialogueRole="builder";f.parentId="1048";f.node="dialogue";
        store.saveFlow(task.id(),f);task.state(Task.State.COMPLETED);store.save(task);
        var reply=mapper.createObjectNode().put("answer","請開http://127.0.0.1:8088/view/chat，輸入`@Infrastructure Agent 執行 檢查轉送`" );
        store.saveStage(task.id(),new com.zipe.aiteam.task.StageOutcome("dialogue","session",reply,0.1,0));
        task=store.find("42").orElseThrow();List<Runnable> queued=new ArrayList<>();engine.resumeDialogueTask(task,queued::add);
        assertThat(queued).hasSize(1);assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.RESUME_QUEUED);
        assertThat(store.flow("42").orElseThrow().context).contains("上次回答留下不能執行");verifyNoInteractions(executor,publisher);
    }
    @Test void stoppedDialogueCanResumeSameQuestionWithoutChangingParent() throws Exception {
        FlowRun f=store.flow(task.id()).orElseThrow();f.dialogue=true;f.dialogueRole="coordinator";f.parentId="1048";f.node="dialogue";
        store.saveFlow(task.id(),f);task.state(Task.State.AWAIT_HUMAN);store.save(task);
        List<Runnable> queued=new ArrayList<>();engine.resumeDialogueTask(task,queued::add);
        assertThat(queued).hasSize(1);assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.RESUME_QUEUED);
        assertThat(store.flow("42").orElseThrow().parentId).isEqualTo("1048");verifyNoInteractions(executor,publisher);
    }
    @TempDir Path tmp;
    ObjectMapper mapper = new ObjectMapper();
    TaskStore store;
    TeamCatalog catalog = mock(TeamCatalog.class);
    AiTeamProperties props = mock(AiTeamProperties.class);
    ConfiguredExecutor executor = mock(ConfiguredExecutor.class);
    WorkspaceManager workspace = mock(WorkspaceManager.class);
    SlackNotifier slack = mock(SlackNotifier.class);
    AuditLog audit = mock(AuditLog.class);
    PullRequestPublisher publisher = mock(PullRequestPublisher.class);
    IndependentVerifier verifier = mock(IndependentVerifier.class);
    ErrorExplainer explainer = mock(ErrorExplainer.class);
    FlowEngine engine;
    Task task = new Task("42", "c", "t", "u", "email failover");
    List<String> calls = new ArrayList<>();
    List<String> resumed = new ArrayList<>();
    List<String> consults = new ArrayList<>();
    String mode = "normal";
    String deniedAt;
    boolean provisioned;
    boolean workspaceClean;
    boolean editBaseline;
    String workspaceHead;
    String dirtyPath = "base-spring-boot-starter/src/test/java/com/example/MailTest.java";
    @BeforeEach void setup(TestInfo info) throws Exception {
        Files.createDirectories(tmp.resolve("repo/.git"));
        var ds = new org.sqlite.SQLiteDataSource(); ds.setUrl("jdbc:sqlite:" + tmp.resolve("db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        store = new TaskStore(new JdbcTemplate(ds));
        when(catalog.enabled()).thenReturn(true);
        boolean spring = info.getTestMethod().orElseThrow().getName().startsWith("spring");
        when(catalog.snapshot()).thenReturn(TeamCatalog.load(Path.of(spring ? "teams/maven-library.json" : "teams/node-web.json"), key -> tmp.resolve("repo").toString()));
        // 既有流程測試維持「不需計畫核准」契約；新核准測試另明確啟用。
        var original = catalog.snapshot();
        // Existing scenarios exercise persisted pre-issue-tracking snapshots. New contract tests opt in explicitly.
        var legacyAssets = new java.util.LinkedHashMap<>(original.assets());
        legacyAssets.remove(IssueTracker.CONTRACT);
        ObjectNode legacyDefinition = mapper.valueToTree(original.definition());
        ((ObjectNode)legacyDefinition.path("project")).put("requirePlanApproval", false);
        when(catalog.snapshot()).thenReturn(new TeamDefinition.Snapshot(original.revision(),
                mapper.treeToValue(legacyDefinition, TeamDefinition.class), original.repository(), legacyAssets));
        when(props.allowedUsers()).thenReturn(Set.of("u"));
        when(workspace.exists("42")).thenAnswer(c -> provisioned);
        when(workspace.provision(eq(task), any(), anyString())).thenAnswer(c -> { provisioned = true; return tmp; });
        workspaceClean = true; workspaceHead = "head1";
        when(workspace.pathFor("42")).thenReturn(tmp.resolve("work"));
        when(workspace.headSha("42")).thenAnswer(c -> workspaceHead);
        when(workspace.clean("42")).thenAnswer(c -> workspaceClean);
        when(workspace.origin("42")).thenReturn("https://github.com/test/example.git");
        when(workspace.currentBranch("42")).thenReturn("feature/task42"); when(workspace.baseBranch("42")).thenReturn("main");
        when(workspace.auditPaths(eq("42"), anyString())).thenAnswer(c -> workspaceClean ? List.of()
                : List.of("base-spring-boot-starter/src/test/java/com/example/MailTest.java"));
        when(workspace.uncommittedPaths("42")).thenAnswer(c -> workspaceClean ? List.of() : List.of(dirtyPath));
        // 動手前同步（BaseSync）預設基準沒有前進。
        when(workspace.mergeBase(any(), any(), any(), any())).thenAnswer(c -> new WorkspaceManager.BaseMerge("base0", List.of(), true));
        when(workspace.commitPaths(eq("42"), anyList(), anyString())).thenAnswer(c -> {
            workspaceClean = true; workspaceHead = "head-qa-tests"; return workspaceHead; });
        when(executor.execute(any(), any(), any())).thenAnswer(c -> {
            FlowRun flow = c.getArgument(1); TeamDefinition.Node node = c.getArgument(2); calls.add(flow.node + ":" + node.role());
            Task executing = c.getArgument(0);
            if (mode.equals("dialogueWrongUrl")) {
                var answer=mapper.createObjectNode().put("answer","請開http://192.0.2.20/").put("execution_status","COMPLETED").putNull("failure_report");
                return new AgentRuntime.RuntimeResult("session","",answer,0.1,false,tmp.resolve("log"),"model",null);
            }
            if (executing.continuation() != null) resumed.add(flow.node + ":" + executing.continuation().sessionId());
            if (mode.equals("interrupt") && flow.node.equals("implementation") && executing.continuation() == null) {
                store.saveCheckpoint(new Checkpoint("42", flow.node, "claude-code", "sonnet", "kept-session", "context", "head1", null));
                engine.interrupt(executing, "u");
                // 被停掉的程序看起來像連線中斷；若沒有中斷旗標，這會被系統自動重試。
                throw new AgentRuntime.RuntimeExecutionException("Connection reset; 結束碼 143");
            }
            // agent 已經交出結果才收到中斷：結果照常採用，停在下一步開始前。
            if (mode.equals("interruptAfter") && flow.node.equals("implementation")) engine.interrupt(executing, "u");
            if (mode.equals("usage") && flow.node.equals("verification"))
                throw new RuntimeUsageError("Codex CLI 用法錯誤，指令未送達模型（結束碼 2）：error: unexpected argument '-P' found");
            if (mode.equals("protectedRuleQuota") && flow.node.equals("implementation") && node.role().equals("builder")) {
                store.saveCheckpoint(new Checkpoint("42", flow.node, "claude-code", "sonnet", "builder-session", "context", "head1", "quota"));
                throw new QuotaExceeded("usage_limit_reached");
            }
            if (mode.equals("accessDenied") && flow.node.equals("verification"))
                throw new com.zipe.aiteam.runtime.ProviderAccessDenied(com.zipe.aiteam.runtime.ProviderAccessDenied.Kind.ORGANIZATION,
                        "claude-code", "供應商拒絕存取：status=403 code=oauth_not_allowed_for_organization");
            if (mode.equals("quota") && flow.node.equals("verification")) {
                store.saveCheckpoint(new Checkpoint("42", flow.node, "claude-code", "sonnet", "saved-session", "context", "head1", "quota"));
                throw new QuotaExceeded("usage_limit_reached");
            }
            if (mode.startsWith("modelTimeout") && flow.node.equals("implementation")
                    && (mode.equals("modelTimeoutAlways") || calls.stream().filter("implementation:builder"::equals).count() == 1)) {
                store.saveCheckpoint(new Checkpoint("42", flow.node, "claude-code", "sonnet", "impl-session", "context", "head1", null));
                throw new com.zipe.aiteam.runtime.RuntimeTimedOut("claude-code", java.time.Duration.ofSeconds(1800),
                        tmp.resolve("stdout"), tmp.resolve("stderr"), new com.zipe.aiteam.sandbox.SandboxUnitCleanup.Result(
                                com.zipe.aiteam.sandbox.SandboxUnitCleanup.Status.CONFIRMED, "ai-team-test.service", "test"));
            }
            if (mode.startsWith("serviceOperationTimeout") && flow.node.equals("service")
                    && calls.stream().filter(v -> v.startsWith("service:")).count()==1) {
                operationJob(flow,"SUCCEEDED");
                throw new com.zipe.aiteam.runtime.RuntimeTimedOut("codex", java.time.Duration.ofSeconds(1),
                        tmp.resolve("stdout"), tmp.resolve("stderr"),
                        new com.zipe.aiteam.sandbox.SandboxUnitCleanup.Result(
                                mode.endsWith("Unknown") ? com.zipe.aiteam.sandbox.SandboxUnitCleanup.Status.UNKNOWN
                                        : com.zipe.aiteam.sandbox.SandboxUnitCleanup.Status.CONFIRMED,
                                "ai-team-test.service","test"));
            }
            JsonNode report = report(node.gate());
            if (mode.startsWith("service") && flow.node.equals("intake")) ((ObjectNode)report).put("route", "service");
            if (mode.equals("light") && flow.node.equals("intake")) ((ObjectNode)report).put("route", "light");
            if (mode.equals("draftRepair") && flow.node.equals("intake")
                    && calls.stream().filter(s -> s.startsWith("intake:")).count() == 1)
                ((ObjectNode)report.path("requirements").get(0)).putArray("acceptance_items");
            if (mode.equals("serviceRepair") && flow.node.equals("service")
                    && calls.stream().filter(s -> s.startsWith("service:")).count() == 1)
                ((ObjectNode)report.path("checks").get(1)).put("exit_code", 1);
            if (mode.startsWith("service") && flow.node.equals("service_triage")) ((ObjectNode)report).put("route", "service");
            if (node.gate() == TeamDefinition.Gate.TEST_PLAN && mode.equals("inspect")) {
                ((ObjectNode) report.path("scenarios").get(0)).putArray("operation_ids").add(OperationContract.INSPECT_CONTENT);
            } else if (node.gate() == TeamDefinition.Gate.TEST_PLAN
                    && "maven-library".equals(flow.snapshot.definition().id())) {
                ((ObjectNode) report.path("scenarios").get(0)).putArray("operation_ids").add("qa-java");
            }
            boolean firstAutomaticVerification = mode.equals("automaticRepair")
                    && flow.node.equals("verification")
                    && calls.stream().filter(s -> s.startsWith("verification:")).count() == 1;
            boolean firstDirtyVerification = mode.equals("dirtyVerification")
                    && flow.node.equals("verification")
                    && calls.stream().filter(s -> s.startsWith("verification:")).count() == 1;
            if (firstDirtyVerification) workspaceClean = false;
            if (mode.equals("dirtyVerification") && flow.node.equals("implementation") && !workspaceClean) {
                workspaceClean = true; workspaceHead = "head2";
            }
            if (firstAutomaticVerification) {
                flow.transitions = flow.snapshot.definition().maxTransitions();
                for (String id : List.of("triage", "implementation", "verification", "review")) {
                    int limit = flow.snapshot.definition().nodes().get(id).maxVisits();
                    flow.visits.put(id, limit); flow.repeats.put(id, limit);
                }
            }
            if (node.gate() == TeamDefinition.Gate.TEST_PLAN || node.gate() == TeamDefinition.Gate.VERIFICATION)
                ((ObjectNode) report).put("baseline_revision", flow.baselineRevision);
            if (node.gate() == TeamDefinition.Gate.VERIFICATION)
                ((ObjectNode) report).put("plan_revision", Traceability.digest(flow.receipts.get(node.plan())));
            if (flow.node.equals("design")) ((ObjectNode)report).put("route", "ready");
            if (mode.startsWith("protectedRule") && node.gate() == TeamDefinition.Gate.TEST_PLAN)
                ((ObjectNode)report).putArray("protected_path_changes").add(".claude/rules/api-endpoints.md");
            if (mode.startsWith("protectedRule") && flow.node.equals("intake"))
                ((ObjectNode)report).put("summary", "把 `.claude/rules/api-endpoints.md` 第 49 行的 all 改成 admin");
            // #1054：改派段照指示不建置，如實回報 PARTIAL／verify_passed=false。
            if (mode.equals("protectedRulePartial") && flow.node.equals("implementation") && node.role().equals("builder_codex")) {
                ((ObjectNode)report).put("status", "PARTIAL");
                ((ObjectNode)report.path("quality_gate")).put("verify_passed", false);
            }
            if (mode.startsWith("question") && flow.node.equals("intake")) {
                ((ObjectNode)report).put("route", "question").put("summary", "API 共 69 個，文件寫 68 個");
                ((ObjectNode)report).putArray("requirements");
                var outOfScope = ((ObjectNode)report).putArray("out_of_scope");
                if (mode.equals("question")) outOfScope.add("修正文件計數：68 改為 69").add("補上 API 範例");
                if (mode.equals("question")) ((ObjectNode)report).putArray("out_of_scope_titles").add("文件計數改 69").add("補 API 範例");
            }
            if (flow.node.equals("triage")) ((ObjectNode)report).put("route", mode.equals("repair") ? "verification"
                    : mode.equals("dirtyVerification") ? "verification"
                    : mode.equals("automaticRepair") ? "implementation" : mode.equals("change") ? "requirements"
                    : mode.startsWith("replan") ? "plan" : "human");
            if (mode.startsWith("review") && node.gate() == TeamDefinition.Gate.REVIEW)
                ((ObjectNode) report).withArray("findings").addObject()
                        .put("severity", mode.equals("reviewHigh") ? "HIGH" : "INFO").put("detail", "Jackson 版本低於需求");
            if (((mode.equals("blocked") || mode.equals("blockedDecision")) && flow.node.equals("verification")) || firstAutomaticVerification || firstDirtyVerification)
                ((ObjectNode)report).withArray("uncertainties").add("SMTP 無法核實");
            if (mode.equals("blockedDecision") && flow.node.equals("triage"))
                ((ObjectNode)report).set("human_decision", mapper.readTree("{\"headline\":\"驗收發現既有限制\",\"question\":\"smtps 這次要一起支援嗎？\",\"options\":[{\"label\":\"另開任務處理\",\"consequence\":\"照現況完成\"},{\"label\":\"這次一起支援\",\"consequence\":\"重跑實作與驗收\"}],\"recommendation\":\"建議另開任務\",\"also_needed\":[]}"));
            if (mode.startsWith("dispatch") && flow.node.equals("plan"))
                ((ObjectNode) report).putObject("handoff").put("role", mode.equals("dispatchInvalid")
                                || (mode.equals("dispatchFormatOnce") && calls.stream().filter(s -> s.startsWith("plan:")).count() == 1) ? "security_auditor"
                                : mode.equals("dispatchDisplayName") ? "Infrastructure Agent（環境）" : "infrastructure")
                        .put("reason", "環境建置需要此專業").put("instruction", "完成 Compose 與驗證");
            if (mode.equals("dispatchDirty") && flow.node.equals("implementation")) workspaceClean = false;
            if (mode.equals("dispatchQuota") && flow.node.equals("implementation")) {
                store.saveCheckpoint(new Checkpoint("42", flow.node, "codex", "sonnet", "dispatch-session", "context", "head1", "quota"));
                throw new QuotaExceeded("usage_limit_reached");
            }
            JsonNode denials = deniedAt != null && deniedAt.equals(flow.node)
                    ? mapper.readTree("[{\"tool_name\":\"Bash\",\"tool_use_id\":\"t1\",\"tool_input\":{\"command\":\"git show --stat 814c9b1\"}}]")
                    : null;
            if (denials != null && mode.equals("commandFailure")) denials = mapper.readTree("""
                    [{"tool_name":"command_execution","failure_kind":"COMMAND_FAILURE",
                      "tool_input":{"command":"git diff --check"},"reason":"exit_code=2; new blank line at EOF"}]
                    """);
            // 真實流程的修復會產生新 commit、重訂的計畫或需求內容會不同；沿用前次結果（ReceiptReuse）只在輸入完全相同時發生。
            if (mode.equals("automaticRepair") && flow.node.equals("implementation")
                    && calls.stream().anyMatch(s -> s.startsWith("verification:"))) workspaceHead = "head-repair";
            if (mode.equals("replan") && node.gate() == TeamDefinition.Gate.TEST_PLAN && flow.visits.getOrDefault("plan", 0) > 1)
                ((ObjectNode) report.path("scenarios").get(0)).put("method", "補上缺漏情境後的方法");
            if (editBaseline && flow.node.equals("intake") && !flow.baselineHistory.isEmpty())
                ((ObjectNode) report.path("requirements").get(0)).put("description", "寄送郵件並記錄寄送結果");
            if (IssueTracker.enabled(flow)) {
                ObjectNode tracked = (ObjectNode) report;
                tracked.put("issue_revision", IssueTracker.revision(flow));
                tracked.putArray("issue_updates"); tracked.putArray("issue_checks");
                if (node.gate() == TeamDefinition.Gate.REQUIREMENTS) {
                    tracked.put("work_intent", "WORK").put("intent_evidence", "檢查並修好全部問題");
                    for (int i = 1; i <= 9; i++) tracked.withArray("issue_updates").addObject()
                            .put("id", "I" + i).put("description", "problem " + i)
                            .put("scope", mode.startsWith("issueDecision") && i == 9 ? "DECISION" : "REPAIR")
                            .put("evidence", "file:" + i).put("reason", "使用者目標內")
                            .putArray("requirement_ids").add("R1");
                    if (mode.equals("issueQuestion") && calls.stream().filter(s -> s.startsWith("intake:")).count() == 1)
                        tracked.put("route", "question");
                }
                if (Set.of(TeamDefinition.Gate.VERIFICATION, TeamDefinition.Gate.REVIEW, TeamDefinition.Gate.SERVICE).contains(node.gate())) {
                    int count = mode.equals("issueMissing") && flow.node.equals("verification")
                            && calls.stream().filter(s -> s.startsWith("verification:")).count() == 1 ? 1 : 9;
                    for (int i = 1; i <= count; i++) tracked.withArray("issue_checks").addObject()
                            .put("id", "I" + i).put("status", mode.startsWith("issueDecision") && i == 9 ? "OPEN" : "PASS")
                            .put("evidence", "獨立檢查 file:" + i);
                    if (mode.equals("issueDecisionReviewHigh") && node.gate() == TeamDefinition.Gate.REVIEW)
                        ((ObjectNode) report).put("verdict", "REQUEST_CHANGES").withArray("blockers").add("真正的缺陷");
                }
                if (flow.node.equals("triage") && mode.equals("issueMissing")) tracked.put("route", "implementation");
                // #1055：分流停下前補充待決項目的說明（issueDecisionNotes），或改了待修項目的證據（issueDecisionRepairNote）。
                if (flow.node.equals("triage") && Set.of("issueDecisionNotes", "issueDecisionRepairNote").contains(mode)) {
                    boolean decision = mode.equals("issueDecisionNotes");
                    tracked.withArray("issue_updates").addObject().put("id", decision ? "I9" : "I1")
                            .put("description", decision ? "problem 9" : "problem 1").put("scope", decision ? "DECISION" : "REPAIR")
                            .put("evidence", "分流補充：負責人先前核准延後").put("reason", "請負責人決定").putArray("requirement_ids").add("R1");
                }
                if (flow.node.equals("verification") && mode.equals("issueDecisionUncertain"))
                    tracked.withArray("uncertainties").add("前端單元測試被沙箱擋下 localhost");
                // verification 改寫既有 description：issueFormat 只錯一次，issueFormatTwice 兩次都錯。
                long verifications = calls.stream().filter(s -> s.startsWith("verification:")).count();
                if (flow.node.equals("verification") && (mode.equals("issueFormatTwice")
                        || (mode.equals("issueFormat") && verifications == 1)))
                    tracked.withArray("issue_updates").addObject().put("id", "I1").put("description", "problem 1；已決定接受")
                            .put("scope", "REPAIR").put("evidence", "x").put("reason", "y").putArray("requirement_ids").add("R1");
            }
            if (mode.startsWith("consult") && flow.node.equals("plan")) {
                long plans = calls.stream().filter(s -> s.startsWith("plan:")).count();
                boolean bad = mode.equals("consultBadTwice") || (mode.equals("consultBadOnce") && plans == 1);
                if (bad || plans == 1) {
                    store.saveCheckpoint(new Checkpoint("42", "plan", "claude-code", "sonnet", "plan-session", "context", "head1", null));
                    var consult = ((ObjectNode) report).putArray("consult");
                    consult.addObject().put("role", bad ? "security_auditor" : "builder").put("question", "錯誤碼有哪些？").put("why", "反例要用");
                    if (mode.equals("consultTwo") || mode.equals("consultInterrupt"))
                        consult.addObject().put("role", "frontend").put("question", "畫面怎麼顯示？").put("why", "情境要用");
                }
                ((ObjectNode) report).putArray("board_updates").addObject().put("op", "ADD").put("id", "").put("kind", "DECISION")
                        .put("text", "plan-" + plans).put("to", "").putArray("refs");
            }
            if (mode.equals("boardBlocked") && flow.node.equals("verification")) {
                ((ObjectNode) report).withArray("uncertainties").add("SMTP 無法核實");
                ((ObjectNode) report).putArray("board_updates").addObject().put("op", "ADD").put("id", "").put("kind", "RISK")
                        .put("text", "SMTP 環境不可用").put("to", "").putArray("refs");
            }
            return new AgentRuntime.RuntimeResult("session", "", report, 0.1, false, tmp.resolve("log"), "model", denials);
        });
        when(executor.consult(any(), any(), any(), anyInt())).thenAnswer(c -> {
            Consultation.Pending pending = c.getArgument(2); int index = c.getArgument(3);
            consults.add(pending.questions().get(index).role());
            if (mode.equals("consultQuota") && consults.size() == 1) throw new QuotaExceeded("usage_limit_reached");
            if (mode.equals("consultInterrupt") && consults.size() == 1) engine.interrupt(c.getArgument(0), "u");
            return new AgentRuntime.RuntimeResult("consult-session", "", mapper.readTree("{\"answer\":\"回答 "
                    + pending.questions().get(index).question() + "\",\"confidence\":\"HIGH\",\"evidence\":\"src/x.ts\"}"),
                    0.05, false, tmp.resolve("log"), "model", null);
        });
        when(verifier.verify(any(), any())).thenAnswer(c -> {
            FlowRun f = c.getArgument(1);
            return new IndependentVerifier.Receipt(workspaceHead, f.baselineRevision, f.snapshot.revision(), "start", "end", List.of());
        });
        // 說明 agent 以固定後備文字代替，不呼叫模型；專門的行為在 ErrorExplainerTest。
        when(explainer.explain(any(), any(), any(), any())).thenAnswer(c -> ErrorExplainer.fallback(c.getArgument(2)));
        engine = new FlowEngine(catalog, props, store, executor, workspace, slack, audit, publisher, verifier, explainer);
        engine.prepare(task); store.save(task);
    }
    JsonNode report(TeamDefinition.Gate gate) throws Exception {
        String fields = switch (gate) {
            case REPORT -> "\"summary\":\"已確認\",\"route\":\"implementation\"";
            case REQUIREMENTS -> "\"summary\":\"郵件需求\",\"route\":\"implementation\",\"requirements\":[{\"id\":\"R1\",\"kind\":\"FUNCTIONAL\",\"description\":\"寄送郵件\",\"acceptance\":\"正確送達\",\"acceptance_items\":[{\"id\":\"R1-A1\",\"criterion\":\"正確送達\",\"dimension\":\"郵件/成功\"}],\"source\":\"使用者需求\"}],\"out_of_scope\":[],\"open_questions\":[]";
            case IMPLEMENTATION -> "\"status\":\"SUCCESS\",\"summary\":\"實作完成\",\"quality_gate\":{\"verify_passed\":true},\"boundary_conflict\":null";
            case TEST_PLAN -> "\"status\":\"READY\",\"scenarios\":[{\"id\":\"s1\",\"requirement_ids\":[\"R1\"],\"acceptance_ids\":[\"R1-A1\"],\"operation_ids\":[\"test\"],\"counterexample\":\"只檢查返回值會漏掉未送達；測試檢查收件\",\"criterion\":\"c\",\"preconditions\":\"p\",\"test_data\":\"d\",\"method\":\"m\",\"steps\":\"s\",\"expected_result\":\"r\",\"environment\":\"e\"}]";
            case VERIFICATION -> "\"verdict\":\"PASS\",\"verify_passed\":true,\"scenario_results\":[{\"scenario_id\":\"s1\",\"status\":\"PASS\",\"actual_result\":\"ok\",\"evidence\":\"log\",\"command\":\"npm test\"}],\"findings\":[],\"uncertainties\":[]";
            case REVIEW -> "\"verification_status\":\"VERIFIED\",\"confidence\":\"HIGH\",\"verdict\":\"APPROVE\",\"blockers\":[],\"checked\":[\"diff\"],\"findings\":[]";
            case SERVICE -> "\"summary\":\"服務已啟動\",\"target\":\"test-host\",\"service_status\":\"RUNNING\",\"product_code_changed\":false,\"uncertainties\":[],\"checks\":[{\"name\":\"start\",\"operation_id\":\"start\",\"status\":\"PASS\",\"exit_code\":0,\"evidence\":\"start log\",\"acceptance_ids\":[\"R1-A1\"]},{\"name\":\"health\",\"operation_id\":\"health\",\"status\":\"PASS\",\"exit_code\":0,\"evidence\":\"health log\",\"acceptance_ids\":[\"R1-A1\"]}]";
        };
        return mapper.readTree("{\"execution_status\":\"COMPLETED\",\"failure_report\":null," + fields + "}");
    }

    void enableIssueTracking() {
        var flow = store.flow(task.id()).orElseThrow();
        var assets = new java.util.LinkedHashMap<>(flow.snapshot.assets());
        assets.put(IssueTracker.CONTRACT, IssueTracker.INSTRUCTIONS);
        flow.snapshot = new TeamDefinition.Snapshot("tracked", flow.snapshot.definition(), flow.snapshot.repository(), assets);
        store.saveFlow(task.id(), flow);
    }
    void enableAutonomy() throws Exception {
        var flow = store.flow(task.id()).orElseThrow();
        ObjectNode definition = mapper.valueToTree(flow.snapshot.definition());
        ((ObjectNode) definition.path("project")).set("autonomy", mapper.readTree(
                "{\"modelTimeoutRecoveries\":2,\"maxTimeoutSeconds\":3600,\"verificationContinuations\":2,"
                        + "\"requirementChangeOptions\":true,\"channelCommandRouting\":true}"));
        flow.snapshot = new TeamDefinition.Snapshot("autonomy", mapper.treeToValue(definition, TeamDefinition.class),
                flow.snapshot.repository(), flow.snapshot.assets());
        store.saveFlow(task.id(), flow);
    }
    @Test void modelTimeoutResumesTheSameSessionOnceWithLongerLimit() throws Exception {
        enableAutonomy(); mode = "modelTimeout";
        engine.run(task);
        // #1053：實作跑到時限被砍，工作沒遺失；系統沿用原 session 接著做，不必等人按「繼續執行」。
        assertThat(calls.stream().filter("implementation:builder"::equals)).hasSize(2);
        assertThat(resumed).contains("implementation:impl-session");
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        var flow = store.flow("42").orElseThrow();
        assertThat(flow.visits.get("implementation")).isEqualTo(1);
        assertThat(AutonomousRecovery.timeoutSeconds(flow, "implementation",
                flow.snapshot.definition().nodes().get("implementation"))).isEqualTo(3600);
        assertThat(flow.context).contains("系統自動接續");
    }
    @Test void modelTimeoutWithoutAutonomyStillStopsForHuman() {
        mode = "modelTimeout";
        engine.run(task);
        assertThat(calls.stream().filter("implementation:builder"::equals)).hasSize(1);
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
    }
    @Test void modelTimeoutWithoutProgressStopsInsteadOfLooping() throws Exception {
        enableAutonomy(); mode = "modelTimeoutAlways";
        engine.run(task);
        // 兩輪之間工作區沒有任何變化：同一指紋只接續一次，第二次逾時就停給人。
        assertThat(calls.stream().filter("implementation:builder"::equals)).hasSize(2);
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
    }
    @Test void verificationContinuationInsideTheVerifierIsReportedOnce() throws Exception {
        enableAutonomy();
        doAnswer(c -> {
            FlowRun f = c.getArgument(1);
            // 續驗在 IndependentVerifier 內、同一個 clone 完成（見 IndependentVerifierTest）；引擎只負責回報。
            f.systemRetries.put(AutonomousRecovery.VERIFY_KEY + workspaceHead, 1);
            return new IndependentVerifier.Receipt(workspaceHead, f.baselineRevision, f.snapshot.revision(), "start", "end", List.of());
        }).when(verifier).verify(any(), any());
        engine.run(task);
        verify(verifier, times(1)).verify(any(), any());
        verify(slack).post(any(), eq("c"), eq("t"), contains("自動續驗了 1 輪"));
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
    }
    @Test void verificationTimeoutWithoutProgressStillStops() throws Exception {
        enableAutonomy();
        doThrow(new IndependentVerifier.VerificationTimeout(List.of("npm", "ci"), tmp.resolve("check-0.log")))
                .when(verifier).verify(any(), any());
        engine.run(task);
        verify(verifier, times(1)).verify(any(), any());
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
    }
    @Test void issueQuestionMisclassificationRepairsItselfWithoutHuman() {
        enableIssueTracking(); mode = "issueQuestion";
        engine.run(task);
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        assertThat(calls.stream().filter(s -> s.startsWith("intake:")).count()).isEqualTo(2);
        assertThat(store.flow("42").orElseThrow().issueInventory).hasSize(9);
    }
    @Test void issueMissingChecksRouteBackToRepairUntilAllNineAreVerified() {
        enableIssueTracking(); mode = "issueMissing";
        engine.run(task);
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        assertThat(calls.stream().filter(s -> s.startsWith("verification:")).count()).isEqualTo(2);
        var flow = store.flow("42").orElseThrow();
        assertThat(flow.issueEvidence).hasSize(9);
        assertThat(IssueTracker.completionReason(flow)).isNull();
    }

    @Test void issueFormatErrorIsCorrectedOnceWithoutCountingOrHuman() {
        enableIssueTracking(); mode = "issueFormat";
        engine.run(task);
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        var flow = store.flow("42").orElseThrow();
        assertThat(calls.stream().filter(s -> s.startsWith("verification:")).count()).isEqualTo(2);
        assertThat(flow.visits.get("verification")).isEqualTo(1);
        assertThat(flow.issueInventory.get("I1").path("description").asText()).isEqualTo("problem 1");
        assertThat(flow.systemRetries).doesNotContainKey("verification" + IssueTracker.FORMAT_RETRY);
        verify(slack).post(any(), any(), any(), contains("系統已請它自動修正重送（不計次）"));
    }
    @Test void repeatedIssueFormatErrorStopsAfterOneCorrection() {
        enableIssueTracking(); mode = "issueFormatTwice";
        engine.run(task);
        // 第二次仍錯就照原本受阻路線處理，不再自動重送。
        verify(slack, times(1)).post(any(), any(), any(), contains("系統已請它自動修正重送（不計次）"));
        assertThat(calls).contains("verification:tester", "triage:coordinator");
        assertThat(store.flow("42").orElseThrow().issueInventory.get("I1").path("description").asText()).isEqualTo("problem 1");
        verifyNoInteractions(publisher);
    }

    /** 驗收只因待決問題 I9 擋下、換手次數已用完：重現 #1055。 */
    private HumanOptions.Option verificationBlockedOnlyByDecisionAtTheLimit(String scenario) {
        enableIssueTracking(); mode = scenario;
        engine.run(task);
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.AWAIT_HUMAN);
        var flow = store.flow("42").orElseThrow();
        assertThat(flow.blockedFindings).containsKey("verification");
        flow.transitions = flow.snapshot.definition().maxTransitions();
        store.saveFlow("42", flow);
        calls.clear(); clearInvocations(slack, audit);
        return HumanOptions.options(store.find("42").orElseThrow(), store.flow("42").orElseThrow(), true, true, null,
                        new HumanOptions.Workspace(workspaceHead, true)).stream()
                .filter(o -> HumanOptions.ISSUE_DECISION.equals(o.kind())).findFirst().orElseThrow();
    }

    @Test void ownerExcludesThePendingDecisionAndContinuesToReviewEvenAtTheTransitionLimit() {
        var exclude = verificationBlockedOnlyByDecisionAtTheLimit("issueDecision");
        assertThat(exclude.command()).startsWith(OwnerIssueDecision.COMMAND + " I9 " + OwnerIssueDecision.EXCLUDE + " ");
        int limit = store.flow("42").orElseThrow().transitions;
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(store.find("42").orElseThrow(), exclude.command(), "u", jobs::add);
        assertThat(jobs).hasSize(1);
        jobs.getFirst().run();

        // 不回需求階段整輪重跑：沿用同一個 commit 的驗收，只跑審查，停在 PR 核准。
        assertThat(calls).containsExactly("review:security_auditor");
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        var flow = store.flow("42").orElseThrow();
        assertThat(flow.issueInventory.get("I9").path("scope").asText()).isEqualTo("EXCLUDED");
        assertThat(flow.issueInventory.get("I9").path("description").asText()).isEqualTo("problem 9");
        assertThat(flow.issueInventory.get("I9").path("reason").asText()).contains("<@u>").contains("移出本任務範圍");
        assertThat(flow.issueEvidence).containsKey(IssueTracker.OWNER_DECISION + "I9");
        assertThat(flow.receipts).containsKey("verification");
        assertThat(flow.transitions).isEqualTo(limit + 1);
        assertThat(flow.changeAuthorized).isFalse();
        verify(audit).record(eq("42"), eq("u"), eq(AuditLog.ACTION_RECOVERY), contains("裁定問題 I9 移出本任務範圍"));
    }

    @Test void triageNotesOnThePendingDecisionDoNotForceAnotherVerification() {
        var exclude = verificationBlockedOnlyByDecisionAtTheLimit("issueDecisionNotes");
        assertThat(exclude.hint()).contains("沿用已通過的驗收");
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(store.find("42").orElseThrow(), exclude.command(), "u", jobs::add);
        jobs.getFirst().run();

        assertThat(calls).containsExactly("review:security_auditor");
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
    }

    @Test void changedRepairEvidenceAfterVerificationRerunsOnlyTheVerification() {
        var exclude = verificationBlockedOnlyByDecisionAtTheLimit("issueDecisionRepairNote");
        assertThat(exclude.hint()).contains("只重跑「verification」");
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(store.find("42").orElseThrow(), exclude.command(), "u", jobs::add);
        jobs.getFirst().run();

        // 不回需求階段：同一個 commit 只重驗，再審查，停在 PR 核准；換手只多用裁定給的兩步。
        assertThat(calls).containsExactly("verification:tester", "review:security_auditor");
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
    }

    @Test void remainingUncertaintiesAreLeftForTheOwnerPublishCommand() {
        var exclude = verificationBlockedOnlyByDecisionAtTheLimit("issueDecisionUncertain");
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(store.find("42").orElseThrow(), exclude.command(), "u", jobs::add);

        // 未核實事項要由負責人逐項放行：裁定本身不排入執行，改出「開 PR」。
        assertThat(jobs).isEmpty();
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(store.flow("42").orElseThrow().issueInventory.get("I9").path("scope").asText()).isEqualTo("EXCLUDED");
        assertThat(HumanOptions.options(store.find("42").orElseThrow(), store.flow("42").orElseThrow(), true, true, null,
                new HumanOptions.Workspace(workspaceHead, true)))
                .anyMatch(o -> HumanOptions.PUBLISH.equals(o.kind()) && o.hint().contains("未核實事項"));
    }

    @Test void issueDecisionIsRefusedForOthersStaleTokensAndAChangedCommit() {
        var exclude = verificationBlockedOnlyByDecisionAtTheLimit("issueDecision");
        when(props.allowedUsers()).thenReturn(Set.of("u", "someone-else"));
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(store.find("42").orElseThrow(), exclude.command(), "someone-else", jobs::add);
        engine.resume(store.find("42").orElseThrow(), OwnerIssueDecision.COMMAND + " I9 " + OwnerIssueDecision.EXCLUDE + " stale", "u", jobs::add);
        workspaceHead = "head-other";
        assertThat(HumanOptions.options(store.find("42").orElseThrow(), store.flow("42").orElseThrow(), true, true, null,
                new HumanOptions.Workspace(workspaceHead, true))).noneMatch(o -> HumanOptions.ISSUE_DECISION.equals(o.kind()));
        engine.resume(store.find("42").orElseThrow(), exclude.command(), "u", jobs::add);

        assertThat(jobs).isEmpty();
        assertThat(calls).isEmpty();
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(store.flow("42").orElseThrow().issueInventory.get("I9").path("scope").asText()).isEqualTo("DECISION");
    }

    @Test void issueDecisionIsNotOfferedWhileAnotherProblemIsStillOpen() {
        verificationBlockedOnlyByDecisionAtTheLimit("issueDecision");
        var flow = store.flow("42").orElseThrow();
        ((ObjectNode) flow.blockedFindings.get("verification").path("issue_checks").get(0)).put("status", "OPEN");
        store.saveFlow("42", flow);
        assertThat(HumanOptions.options(store.find("42").orElseThrow(), store.flow("42").orElseThrow(), true, true, null,
                new HumanOptions.Workspace(workspaceHead, true))).noneMatch(o -> HumanOptions.ISSUE_DECISION.equals(o.kind()));
    }

    @Test void reviewFailureAfterTheDecisionStopsAgainWithoutMoreBudget() {
        var exclude = verificationBlockedOnlyByDecisionAtTheLimit("issueDecisionReviewHigh");
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(store.find("42").orElseThrow(), exclude.command(), "u", jobs::add);
        jobs.getFirst().run();

        // 前進額度只給一路通過：審查受阻就收回。之後的分流只能用既有的自動交接額度，不會回頭重跑驗收。
        assertThat(calls).containsExactly("review:security_auditor", "triage:coordinator");
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.AWAIT_HUMAN);
        var flow = store.flow("42").orElseThrow();
        assertThat(flow.systemRetries).doesNotContainKey(OwnerIssueDecision.FORWARD_CAP);
        assertThat(flow.automaticHandoffs).isEqualTo(1);
        verifyNoInteractions(publisher);
    }

    void enableService(boolean target) throws Exception {
        var f = store.flow(task.id()).orElseThrow();
        ObjectNode d = mapper.valueToTree(f.snapshot.definition());
        var nodes = (ObjectNode)d.path("nodes");
        ((ObjectNode)nodes.path("intake").path("routes")).put("service", "service");
        ObjectNode service = ((ObjectNode)nodes.path("verification")).deepCopy();
        service.put("role", "builder").put("gate", "SERVICE").put("next", "end").put("blocked", "service_triage");
        service.putNull("plan"); service.put("localTestNetwork", false); service.putArray("requires").add("intake");
        service.putObject("routes"); service.remove("candidateRoles"); service.remove("protectedPathFallback");
        ((com.fasterxml.jackson.databind.node.ArrayNode) service.path("writablePaths")).add("ai-team-sandbox/**");
        nodes.set("service", service);
        ObjectNode triage = ((ObjectNode)nodes.path("triage")).deepCopy();
        triage.putArray("requires").add("intake"); triage.putObject("routes").put("service", "service").put("human", "human");
        triage.put("next", "human"); nodes.set("service_triage", triage);
        if (target) {
            ((ObjectNode)d.path("project")).set("serviceTarget", mapper.readTree("{\"name\":\"test-host\",\"checks\":{\"start\":\"start\",\"health\":\"health\"}}"));
            for (String id : List.of("start", "health"))
                ((ObjectNode)d.path("project").path("operations")).set(id, mapper.readTree("{\"nodes\":[\"service\"],\"directory\":\".\",\"argv\":[\"git\",\"status\"],\"writes\":[]}"));
        }
        var definition = mapper.treeToValue(d, TeamDefinition.class); TeamCatalog.validate(definition);
        f.snapshot = new TeamDefinition.Snapshot("service-version", definition, f.snapshot.repository(), f.snapshot.assets());
        store.saveFlow(task.id(), f);
    }
    @Test void serviceBuildCompletesWithoutQaOrPublication() throws Exception {
        enableService(true); mode = "serviceSuccess"; engine.run(task);
        assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.COMPLETED);
        assertThat(calls).containsExactly("intake:coordinator", "service:builder");
        verifyNoInteractions(verifier, publisher);
    }
    void serviceWrites(String pattern) throws Exception {
        var f = store.flow(task.id()).orElseThrow();
        ObjectNode d = mapper.valueToTree(f.snapshot.definition());
        ((ObjectNode)d.path("nodes").path("service")).putArray("writablePaths").add(pattern);
        f.snapshot = new TeamDefinition.Snapshot("service-version", mapper.treeToValue(d, TeamDefinition.class), f.snapshot.repository(), f.snapshot.assets());
        store.saveFlow(task.id(), f);
    }
    @Test void successfulServiceSavesDeploymentFilesWithoutSecrets() throws Exception {
        enableService(true); serviceWrites("deploy/**"); mode = "serviceSuccess";
        when(props.homePath()).thenReturn(tmp);
        Path ws = tmp.resolve("ws"); Files.createDirectories(ws.resolve("deploy/sandbox"));
        Files.writeString(ws.resolve("deploy/sandbox/start.sh"), "echo start");
        Files.writeString(ws.resolve("deploy/.env.sit"), "SECRET=1");
        when(workspace.pathFor("42")).thenReturn(ws);
        when(workspace.changedPathsSinceBase("42")).thenReturn(List.of("deploy/sandbox/start.sh", "deploy/.env.sit", "src/Product.java"));
        engine.run(task);
        assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.COMPLETED);
        var manifest = mapper.readTree(tmp.resolve("service-context/test-host.files/manifest.json").toFile());
        assertThat(manifest.path("task").asText()).isEqualTo("42");
        assertThat(manifest.path("files")).hasSize(1);
        assertThat(manifest.path("files").get(0).path("path").asText()).isEqualTo("deploy/sandbox/start.sh");
    }
    @Test void serviceRestoresSavedDeploymentFilesAndTellsTheRole() throws Exception {
        enableService(true); serviceWrites("deploy/**"); mode = "serviceSuccess";
        when(props.homePath()).thenReturn(tmp);
        Path previous = tmp.resolve("previous"); Files.createDirectories(previous.resolve("deploy"));
        Files.writeString(previous.resolve("deploy/start.sh"), "echo saved");
        ServiceArtifacts.save(tmp, "test-host", "41", "abc", previous, List.of("deploy/start.sh"), List.of("deploy/**"));
        Path ws = tmp.resolve("ws"); Files.createDirectories(ws);
        when(workspace.pathFor("42")).thenReturn(ws);
        engine.run(task);
        assertThat(Files.readString(ws.resolve("deploy/start.sh"))).isEqualTo("echo saved");
        verify(workspace).commitPaths(eq("42"), eq(List.of("deploy/start.sh")), contains("還原前次部署檔（來源任務 #41）"));
        assertThat(store.flow(task.id()).orElseThrow().context).contains("前次成功部署的部署檔", "deploy/start.sh");
    }
    @Test void serviceTypedTimeoutResumesSuccessfulJobWithoutAnotherVisit() throws Exception {
        enableService(true); mode="serviceOperationTimeout"; engine.run(task);
        assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.COMPLETED);
        assertThat(calls).containsExactly("intake:coordinator","service:builder","service:builder");
        assertThat(store.flow(task.id()).orElseThrow().visits.get("service")).isEqualTo(1);
        verifyNoInteractions(verifier,publisher);
    }
    @Test void serviceTypedTimeoutRequiresConfirmedModelCleanup() throws Exception {
        enableService(true); mode="serviceOperationTimeoutUnknown"; engine.run(task);
        assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(calls).containsExactly("intake:coordinator","service:builder");
        verifyNoInteractions(verifier,publisher);
    }
    @Test void serviceFailureRoutesBackToBuilderAndRechecks() throws Exception {
        enableService(true); mode = "serviceRepair"; engine.run(task);
        assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.COMPLETED);
        assertThat(calls).containsExactly("intake:coordinator", "service:builder", "service_triage:coordinator", "service:builder");
        verifyNoInteractions(verifier, publisher);
    }
    @Test void serviceWithoutTargetStopsBeforeExecution() throws Exception {
        enableService(false); mode = "serviceMissing"; engine.run(task);
        assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(calls).containsExactly("intake:coordinator");
        verifyNoInteractions(verifier, publisher);
    }
    @Test void serviceCannotChangeProductFiles() throws Exception {
        enableService(true); mode = "serviceSuccess";
        when(workspace.auditPaths(eq("42"), anyString())).thenReturn(List.of("src/main/Product.java"));
        engine.run(task);
        assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(store.flow(task.id()).orElseThrow().receipts).doesNotContainKey("service");
    }
    @Test void invalidRequirementDraftIsAutomaticallyRepairedBeforeBaseline() throws Exception {
        mode = "draftRepair"; engine.run(task);
        assertThat(calls.stream().filter(s -> s.startsWith("intake:")).count()).isEqualTo(2);
        assertThat(store.flow(task.id()).orElseThrow().receipts).containsKey("intake");
        assertThat(calls).anyMatch(s -> s.startsWith("implementation:"));
    }

    void enableCollaboration() throws Exception {
        FlowRun f = store.flow("42").orElseThrow();
        f.snapshot = ConsultationTest.collaborative(f.snapshot, 2, 4);
        store.saveFlow("42", f);
    }
    @Test void consultPausesAsksAnotherRoleAndResumesTheSameSessionWithoutCounting() throws Exception {
        enableCollaboration(); mode = "consult";
        engine.run(task);
        var flow = store.flow("42").orElseThrow();
        assertThat(consults).containsExactly("builder");
        assertThat(calls.stream().filter(s -> s.startsWith("plan:")).count()).isEqualTo(2);
        assertThat(resumed).contains("plan:plan-session");
        assertThat(flow.visits.get("plan")).isEqualTo(1);
        assertThat(flow.consultations).singleElement().satisfies(a -> {
            assertThat(a.role()).isEqualTo("builder"); assertThat(a.answer()).contains("回答 錯誤碼有哪些？"); });
        assertThat(flow.consultUsed).isEqualTo(1);
        assertThat(flow.pendingConsult).isNull();
        assertThat(flow.receipts.get("plan").has("consult")).isFalse();
        assertThat(flow.board.values()).extracting(TeamBoard.Entry::text).containsExactly("plan-2");
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        verify(slack).postConfigured(any(), any(), any(), contains("暫停，先向其他角色諮詢"));
        verify(slack).postConfigured(any(), any(), any(), contains("Backend Agent 回覆 QA Agent 的諮詢"));
        verify(slack).post(any(), any(), any(), contains("團隊看板"), any());
    }
    @Test void consultQuotaStopsThenResumeAnswersFirstAndReturnsToTheAsker() throws Exception {
        enableCollaboration(); mode = "consultQuota";
        engine.run(task);
        assertThat(task.state()).isEqualTo(Task.State.WAITING_QUOTA);
        assertThat(store.flow("42").orElseThrow().pendingConsult).isNotNull();
        mode = "normal"; calls.clear();
        List<Runnable> jobs = new ArrayList<>(); engine.resume(task, "繼續", "u", jobs::add); jobs.getFirst().run();
        assertThat(consults).containsExactly("builder", "builder");
        assertThat(calls.getFirst()).isEqualTo("plan:tester");
        var flow = store.flow("42").orElseThrow();
        assertThat(flow.consultations).hasSize(1);
        assertThat(flow.consultUsed).isEqualTo(1);
        assertThat(flow.visits.get("plan")).isEqualTo(1);
        // plan 已以接回的 session 完成並交棒；之後的工作區節點不在本測試範圍
        // （測試替身的 provision 只認原 task 物件，續跑工作拿到的是重新載入的任務）。
        assertThat(flow.receipts).containsKey("plan");
        assertThat(flow.pendingConsult).isNull();
        assertThat(resumed).contains("plan:plan-session");
    }
    @Test void invalidConsultIsCorrectedOnceInTheSameSessionAndNotPutOnTheBoard() throws Exception {
        enableCollaboration(); mode = "consultBadOnce";
        engine.run(task);
        var flow = store.flow("42").orElseThrow();
        assertThat(consults).isEmpty();
        assertThat(calls.stream().filter(s -> s.startsWith("plan:")).count()).isEqualTo(2);
        assertThat(resumed).contains("plan:plan-session");
        assertThat(flow.visits.get("plan")).isEqualTo(1);
        assertThat(flow.board.values()).extracting(TeamBoard.Entry::text).containsExactly("plan-2");
        assertThat(flow.systemRetries).doesNotContainKey("plan" + Consultation.FORMAT_RETRY);
        verify(slack).post(any(), any(), any(), contains("諮詢（consult）格式不對"));
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
    }
    @Test void persistentlyInvalidConsultFollowsTheBlockedRoute() throws Exception {
        enableCollaboration(); mode = "consultBadTwice";
        engine.run(task);
        var flow = store.flow("42").orElseThrow();
        assertThat(consults).isEmpty();
        assertThat(flow.receipts).doesNotContainKey("plan");
        assertThat(flow.board).isEmpty();
        assertThat(flow.lastBlockReason).contains("諮詢請求不合格");
    }
    @Test void interruptBetweenQuestionsStopsBeforeTheNextAnswer() throws Exception {
        enableCollaboration(); mode = "consultInterrupt";
        engine.unitStopper = id -> true;
        engine.run(task);
        var flow = store.flow("42").orElseThrow();
        assertThat(consults).containsExactly("builder");
        assertThat(flow.consultations).hasSize(1);
        assertThat(flow.pendingConsult.questions()).hasSize(2);
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.AWAIT_HUMAN);
    }
    @Test void blockedReportsStillRecordBoardRisks() throws Exception {
        enableCollaboration(); mode = "boardBlocked";
        engine.run(task);
        var flow = store.flow("42").orElseThrow();
        assertThat(flow.board.values()).extracting(TeamBoard.Entry::kind).contains("RISK");
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
    }
    @Test void legacySnapshotIgnoresAStrayConsultField() throws Exception {
        mode = "consult";
        engine.run(task);
        assertThat(consults).isEmpty();
        assertThat(store.flow("42").orElseThrow().board).isEmpty();
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
    }
    void enableAgentDispatch() throws Exception {
        FlowRun f = store.flow("42").orElseThrow();
        ObjectNode d = mapper.valueToTree(f.snapshot.definition());
        ObjectNode roles = (ObjectNode) d.path("roles");
        ObjectNode infra = ((ObjectNode) roles.get("builder")).deepCopy();
        infra.put("name", "Infrastructure Agent").put("capabilities", "環境建置").put("runtime", "codex");
        infra.putNull("botTokenEnv"); infra.putNull("appTokenEnv");
        roles.set("infrastructure", infra);
        ((ObjectNode) roles.get("builder")).put("capabilities", "應用實作");
        ((ObjectNode) d.path("nodes").path("implementation")).putArray("candidateRoles").add("builder").add("infrastructure");
        var definition = mapper.treeToValue(d, TeamDefinition.class); TeamCatalog.validate(definition);
        f.snapshot = new TeamDefinition.Snapshot("dispatch", definition, f.snapshot.repository(), f.snapshot.assets());
        store.saveFlow("42", f);
    }
    @Test void agentDispatchUsesChosenRoleAndKeepsAllGates() throws Exception {
        enableAgentDispatch(); mode = "dispatch";
        engine.run(task);
        assertThat(calls).contains("implementation:infrastructure", "verification:tester", "review:security_auditor")
                .doesNotContain("implementation:builder");
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        assertThat(store.flow("42").orElseThrow().assignments.get("implementation").role()).isEqualTo("infrastructure");
        verifyNoInteractions(publisher);
    }
    @Test void agentDispatchRejectsUnauthorizedRoleBeforeExecution() throws Exception {
        enableAgentDispatch(); mode = "dispatchInvalid";
        engine.run(task);
        assertThat(calls).noneMatch(c -> c.startsWith("implementation:"));
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(store.flow("42").orElseThrow().assignments).isEmpty();
    }
    @Test void handoffDisplayNameIsMappedToTheEligibleRoleId() throws Exception {
        // #1044：判斷正確只是角色欄填了顯示名稱，不該停下來等人。
        enableAgentDispatch(); mode = "dispatchDisplayName";
        engine.run(task);
        assertThat(calls).contains("implementation:infrastructure");
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        verify(audit).record(eq("42"), eq(AuditLog.ACTOR_SYSTEM), eq(AuditLog.ACTION_RECOVERY), contains("對應為唯一合格角色 infrastructure"));
    }
    @Test void unmappableHandoffRoleIsResentOnceWithoutCountingThenFollowsTheBlockedRoute() throws Exception {
        enableAgentDispatch(); mode = "dispatchFormatOnce";
        engine.run(task);
        var flow = store.flow("42").orElseThrow();
        assertThat(calls.stream().filter(s -> s.startsWith("plan:")).count()).isEqualTo(2);
        assertThat(flow.visits.get("plan")).isEqualTo(1);
        assertThat(flow.systemRetries).doesNotContainKey("plan" + AgentDispatch.FORMAT_RETRY);
        assertThat(flow.assignments.get("implementation").role()).isEqualTo("infrastructure");
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        verify(slack).post(any(), any(), any(), contains("交接（handoff）格式不對，系統已請它自動修正重送（不計次）"));
    }
    @Test void persistentlyInvalidHandoffIsResentOnlyOnce() throws Exception {
        // 一直填錯：只重送一次，之後照原受阻路線交給分流／真人。
        enableAgentDispatch(); mode = "dispatchInvalid";
        engine.run(task);
        assertThat(calls.stream().filter(s -> s.startsWith("plan:")).count()).isEqualTo(2);
        verify(slack, times(1)).post(any(), any(), any(), contains("交接（handoff）格式不對"));
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
    }
    @Test void agentDispatchPersistsChoiceAcrossQuotaResume() throws Exception {
        enableAgentDispatch(); mode = "dispatchQuota";
        engine.run(task);
        assertThat(task.state()).isEqualTo(Task.State.WAITING_QUOTA);
        assertThat(store.flow("42").orElseThrow().assignments.get("implementation").role()).isEqualTo("infrastructure");
        mode = "dispatch"; calls.clear();
        List<Runnable> jobs = new ArrayList<>(); engine.resume(task, "繼續", "u", jobs::add); jobs.getFirst().run();
        assertThat(calls.getFirst()).isEqualTo("implementation:infrastructure");
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
    }
    @Test void agentDispatchChecksOperationPermissionBeforeAssignment() throws Exception {
        enableAgentDispatch(); mode = "dispatch";
        doThrow(new IllegalArgumentException("缺少必要操作的工具權限")).when(executor).validateAssignment(any(), any());
        engine.run(task);
        assertThat(calls).noneMatch(c -> c.startsWith("implementation:"));
        assertThat(store.flow("42").orElseThrow().assignments).isEmpty();
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
    }
    @Test void agentDispatchCommitsCodexChangesBeforeVerification() throws Exception {
        enableAgentDispatch(); mode = "dispatchDirty";
        engine.run(task);
        verify(workspace).commitPaths(eq("42"), anyList(), contains("自主派工實作"));
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        assertThat(store.flow("42").orElseThrow().independentEvidence.head()).isEqualTo("head-qa-tests");
    }
    @Test void arbitraryFrontendAndReviewerExecuteFromConfiguration() throws Exception {
        engine.run(task);
        assertThat(calls).containsExactly("intake:coordinator", "plan:tester", "implementation:builder", "frontend:frontend", "verification:tester", "review:security_auditor");
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL); verifyNoInteractions(publisher);
        verify(verifier, times(1)).verify(any(), any());
        assertThat(store.flow(task.id()).orElseThrow().independentEvidence).isNotNull();
    }
    @Test void documentationOnlyTaskVerifiedByContentInspectionReachesPrApproval() {
        // #1010／#1011：純文件修改沒有任何配置操作能驗，整個任務卡在測試計畫。內建內容檢視讓它照完整流程走完。
        mode = "inspect";
        engine.run(task);
        assertThat(calls).containsExactly("intake:coordinator", "plan:tester", "implementation:builder", "frontend:frontend", "verification:tester", "review:security_auditor");
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        assertThat(store.flow(task.id()).orElseThrow().receipts.get("plan").path("scenarios").get(0).path("operation_ids").get(0).asText())
                .isEqualTo(OperationContract.INSPECT_CONTENT);
    }
    @Test void springConfigurationUsesSameEngineWithDifferentRolesAndNodes() {
        engine.run(task);
        assertThat(calls).containsExactly("intake:coordinator", "design:architect", "plan:tester", "implementation:builder", "verification:tester", "review:inspector");
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL); verifyNoInteractions(publisher);
    }
    @Test void springLightRouteSkipsDesignButKeepsEveryContractStage() {
        // #1002：一行文件修改也被補跑設計；精簡路線只略過建議性的設計，測試計畫、驗收與審查照跑。
        mode = "light"; engine.run(task);
        assertThat(calls).containsExactly("intake:coordinator", "plan:tester", "implementation:builder", "verification:tester", "review:inspector");
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL); verifyNoInteractions(publisher);
    }
    @Test void onlyAdvisoryStagesThatNeverRanCountAsBypassed() throws Exception {
        var flow = new FlowRun(TeamCatalog.load(Path.of("teams/maven-library.json"), key -> tmp.resolve("repo").toString()));
        assertThat(FlowGates.bypassed(flow, "design")).isTrue();
        for (String contract : List.of("intake", "plan", "implementation", "verification", "review"))
            assertThat(FlowGates.bypassed(flow, contract)).as(contract).isFalse();
        flow.blockedFindings.put("design", mapper.createObjectNode());
        assertThat(FlowGates.bypassed(flow, "design")).isFalse();
        flow.blockedFindings.clear(); flow.visits.put("design", 0);
        assertThat(FlowGates.bypassed(flow, "design")).isFalse();
    }
    @Test void springDesignThatRanButHasNoValidResultIsStillRedone() {
        // 略過只適用從未執行的設計；跑過卻沒有有效結果時，照舊退回補做。
        var flow = store.flow(task.id()).orElseThrow();
        flow.node = "plan"; flow.visits.put("design", 1);
        store.saveFlow(task.id(), flow);
        engine.run(task);
        assertThat(calls).contains("design:architect");
        verify(slack, atLeastOnce()).post(any(), any(), any(), contains("系統自動退回"));
    }
    @Test void blockedVerificationReallyRoutesToConfiguredCoordinatorAndHuman() {
        mode = "blocked"; engine.run(task);
        assertThat(calls).contains("triage:coordinator").doesNotContain("review:security_auditor");
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN); verifyNoInteractions(publisher);
    }
    @Test void springQaTestsLeftUncommittedAreCommittedBySystemAndReverifiedWithoutTheImplementer() throws Exception {
        // #1019：QA 跑在 Codex，.git 唯讀，補的測試永遠無法自己提交；舊做法每次交給實作節點提交，多繞一整輪。
        mode = "dirtyVerification";

        engine.run(task);

        assertThat(calls).containsSubsequence("verification:tester", "verification:tester", "review:inspector");
        assertThat(calls.stream().filter("implementation:builder"::equals)).hasSize(1);
        verify(workspace).commitPaths(eq("42"), eq(List.of(dirtyPath)), contains("系統代為提交"));
        assertThat(workspaceHead).isEqualTo("head-qa-tests");
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        verify(audit).record(eq("42"), eq(AuditLog.ACTOR_SYSTEM), eq(AuditLog.ACTION_RECOVERY), contains("未消耗自動處理額度"));
        verify(slack).post(any(), eq("c"), eq("t"), contains("系統已代為提交"));
    }
    @Test void springChangesOutsideTheQaWritablePathsAreNeverCommittedBySystem() throws Exception {
        dirtyPath = "base-spring-boot-starter/src/main/java/com/example/MailService.java";
        mode = "dirtyVerification";

        engine.run(task);

        verify(workspace, never()).commitPaths(any(), any(), any());
        // 維持原本的路由：交給具 Git 寫入能力的實作節點處理。
        verify(audit).record(eq("42"), eq(AuditLog.ACTOR_SYSTEM), eq(AuditLog.ACTION_RECOVERY), contains("具 Git 寫入能力"));
    }
    @Test void reviewSuggestionsAreListedForTheOwnerButDoNotBlock() throws Exception {
        var flow = store.flow(task.id()).orElseThrow();
        flow.receipts.put("review", mapper.readTree("{\"verdict\":\"COMMENT\",\"findings\":["
                + "{\"severity\":\"MEDIUM\",\"detail\":\"SC-017 可再多測一位收件人\"},"
                + "{\"severity\":\"LOW\",\"detail\":\"日誌措辭可更一致\"}]}"));

        String text = FlowEngine.reviewSuggestions(flow, 8);

        assertThat(text).contains("審查建議（未擋發布").contains("・建議：SC-017 可再多測一位收件人")
                .contains("・小建議：日誌措辭可更一致");
        assertThat(FlowEngine.reviewSuggestions(new FlowRun(flow.snapshot), 8)).isEmpty();
    }
    @Test void prBodyListsReceiptsAsMarkdownBulletsInsteadOfRawJson() throws Exception {
        var flow = store.flow(task.id()).orElseThrow();
        flow.receipts.clear();
        flow.receipts.put("verification", mapper.readTree("{\"verdict\":\"PASS\",\"summary\":\"全部通過\",\"verify_passed\":true,"
                + "\"scenario_results\":[{\"scenario_id\":\"SC-01\",\"status\":\"PASS\",\"actual_result\":\"寄出成功\\n第二行\","
                + "\"evidence\":\"MailTest\",\"command\":\"mvn -B test\"}],\"findings\":[]}"));
        flow.receipts.put("review", mapper.readTree("{\"verdict\":\"COMMENT\",\"summary\":\"可發布\",\"blockers\":[],"
                + "\"findings\":[{\"severity\":\"LOW\",\"detail\":\"措辭可更一致\"}]}"));
        flow.independentEvidence = new IndependentVerifier.Receipt("abc123", "base1", "rev1",
                "2026-09-26T06:00:00Z", "2026-09-26T06:01:30Z",
                List.of(new IndependentVerifier.CommandResult(List.of("mvn", "-B", "verify"), 0, "/vm/log", "sha9")));

        String body = FlowEngine.prBody(task, flow);

        assertThat(body).doesNotContain("{\"").doesNotContain("Receipt[")
                .contains("## 驗收紀錄").contains("## 系統獨立驗證").contains("## 審查建議")
                .contains("- 小建議：措辭可更一致")
                .contains("- **判定**：✅ `PASS`").contains("- **品質檢查通過**：✅ 是")
                .contains("  - `SC-01` ✅ `PASS`").contains("    - **實際結果**：寄出成功\n      第二行")
                .contains("- 開始：2026-09-26 14:00:00（台北時間）")
                .contains("  - ✅ `mvn -B verify`（exit 0）");
        // 驗收節點依流程順序排在審查之前；空陣列不輸出空標題。
        assertThat(body.indexOf("（verification）")).isLessThan(body.indexOf("（review）"));
        assertThat(body).doesNotContain("**阻礙**").doesNotContain("/vm/log")
                .contains("<details open><summary><b>").contains(" — ✅ PASS</summary>");
    }
    @Test void prBodyStaysUnderGithubLimitWhenReportsAreHuge() throws Exception {
        var flow = store.flow(task.id()).orElseThrow();
        var items = mapper.createArrayNode();
        for (int i = 0; i < 400; i++) items.addObject().put("id", "REQ-" + i).put("description", "很長的需求描述".repeat(40));
        flow.receipts.clear();
        flow.receipts.put("intake", mapper.createObjectNode().put("summary", "需求").set("requirements", items));
        flow.receipts.put("verification", mapper.readTree("{\"verdict\":\"PASS\",\"summary\":\"全部通過\"}"));

        String receipts = PrReport.receipts(flow);

        assertThat(receipts.length()).isLessThan(PrReport.RECEIPTS_LIMIT + 1_000);
        assertThat(receipts).contains("此節點內容較長").contains("全部通過");
    }
    @Test void humanFeedbackResumesConfiguredRepairNotWholePipeline() {
        mode = "blocked"; engine.run(task); calls.clear(); mode = "repair";
        List<Runnable> jobs = new ArrayList<>(); engine.resume(task, "已提供 SMTP 環境", "u", jobs::add); jobs.get(0).run();
        assertThat(calls).containsExactly("triage:coordinator", "verification:tester", "review:security_auditor");
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
    }
    /** #1042：帳號被供應商拒絕時模型沒有做任何工作，次數要還原、停給人、不排額度續跑也不自動重試。 */
    @Test void providerAccessDeniedRestoresCountsAndWaitsForHumanWithoutRetry() throws Exception {
        mode = "accessDenied"; engine.run(task);

        var saved = store.flow("42").orElseThrow();
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(saved.node).isEqualTo("verification");
        assertThat(saved.visits.getOrDefault("verification", 0)).isZero();
        assertThat(saved.activeNode).isNull();
        assertThat(calls.stream().filter(c -> c.startsWith("verification:")).count()).isEqualTo(1);
        verify(explainer).explain(eq(task), any(), any(com.zipe.aiteam.runtime.ProviderAccessDenied.class), any());
        verify(slack).post(any(), any(), any(), contains("已還原，這次不算一次失敗"), any());
        // 停止訊息與狀態卡開頭都是任務目標，對話再長也看得出原本要做什麼。
        verify(slack).post(any(), any(), any(), startsWith("🎯 email failover\n"), any());
        verify(slack, atLeastOnce()).update(any(), any(), any(), startsWith("🎯 email failover\n"), any());
        verifyNoInteractions(publisher);
    }
    @Test void quotaKeepsSnapshotNodeSessionAndOriginalWorkspace() throws Exception {
        mode = "quota"; engine.run(task);
        assertThat(task.state()).isEqualTo(Task.State.WAITING_QUOTA);
        String revision = store.flow("42").orElseThrow().snapshot.revision();
        when(catalog.snapshot()).thenThrow(new AssertionError("續跑不可讀新配置"));
        calls.clear(); mode = "normal"; List<Runnable> jobs = new ArrayList<>();
        engine.resume(task, null, "u", jobs::add); jobs.get(0).run();
        assertThat(calls).containsExactly("verification:tester", "review:security_auditor");
        assertThat(store.flow("42").orElseThrow().snapshot.revision()).isEqualTo(revision);
        verify(workspace, times(1)).provision(any(), any(), any());
        assertThat(resumed).containsExactly("verification:saved-session");
    }
    @Test void workspaceStagesExcludeDeclaredOperationOutputsBeforeAudit() throws Exception {
        engine.run(task);
        // node-web 的固定操作宣告 node_modules、dist、coverage；每個工作區階段開始前都重新排除（#1044）。
        verify(workspace, atLeastOnce()).excludeOutputDirs(eq("42"), eq(Set.of("node_modules", "dist", "coverage", "ai-team-sandbox/.agent-service")));
    }
    // ---- 驗收負責人命令開 PR（#1022：審查 APPROVE 但寫了 INFO，閘門擋下、停在分流、收據已被清掉） ----

    private void reviewBlockedAtTriage(String severity) {
        engine.run(task);
        var flow = store.flow("42").orElseThrow();
        ObjectNode review = (ObjectNode) flow.receipts.remove("review");
        review.putArray("findings").addObject().put("severity", severity).put("detail", "需要資料庫的測試沒有核實");
        flow.blockedFindings.put("review", review);
        flow.heads.remove("review");
        flow.attemptHeads.put("review", workspaceHead);
        flow.node = "triage"; flow.independentEvidence = null; flow.approvalHead = null;
        store.saveFlow("42", flow);
        task.state(Task.State.AWAIT_HUMAN); store.save(task);
        // 與真的 IndependentVerifier 相同：沒有收據就不算通過。
        when(verifier.blockingReason(any(), any())).thenAnswer(c ->
                ((FlowRun) c.getArgument(0)).independentEvidence == null ? "缺少系統獨立驗證收據" : null);
        clearInvocations(verifier, slack, audit);
    }

    @Test void ownerCommandWaivesAnInformationalReviewFindingReverifiesAndPublishes() throws Exception {
        reviewBlockedAtTriage("INFO");
        when(publisher.publish(eq("42"), any(), any(), eq("feature/task42"))).thenReturn(new PullRequestPublisher.Result("feature/task42", "https://example.test/pr/9", 9));
        assertThat(HumanOptions.options(task, store.flow("42").orElseThrow(), true, true, null,
                new HumanOptions.Workspace(workspaceHead, true)))
                .anyMatch(o -> HumanOptions.PUBLISH.equals(o.kind()) && o.hint().contains("由你放行"));
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(task, HumanOptions.PUBLISH_COMMAND, "u", jobs::add);
        assertThat(jobs).hasSize(1);
        jobs.getFirst().run();

        verify(verifier, times(1)).verify(any(), any());
        var body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(publisher).publish(eq("42"), any(), body.capture(), eq("feature/task42"));
        assertThat(body.getValue()).contains("負責人放行的審查項目").contains("原等級 INFO：需要資料庫的測試沒有核實");
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.PR_CREATED);
        var flow = store.flow("42").orElseThrow();
        assertThat(flow.receipts.get("review").path("owner_waiver").path("by").asText()).isEqualTo("u");
        assertThat(flow.receipts.get("review").path("findings").get(0).path("waived_from").asText()).isEqualTo("INFO");
        assertThat(flow.blockedFindings).doesNotContainKey("review");
        verify(audit).record(eq("42"), eq("u"), eq(AuditLog.ACTION_RECOVERY), contains("負責人放行審查"));
    }

    @Test void ownerCannotWaiveASubstantiveReviewProblemOrActForSomeoneElse() throws Exception {
        when(props.allowedUsers()).thenReturn(Set.of("u", "someone-else"));
        reviewBlockedAtTriage("HIGH");
        assertThat(HumanOptions.options(task, store.flow("42").orElseThrow(), true, true, null,
                new HumanOptions.Workspace(workspaceHead, true)))
                .noneMatch(o -> HumanOptions.PUBLISH.equals(o.kind()));
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(task, HumanOptions.PUBLISH_COMMAND, "u", jobs::add);
        engine.resume(task, HumanOptions.PUBLISH_COMMAND, "someone-else", jobs::add);

        assertThat(jobs).isEmpty();
        verifyNoInteractions(publisher);
        verify(slack).post(any(), eq("c"), eq("t"), contains("只有驗收負責人可以命令開 PR"));
        var text = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(slack, atLeastOnce()).post(any(), eq("c"), eq("t"), text.capture(), any());
        assertThat(text.getAllValues()).anySatisfy(t -> assertThat(t).contains("還不能開 PR").contains("不是可以由你放行的情況"));
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(store.flow("42").orElseThrow().blockedFindings).containsKey("review");
    }

    @Test void failedReverificationPublishesNothingAndKeepsTheWaiverUnsaved() throws Exception {
        reviewBlockedAtTriage("INFO");
        doThrow(new IllegalStateException("npm test 失敗")).when(verifier).verify(any(), any());
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(task, HumanOptions.PUBLISH_COMMAND, "u", jobs::add);
        jobs.getFirst().run();

        verifyNoInteractions(publisher);
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.AWAIT_HUMAN);
        var flow = store.flow("42").orElseThrow();
        assertThat(flow.blockedFindings).containsKey("review");
        assertThat(flow.receipts).doesNotContainKey("review");
        assertThat(flow.node).isEqualTo("triage");
    }

    @Test void ownerClosedTaskCanStillBePublishedOnCommand() throws Exception {
        // #1022：先按了「結案，系統外處理」，之後才想讓系統開 PR。
        reviewBlockedAtTriage("INFO");
        task.state(Task.State.ABORTED); store.save(task);
        when(audit.has("42", AuditLog.ACTION_OWNER_CLOSE)).thenReturn(true);
        when(publisher.publish(eq("42"), any(), any(), eq("feature/task42"))).thenReturn(new PullRequestPublisher.Result("feature/task42", "https://example.test/pr/9", 9));
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(task, HumanOptions.PUBLISH_COMMAND, "u", jobs::add);
        jobs.getFirst().run();

        verify(publisher).publish(eq("42"), any(), contains("負責人放行的審查項目"), eq("feature/task42"));
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.PR_CREATED);
    }

    @Test void closedTaskThatFailsTheChecksStaysClosed() throws Exception {
        reviewBlockedAtTriage("INFO");
        task.state(Task.State.ABORTED); store.save(task);
        when(audit.has("42", AuditLog.ACTION_OWNER_CLOSE)).thenReturn(true);
        doThrow(new IllegalStateException("npm test 失敗")).when(verifier).verify(any(), any());
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(task, HumanOptions.PUBLISH_COMMAND, "u", jobs::add);
        jobs.getFirst().run();

        verifyNoInteractions(publisher);
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.ABORTED);
        verify(slack).post(any(), eq("c"), eq("t"), contains("任務維持結案"));
    }

    @Test void plainAbortedTaskCannotBePublished() throws Exception {
        reviewBlockedAtTriage("INFO");
        task.state(Task.State.ABORTED); store.save(task);
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(task, HumanOptions.PUBLISH_COMMAND, "u", jobs::add);

        assertThat(jobs).isEmpty();
        verify(slack).post(any(), eq("c"), eq("t"), contains("由負責人結案的任務才能開 PR"));
    }

    // ---- #1029：驗收全過只剩約定「無法核實」的事項，審查還沒跑 ----

    private void verificationBlockedOnlyByUncertainties() {
        mode = "blocked"; engine.run(task);
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.AWAIT_HUMAN);
        var flow = store.flow("42").orElseThrow();
        assertThat(flow.blockedFindings).containsKey("verification");
        assertThat(flow.receipts).doesNotContainKey("review");
        calls.clear(); clearInvocations(verifier, slack, audit);
    }

    @Test void ownerCommandWaivesUncertaintiesRunsThePendingReviewAndPublishes() throws Exception {
        verificationBlockedOnlyByUncertainties();
        mode = "normal";
        when(publisher.publish(eq("42"), any(), any(), eq("feature/task42"))).thenReturn(new PullRequestPublisher.Result("feature/task42", "https://example.test/pr/7", 7));
        assertThat(HumanOptions.options(store.find("42").orElseThrow(), store.flow("42").orElseThrow(), true, true, null,
                new HumanOptions.Workspace(workspaceHead, true)))
                .anyMatch(o -> HumanOptions.PUBLISH.equals(o.kind()) && o.hint().contains("未核實事項") && o.hint().contains("審查還沒跑過"));
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(store.find("42").orElseThrow(), HumanOptions.PUBLISH_COMMAND, "u", jobs::add);
        jobs.getFirst().run();

        // 審查不能跳過：只補跑審查，通過後不再停在等待核准 PR。
        assertThat(calls).containsExactly("review:security_auditor");
        var body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(publisher).publish(eq("42"), any(), body.capture(), eq("feature/task42"));
        assertThat(body.getValue()).contains("負責人放行的未核實事項").contains("SMTP 無法核實（u 放行）");
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.PR_CREATED);
        var flow = store.flow("42").orElseThrow();
        assertThat(flow.ownerPublishBy).isNull();
        assertThat(flow.receipts.get("verification").path("owner_waiver").path("by").asText()).isEqualTo("u");
        assertThat(flow.receipts.get("verification").path("uncertainties")).isEmpty();
        verify(audit).record(eq("42"), eq("u"), eq(AuditLog.ACTION_RECOVERY), contains("未核實事項"));
        verify(slack).post(any(), eq("c"), eq("t"), contains("將安排審查"));
    }

    @Test void ownerPublishCommandRunsThePendingReviewEvenAtTheTransitionLimit() throws Exception {
        verificationBlockedOnlyByUncertainties();
        var flow = store.flow("42").orElseThrow();
        flow.transitions = flow.snapshot.definition().maxTransitions();
        store.saveFlow("42", flow);
        mode = "normal";
        when(publisher.publish(eq("42"), any(), any(), eq("feature/task42"))).thenReturn(new PullRequestPublisher.Result("feature/task42", "https://example.test/pr/10", 10));
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(store.find("42").orElseThrow(), HumanOptions.PUBLISH_COMMAND, "u", jobs::add);
        jobs.getFirst().run();

        // 開 PR 指令是新的真人輸入：補跑審查只到 PR 為止，不被已用完的換手次數擋住。
        assertThat(calls).containsExactly("review:security_auditor");
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.PR_CREATED);
    }

    @Test void pendingReviewWithOnlyAnInformationalFindingIsWaivedByTheSameCommand() throws Exception {
        verificationBlockedOnlyByUncertainties();
        mode = "reviewInfo";
        when(publisher.publish(eq("42"), any(), any(), eq("feature/task42"))).thenReturn(new PullRequestPublisher.Result("feature/task42", "https://example.test/pr/8", 8));
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(store.find("42").orElseThrow(), HumanOptions.PUBLISH_COMMAND, "u", jobs::add);
        jobs.getFirst().run();

        verify(publisher).publish(eq("42"), any(), contains("原等級 INFO：Jackson 版本低於需求"), eq("feature/task42"));
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.PR_CREATED);
    }

    @Test void pendingReviewWithARealProblemStopsAndForgetsTheCommand() throws Exception {
        verificationBlockedOnlyByUncertainties();
        mode = "reviewHigh";
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(store.find("42").orElseThrow(), HumanOptions.PUBLISH_COMMAND, "u", jobs::add);
        jobs.getFirst().run();

        verifyNoInteractions(publisher);
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(store.flow("42").orElseThrow().ownerPublishBy).isNull();
    }

    @Test void verificationWithAFailedScenarioCannotBeWaived() throws Exception {
        verificationBlockedOnlyByUncertainties();
        var flow = store.flow("42").orElseThrow();
        ((ObjectNode) flow.blockedFindings.get("verification").path("scenario_results").get(0)).put("status", "FAIL");
        store.saveFlow("42", flow);
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(store.find("42").orElseThrow(), HumanOptions.PUBLISH_COMMAND, "u", jobs::add);

        assertThat(jobs).isEmpty();
        verifyNoInteractions(publisher);
        var text = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(slack, atLeastOnce()).post(any(), eq("c"), eq("t"), text.capture(), any());
        assertThat(text.getAllValues()).anySatisfy(t -> assertThat(t).contains("驗收沒有通過").contains("只剩未核實事項"));
    }

    @Test void ownerCommandWhileAwaitingPrApprovalIsTheApproval() throws Exception {
        engine.run(task);
        when(publisher.publish(eq("42"), any(), any(), eq("feature/task42"))).thenReturn(new PullRequestPublisher.Result("feature/task42", "https://example.test/pr/1", 1));
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(task, HumanOptions.PUBLISH_COMMAND, "u", jobs::add);
        jobs.getFirst().run();

        assertThat(task.state()).isEqualTo(Task.State.PR_CREATED);
    }

    static final String PR_URL = "https://github.com/test/example/pull/1";
    static final String SYNC_BASE = "b".repeat(40);
    /** 先走到 PR_CREATED，再把目前配置改成開啟 PR 衝突同步（舊任務快照沒有這兩個欄位）。 */
    private FlowRun publishedAndSyncEnabled(boolean autoPush) throws Exception {
        engine.run(task);
        when(publisher.publish(eq("42"), any(), any(), eq("feature/task42"))).thenReturn(new PullRequestPublisher.Result("feature/task42", PR_URL, 1));
        List<Runnable> jobs = new ArrayList<>(); engine.approve(task, "u", jobs::add); jobs.getFirst().run();
        assertThat(task.state()).isEqualTo(Task.State.PR_CREATED);
        var current = catalog.snapshot();
        ObjectNode definition = mapper.valueToTree(current.definition());
        ((ObjectNode) definition.path("project")).putObject("autonomy").put("pullRequestSyncRounds", 2).put("pullRequestSyncAutoPush", autoPush);
        when(catalog.snapshot()).thenReturn(new TeamDefinition.Snapshot(current.revision(),
                mapper.treeToValue(definition, TeamDefinition.class), current.repository(), current.assets()));
        when(publisher.status(PR_URL)).thenReturn(new PullRequestPublisher.PullRequestState(1, "open", false, false, "dirty", "feature/task42", "head1", "main"));
        when(workspace.mergeBase(eq("42"), any(), eq("main"), eq("head1"))).thenAnswer(c -> {
            workspaceHead = "merged"; return new WorkspaceManager.BaseMerge(SYNC_BASE, List.of("README.md"), false); });
        when(workspace.pathsChangedFromAutoMerge("42", "head1", SYNC_BASE)).thenReturn(List.of("README.md"));
        when(publisher.update("42", "feature/task42", PR_URL, "head1")).thenReturn(new PullRequestPublisher.Result("feature/task42", PR_URL, 1));
        calls.clear();
        // 開 PR 前各輪動手前的基準同步（BaseSync）不算 PR 同步。
        clearInvocations(workspace);
        return store.flow("42").orElseThrow();
    }

    @Test void conflictingPullRequestIsResolvedOnTheOriginalTaskAndPushedBackToTheSamePr() throws Exception {
        FlowRun published = publishedAndSyncEnabled(true);
        // 原任務的整輪換手早已用完（#1055 為 38／24），同步輪仍有自己的固定額度。
        published.transitions = published.snapshot.definition().maxTransitions();
        store.saveFlow("42", published);
        List<Runnable> jobs = new ArrayList<>();

        engine.checkPullRequest(task, null, jobs::add);

        assertThat(jobs).hasSize(1);
        FlowRun syncing = store.flow("42").orElseThrow();
        assertThat(syncing.node).isEqualTo("implementation");
        assertThat(syncing.pullRequestSync.conflicts()).containsExactly("README.md");
        assertThat(syncing.comparisonBase).isEqualTo(SYNC_BASE);
        assertThat(syncing.context).contains("已開 PR 與基準衝突").contains("README.md");
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.RESUME_QUEUED);

        jobs.getFirst().run();

        assertThat(calls).anyMatch(c -> c.startsWith("implementation:")).anyMatch(c -> c.startsWith("verification:"))
                .anyMatch(c -> c.startsWith("review:")).noneMatch(c -> c.startsWith("intake:") || c.startsWith("plan:"));
        verify(workspace).mergeProblem("42", "head1", SYNC_BASE, List.of("README.md"));
        verify(publisher).update("42", "feature/task42", PR_URL, "head1");
        verify(publisher, times(1)).publish(any(), any(), any(), any());
        FlowRun done = store.flow("42").orElseThrow();
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.PR_CREATED);
        assertThat(done.publishedHead).isEqualTo("merged");
        assertThat(done.pullRequestSync).isNull();
        assertThat(done.pullRequestSyncRounds).isEqualTo(1);

        // GitHub 重新判斷後可合併：只通知一次。
        when(publisher.status(PR_URL)).thenReturn(new PullRequestPublisher.PullRequestState(1, "open", false, true, "clean", "feature/task42", "merged", "main"));
        engine.checkPullRequest(store.find("42").orElseThrow(), null, jobs::add);
        engine.checkPullRequest(store.find("42").orElseThrow(), null, jobs::add);
        verify(slack, times(1)).post(any(), eq("c"), eq("t"), contains("GitHub 已確認 PR 不再衝突"));
    }

    @Test void syncRoundSkipsTheProtectedPathDelegationOfTheOriginalPlan() throws Exception {
        // #1055 同步第 1 輪：原計畫的受保護檔案改派段又跑一次，稽核把合併帶進來的基準變更算成越權。
        mode = "protectedRule";
        enableProtectedPathFallback();
        when(workspace.uncommittedPaths("42")).thenReturn(List.of(".claude/rules/api-endpoints.md"), List.of());
        when(workspace.auditPaths(eq("42"), anyString())).thenReturn(List.of(".claude/rules/api-endpoints.md"), List.of());
        String published = publishedAndSyncEnabled(true).publishedHead;   // 系統代為提交改派段後的 commit
        when(publisher.status(PR_URL)).thenReturn(new PullRequestPublisher.PullRequestState(1, "open", false, false, "dirty", "feature/task42", published, "main"));
        when(workspace.mergeBase(eq("42"), any(), eq("main"), eq(published))).thenAnswer(c -> {
            workspaceHead = "merged"; return new WorkspaceManager.BaseMerge(SYNC_BASE, List.of("README.md"), false); });
        when(publisher.update("42", "feature/task42", PR_URL, published)).thenReturn(new PullRequestPublisher.Result("feature/task42", PR_URL, 1));
        List<Runnable> jobs = new ArrayList<>();
        engine.checkPullRequest(task, null, jobs::add);
        // 舊版在同步輪跑過改派段後停下：留下 codex 的 checkpoint，續跑時原角色（claude）不能沿用（#1055 08:33）。
        FlowRun interrupted = store.flow("42").orElseThrow();
        interrupted.activeNode = "implementation"; interrupted.beforeHead = published; store.saveFlow("42", interrupted);
        store.saveCheckpoint(new Checkpoint("42", "implementation", "codex", "opus", "codex-session", "上次的脈絡", published, null));
        jobs.getFirst().run();
        assertThat(calls).contains("implementation:builder").doesNotContain("implementation:builder_codex");
        assertThat(resumed).noneMatch(r -> r.startsWith("implementation:"));
        verify(publisher).update("42", "feature/task42", PR_URL, published);
    }

    @Test void syncWithoutAutoPushWaitsForApprovalAndThenUpdatesInsteadOfOpeningANewPr() throws Exception {
        publishedAndSyncEnabled(false);
        List<Runnable> jobs = new ArrayList<>();
        engine.checkPullRequest(task, null, jobs::add);
        jobs.getFirst().run();
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        verify(slack).post(any(), eq("c"), eq("t"), contains("推回既有 PR"), any());
        verify(publisher, never()).update(any(), any(), any(), any());

        Task waiting = store.find("42").orElseThrow(); jobs.clear();
        engine.approve(waiting, "u", jobs::add); jobs.getFirst().run();
        verify(publisher).update("42", "feature/task42", PR_URL, "head1");
        verify(publisher, times(1)).publish(any(), any(), any(), any());
    }

    @Test void failedMergeCheckNeverPushesBack() throws Exception {
        publishedAndSyncEnabled(true);
        when(workspace.mergeProblem(any(), any(), any(), any())).thenReturn("衝突檔仍有衝突標記：README.md");
        List<Runnable> jobs = new ArrayList<>();
        engine.checkPullRequest(task, null, jobs::add);
        jobs.getFirst().run();
        verify(publisher, never()).update(any(), any(), any(), any());
        assertThat(store.find("42").orElseThrow().state()).isNotEqualTo(Task.State.PR_CREATED);
        assertThat(store.flow("42").orElseThrow().context).contains("系統核對合併結果");
    }

    @Test void pullRequestPushedBySomeoneElseIsReportedOnceAndNeverSynced() throws Exception {
        publishedAndSyncEnabled(true);
        when(publisher.status(PR_URL)).thenReturn(new PullRequestPublisher.PullRequestState(1, "open", false, false, "dirty", "feature/task42", "someone", "main"));
        List<Runnable> jobs = new ArrayList<>();
        engine.checkPullRequest(task, null, jobs::add);
        engine.checkPullRequest(task, null, jobs::add);
        assertThat(jobs).isEmpty();
        verify(workspace, never()).mergeBase(any(), any(), any(), any());
        verify(slack, times(1)).post(any(), eq("c"), eq("t"), contains("不蓋過其他人的修改"));
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.PR_CREATED);
    }

    @Test void mergedPullRequestStopsBeingPolledAndDisabledConfigurationNeverCallsGithub() throws Exception {
        publishedAndSyncEnabled(true);
        when(publisher.status(PR_URL)).thenReturn(new PullRequestPublisher.PullRequestState(1, "closed", true, null, "", "feature/task42", "head1", "main"));
        List<Runnable> jobs = new ArrayList<>();
        engine.checkPullRequest(task, null, jobs::add);
        engine.checkPullRequest(task, null, jobs::add);
        verify(publisher, times(1)).status(PR_URL);
        assertThat(store.flow("42").orElseThrow().pullRequestClosed).isTrue();

        // 手動指令只限需求負責人；未開啟同步的配置不查 GitHub。
        engine.checkPullRequest(task, "stranger", jobs::add);
        FlowRun reopened = store.flow("42").orElseThrow(); reopened.pullRequestClosed = false; store.saveFlow("42", reopened);
        var current = catalog.snapshot();
        ObjectNode definition = mapper.valueToTree(current.definition());
        ((ObjectNode) definition.path("project")).remove("autonomy");
        when(catalog.snapshot()).thenReturn(new TeamDefinition.Snapshot(current.revision(),
                mapper.treeToValue(definition, TeamDefinition.class), current.repository(), current.assets()));
        engine.checkPullRequest(task, "u", jobs::add);
        verify(publisher, times(1)).status(PR_URL);
        verify(slack).post(any(), eq("c"), eq("t"), contains("未開啟 PR 衝突同步"));
        assertThat(jobs).isEmpty();
    }

    @Test void changedHeadAfterApprovalNeverPublishes() throws Exception {
        engine.run(task); when(workspace.headSha("42")).thenReturn("changed");
        List<Runnable> jobs = new ArrayList<>(); engine.approve(task, "u", jobs::add); jobs.get(0).run();
        verifyNoInteractions(publisher); assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
    }
    @Test void approvalCarriesTaskIdentityForActualQueueNotifications() throws Exception {
        // #1019：核准後排在 #1018 後面，Slack 沒有任何訊息，使用者以為按鈕沒作用。
        engine.run(task);
        var busy = new Task("7", "c", "t7", "u", "別的任務"); busy.state(Task.State.STAGE_RUNNING); store.save(busy);
        var queued = new Task("8", "c", "t8", "u", "排隊任務"); queued.state(Task.State.QUEUED); store.save(queued);
        var waiting = new Task("9", "c", "t9", "u", "等人任務"); waiting.state(Task.State.AWAIT_HUMAN); store.save(waiting);
        when(publisher.publish(eq("42"), any(), any(), eq("feature/task42"))).thenReturn(new PullRequestPublisher.Result("feature/task42", "https://example.test/pr/1", 1));
        List<Runnable> jobs = new ArrayList<>(); engine.approve(task, "u", jobs::add);
        verify(slack).post(any(), eq("c"), eq("t"), contains("排隊狀況與開始執行會另行通知"));
        assertThat(jobs.getFirst()).isInstanceOf(com.zipe.aiteam.task.TaskOrchestrator.QueuedTask.class);
        assertThat(((com.zipe.aiteam.task.TaskOrchestrator.QueuedTask) jobs.getFirst()).task().id()).isEqualTo("42");
        jobs.get(0).run();
        assertThat(task.state()).isEqualTo(Task.State.PR_CREATED);
    }
    @Test void approvalDoesNotClaimExecutionBeforeQueueDispatch() throws Exception {
        engine.run(task);
        List<Runnable> jobs = new ArrayList<>(); engine.approve(task, "u", jobs::add);
        verify(slack).post(any(), eq("c"), eq("t"), contains("將安排推分支、開 PR"));
        verify(slack, never()).post(any(), any(), any(), argThat((String t) -> t.startsWith("輪到任務")));
    }
    @Test void onlyWhitelistedHumanCanPublishAndDoubleClickIsAtomic() throws Exception {
        engine.run(task); List<Runnable> jobs = new ArrayList<>(); engine.approve(task, "stranger", jobs::add); assertThat(jobs).isEmpty();
        engine.approve(task, "u", jobs::add); engine.approve(task, "u", jobs::add); assertThat(jobs).hasSize(1);
        when(publisher.publish(eq("42"), any(), any(), eq("feature/task42"))).thenReturn(new PullRequestPublisher.Result("feature/task42", "https://example.test/pr/1", 1));
        jobs.get(0).run(); verify(publisher).publish(eq("42"), any(), any(), eq("feature/task42"));
        assertThat(task.state()).isEqualTo(Task.State.PR_CREATED);
        assertThat(store.flow("42").orElseThrow().prUrl).isEqualTo("https://example.test/pr/1");
        assertThat(store.findUnfinished()).isEmpty();
    }
    @Test void limitedScopeIndexBypassStopsBeforeReview() throws Exception {
        when(workspace.auditPaths(eq("42"), anyString())).thenReturn(List.of("src/email.ts")); engine.run(task);
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN); assertThat(calls).doesNotContain("review:security_auditor"); verifyNoInteractions(publisher);
    }
    @Test void restartUsesPersistedDefinitionEvenIfRoleRemovedFromCurrentConfig() {
        mode = "quota"; engine.run(task);
        FlowRun saved = store.flow("42").orElseThrow();
        assertThat(saved.snapshot.definition().roles()).containsKey("security_auditor");
        when(catalog.enabled()).thenReturn(false); when(catalog.snapshot()).thenThrow(new AssertionError("舊任務不能改用新配置"));
        task.state(Task.State.STAGE_RUNNING); store.save(task); engine.recover(task);
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(store.flow("42").orElseThrow().snapshot.definition().roles()).containsKey("security_auditor");
    }
    @Test void independentFailureStopsBeforeReviewerAndPublication() throws Exception {
        doThrow(new IllegalStateException("exit=1; check-0.log")).when(verifier).verify(any(), any());
        engine.run(task);
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(calls).doesNotContain("review:security_auditor"); verifyNoInteractions(publisher);
    }
    @Test void failedCodeCheckGoesBackToTheImplementerInsteadOfStopping() throws Exception {
        var failure = new IndependentVerifier.CheckFailed(List.of("npm", "test"), 1, "/runs/check-0.log",
                "[ERROR]   MailTimeoutTest.boundsElapsed:142");
        doThrow(failure).doAnswer(c -> {
            FlowRun f = c.getArgument(1);
            return new IndependentVerifier.Receipt(workspaceHead, f.baselineRevision, f.snapshot.revision(), "start", "end", List.of());
        }).when(verifier).verify(any(), any());

        engine.run(task);

        // 程式沒過是實作的責任：系統自己交回去修，修好後重驗，全程不需要人轉達。
        assertThat(calls.stream().filter("implementation:builder"::equals)).hasSize(2);
        assertThat(calls).endsWith("verification:tester", "review:security_auditor");
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        verify(verifier, times(2)).verify(any(), any());
        var saved = store.flow(task.id()).orElseThrow();
        assertThat(saved.blockedFindings).doesNotContainKey("verification");
        assertThat(saved.context).contains("MailTimeoutTest.boundsElapsed:142");
        // #1001：舊文字斷言「不是環境問題」，配置的測試依賴不存在的路徑時實作只能硬回 SUCCESS 或被誤導。
        assertThat(saved.context).doesNotContain("不是環境問題").contains("BLOCKED", "verify_passed 填 false", "記為無法核實");
        verify(slack).post(any(), any(), any(), contains("已自動交回"));
        verifyNoInteractions(publisher);
    }
    @Test void claimedSuccessWithFailedOperationNeverReachesIndependentVerification() throws Exception {
        // #1001：實作自陳成功，但 CLI 紀錄裡配置的測試結束碼 1；以前會先跑 16 分鐘注定失敗的獨立驗證。
        when(executor.lastCommandRuns("42")).thenAnswer(c -> calls.stream().filter("implementation:builder"::equals).count() == 1
                ? List.of(new com.zipe.aiteam.runtime.CommandRuns.CommandRun("npm test", 1))
                : List.of(new com.zipe.aiteam.runtime.CommandRuns.CommandRun("npm test", 0)));

        engine.run(task);

        assertThat(calls).containsSubsequence("implementation:builder", "triage:coordinator").doesNotContain("verification:tester");
        verify(verifier, never()).verify(any(), any());
        assertThat(store.flow("42").orElseThrow().receipts).doesNotContainKey("implementation");
        verify(audit).record(eq("42"), eq(AuditLog.ACTOR_SYSTEM), eq(AuditLog.ACTION_REJECTED), contains("npm test，結束碼 1"));
        assertThat(store.flow("42").orElseThrow().context).contains("系統核對指令紀錄");
    }
    /** 獨立驗證第一次失敗，讓實作節點跑第二輪（工作區已存在）。 */
    private void implementationRunsTwice() throws Exception {
        doThrow(new IndependentVerifier.CheckFailed(List.of(FileComparison.OPERATION), 1, "/runs/files.json", "README.md 被誤改"))
                .doAnswer(c -> {
                    FlowRun f = c.getArgument(1);
                    return new IndependentVerifier.Receipt(workspaceHead, f.baselineRevision, f.snapshot.revision(), "start", "end", List.of());
                }).when(verifier).verify(any(), any());
    }
    @Test void implementationMergesTheLatestBaseBeforeEachLaterRound() throws Exception {
        implementationRunsTwice();
        when(workspace.baseSha("42")).thenReturn("head1");
        when(workspace.mergeBase(eq("42"), any(), eq("main"), eq("head1"))).thenAnswer(c -> {
            workspaceHead = "synced"; return new WorkspaceManager.BaseMerge(SYNC_BASE, List.of(), false); });

        engine.run(task);

        assertThat(calls.stream().filter("implementation:builder"::equals)).hasSize(2);
        // 剛建好的工作區不重複同步；第二輪動手前才合併，之後的節點基準沒有再前進。
        verify(workspace, times(1)).mergeBase(eq("42"), any(), eq("main"), eq("head1"));
        FlowRun saved = store.flow(task.id()).orElseThrow();
        assertThat(saved.comparisonBase).isEqualTo(SYNC_BASE);
        assertThat(saved.context).contains("動手前已同步最新基準").contains(SYNC_BASE);
        assertThat(saved.baseSync).isNull();
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
    }
    @Test void baseConflictBeforeARoundIsResolvedByThatRoundAndCheckedBySystem() throws Exception {
        implementationRunsTwice();
        when(workspace.mergeBase(eq("42"), any(), eq("main"), eq("head1"))).thenAnswer(c -> new WorkspaceManager.BaseMerge(SYNC_BASE, List.of("README.md"), false));
        // 角色完成合併 commit 後 HEAD 已包含基準，下一個實作節點不會再有東西要合併。
        when(workspace.syncProblem("42", "head1", SYNC_BASE, List.of("README.md"))).thenAnswer(c -> { workspaceHead = "resolved"; return null; });

        engine.run(task);

        verify(workspace, times(1)).mergeBase(eq("42"), any(), eq("main"), eq("head1"));
        verify(workspace).finishMerge(eq("42"), eq(List.of("README.md")), contains("動手前同步"));
        verify(workspace).syncProblem("42", "head1", SYNC_BASE, List.of("README.md"));
        verify(slack).post(any(), eq("c"), eq("t"), contains("動手前已同步最新的 main，有衝突：README.md"));
        FlowRun saved = store.flow(task.id()).orElseThrow();
        assertThat(saved.context).contains("動手前同步最新基準，有衝突").contains("README.md");
        assertThat(saved.comparisonBase).isNotEqualTo(SYNC_BASE);   // 比較起點不是工作區記錄的基準（例如承接任務）時不改
        assertThat(saved.baseSync).isNull();
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
    }
    @Test void unresolvedBaseConflictBlocksTheRoundAndIsNeverMergedAgain() throws Exception {
        implementationRunsTwice();
        when(workspace.mergeBase(eq("42"), any(), eq("main"), eq("head1")))
                .thenAnswer(c -> new WorkspaceManager.BaseMerge(SYNC_BASE, List.of("README.md"), false));
        when(workspace.syncProblem(any(), any(), any(), any())).thenReturn("衝突檔仍有衝突標記：README.md");

        engine.run(task);

        verify(workspace, times(1)).mergeBase(eq("42"), any(), eq("main"), eq("head1"));
        verify(audit).record(eq("42"), eq(AuditLog.ACTOR_SYSTEM), eq(AuditLog.ACTION_REJECTED), contains("動手前同步最新基準的合併核對未通過"));
        FlowRun saved = store.flow(task.id()).orElseThrow();
        assertThat(saved.context).contains("系統核對合併結果");
        assertThat(saved.baseSync).isNotNull();
        assertThat(task.state()).isNotEqualTo(Task.State.AWAIT_PR_APPROVAL);
    }
    @Test void failedBaseSyncDoesNotStopTheRoundButTellsTheRole() throws Exception {
        implementationRunsTwice();
        when(workspace.mergeBase(eq("42"), any(), eq("main"), eq("head1")))
                .thenThrow(new WorkspaceManager.WorkspaceException("git 失敗（結束碼 128）：git fetch"));

        engine.run(task);

        assertThat(calls.stream().filter("implementation:builder"::equals)).hasSize(2);
        verify(workspace, atLeastOnce()).abortMerge("42");
        assertThat(store.flow(task.id()).orElseThrow().context).contains("這一輪沒有同步最新基準").contains("git fetch");
        verify(audit, atLeastOnce()).record(eq("42"), eq(AuditLog.ACTOR_SYSTEM), eq(AuditLog.ACTION_ERROR), contains("動手前同步最新的 main 失敗"));
    }
    @Test void changedProtectedOldFileReturnsToImplementationWithoutChangingRequirementsOrBaseline() throws Exception {
        doThrow(new IndependentVerifier.CheckFailed(List.of(FileComparison.OPERATION), 1, "/runs/files.json", "README.md 被誤改"))
                .doAnswer(c -> {
                    FlowRun f = c.getArgument(1);
                    return new IndependentVerifier.Receipt(workspaceHead, f.baselineRevision, f.snapshot.revision(), "start", "end", List.of());
                }).when(verifier).verify(any(), any());
        engine.run(task);
        assertThat(calls.stream().filter("implementation:builder"::equals)).hasSize(2);
        assertThat(calls.stream().filter(s -> s.startsWith("intake:"))).hasSize(1);
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        FlowRun saved = store.flow(task.id()).orElseThrow();
        assertThat(saved.comparisonBase).isNotBlank();
        assertThat(saved.context).contains("README.md 被誤改", "系統比對指定舊檔");
        assertThat(saved.baselineHistory).isEmpty();
        verifyNoInteractions(publisher);
    }
    @Test void interruptStopsOnlyThisRoundAndResumeContinuesTheSameSession() throws Exception {
        List<String> stopped = new ArrayList<>();
        engine.unitStopper = id -> { stopped.add(id); return true; };
        mode = "interrupt";

        engine.run(task);

        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(stopped).containsExactly("42");
        var flow = store.flow("42").orElseThrow();
        assertThat(flow.node).isEqualTo("implementation");
        assertThat(flow.activeNode).isEqualTo("implementation");
        assertThat(flow.visits.get("implementation")).isEqualTo(1);
        assertThat(flow.systemRetries).doesNotContainKey("implementation");
        assertThat(calls.stream().filter(s -> s.startsWith("implementation:"))).containsExactly("implementation:builder");
        verify(audit, never()).record(eq("42"), eq(AuditLog.ACTOR_SYSTEM), eq(AuditLog.ACTION_ERROR), anyString());
        verify(slack).post(any(), eq("c"), eq("t"), contains("已依 <@u> 的要求中斷「implementation」"), any());

        List<Runnable> jobs = new ArrayList<>();
        engine.resume(task, "續跑", "u", jobs::add); jobs.forEach(Runnable::run);

        assertThat(resumed).startsWith("implementation:kept-session");
        assertThat(store.flow("42").orElseThrow().visits.get("implementation")).isEqualTo(1);
    }
    @Test void interruptedVerificationIsNotSentBackToTheImplementer() throws Exception {
        engine.unitStopper = id -> true;
        doAnswer(c -> {
            engine.interrupt(c.getArgument(0), "u");
            throw new IndependentVerifier.CheckFailed(List.of("npm", "test"), 143, "/runs/check-0.log", "Terminated");
        }).when(verifier).verify(any(), any());

        engine.run(task);

        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(calls.stream().filter("implementation:builder"::equals)).hasSize(1);
        verify(slack, never()).post(any(), any(), any(), contains("已自動交回"));
        verify(verifier, times(1)).verify(any(), any());
    }
    @Test void interruptIsIgnoredWhenNothingIsRunning() throws Exception {
        List<String> stopped = new ArrayList<>();
        engine.unitStopper = id -> { stopped.add(id); return true; };

        engine.interrupt(task, "u");

        assertThat(stopped).isEmpty();
        verify(slack).post(any(), eq("c"), eq("t"), contains("目前沒有正在執行的一輪"));
        engine.run(task);
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
    }
    @Test void runtimeFailureIsExplainedInPlainWordsInsteadOfAnExceptionDump() throws Exception {
        when(explainer.explain(any(), any(), any(), any())).thenReturn("*發生了什麼*\nagent 在限定的步數內沒做完這一步。");
        doThrow(new AgentRuntime.RuntimeExecutionException("已確認：Claude Code 回報 error_max_turns，結束碼 1"))
                .when(executor).execute(any(), any(), any());

        engine.run(task);

        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
        var text = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(slack, atLeastOnce()).post(any(), eq("c"), eq("t"), text.capture(), any());
        assertThat(text.getAllValues()).anySatisfy(t -> assertThat(t)
                .contains("agent 在限定的步數內沒做完這一步").doesNotContain("RuntimeExecutionException"));
        // 說明只能引用這次停止訊息真的會提供的選項。
        verify(explainer).explain(eq(task), contains("需求整理"), any(AgentRuntime.RuntimeExecutionException.class),
                argThat(options -> options != null && options.stream().anyMatch(o -> o.startsWith("告訴它還缺什麼："))));
    }
    @Test void resultReturnedAfterAnInterruptIsNotUsed() throws Exception {
        // #1019：Codex 被停下時以結束碼 0 交出開頭一句「我會先核對…」，被當成正式計畫而顯示「沒有情境」。
        // 無法分辨剛好做完或被截斷，一律不採用，停在原節點並明講是依要求中斷。
        engine.unitStopper = id -> true;
        mode = "interruptAfter";

        engine.run(task);

        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(calls).last().isEqualTo("implementation:builder");
        var flow = store.flow("42").orElseThrow();
        assertThat(flow.receipts).doesNotContainKey("implementation");
        assertThat(flow.node).isEqualTo("implementation");
        assertThat(flow.activeNode).isEqualTo("implementation");
        verify(slack).post(any(), eq("c"), eq("t"), contains("已依 <@u> 的要求中斷「implementation」"), any());
    }
    @Test void interruptDuringASuccessfulIndependentCheckKeepsTheReceiptButDoesNotStartTheTester() throws Exception {
        engine.unitStopper = id -> true;
        doAnswer(c -> {
            engine.interrupt(c.getArgument(0), "u");
            FlowRun f = c.getArgument(1);
            return new IndependentVerifier.Receipt(workspaceHead, f.baselineRevision, f.snapshot.revision(), "start", "end", List.of());
        }).when(verifier).verify(any(), any());

        engine.run(task);

        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(calls).noneMatch(c -> c.startsWith("verification:"));
        assertThat(store.flow("42").orElseThrow().independentEvidence).isNotNull();
    }
    @Test void requirementOwnerCanStartARequirementChangeWhileStoppedAtThePlan() {
        // #1019：需求裡有一條測不了的流程要求（REQ-016），計畫受阻直接停給人；沒有 PM 提議時舊版沒有任何路可以改需求。
        task.state(Task.State.AWAIT_HUMAN); store.save(task);
        var flow = store.flow(task.id()).orElseThrow();
        flow.node = "plan"; flow.activeNode = null; flow.baselineRevision = "baseline-1";
        store.saveFlow(task.id(), flow);
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(task, "核准需求變更 移除 REQ-016：版本追溯由系統負責，不是產品需求", "someone-else", jobs::add);
        assertThat(jobs).isEmpty();

        when(props.allowedUsers()).thenReturn(Set.of("u", "someone-else"));
        engine.resume(task, "核准需求變更 移除 REQ-016：版本追溯由系統負責，不是產品需求", "someone-else", jobs::add);
        assertThat(jobs).isEmpty();
        verify(slack).post(any(), eq("c"), eq("t"), contains("只有這個任務的需求負責人"));

        engine.resume(task, "核准需求變更 移除 REQ-016：版本追溯由系統負責，不是產品需求", "u", jobs::add);

        var changed = store.flow(task.id()).orElseThrow();
        assertThat(jobs).hasSize(1);
        assertThat(changed.node).isEqualTo("intake");
        assertThat(changed.changeAuthorized).isTrue();
        assertThat(changed.changeTarget).isNull();
        assertThat(changed.context).contains("移除 REQ-016");
        verify(audit).record(eq("42"), eq("u"), eq(AuditLog.ACTION_STATE), contains("核准需求變更"));
    }
    @Test void requirementChangeBeforeAnyBaselineIsExplainedInsteadOfQueued() {
        task.state(Task.State.AWAIT_HUMAN); store.save(task);
        var flow = store.flow(task.id()).orElseThrow();
        flow.node = "plan"; flow.baselineRevision = null; store.saveFlow(task.id(), flow);
        List<Runnable> jobs = new ArrayList<>();

        engine.resume(task, "核准需求變更 移除 R1", "u", jobs::add);

        assertThat(jobs).isEmpty();
        assertThat(store.flow(task.id()).orElseThrow().node).isEqualTo("plan");
        verify(slack).post(any(), eq("c"), eq("t"), contains("還沒有確定的需求清單"));
    }
    @Test void newConfigurationSendsABlockedTestPlanToTriageFirst() {
        for (String team : List.of("teams/maven-library.json", "teams/node-web.json")) {
            var definition = assertDoesNotThrowLoad(team);
            definition.nodes().forEach((id, node) -> {
                if (node.gate() == TeamDefinition.Gate.TEST_PLAN) assertThat(node.blocked()).as(team + " " + id).isEqualTo("triage");
            });
        }
    }
    private TeamDefinition assertDoesNotThrowLoad(String team) {
        try { return TeamCatalog.load(Path.of(team), key -> tmp.resolve("repo").toString()).definition(); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    @Test void missingPrerequisiteResultSendsTheFlowBackInsteadOfFailing() {
        // #1016／#1017：分流把流程送到驗收，實作的報告卻沒被採用；舊版每次都丟 IllegalStateException。
        var flow = store.flow(task.id()).orElseThrow();
        flow.node = "verification";
        store.saveFlow(task.id(), flow);

        engine.run(task);

        assertThat(calls.getFirst()).isNotEqualTo("verification:tester");
        assertThat(calls).endsWith("verification:tester", "review:security_auditor");
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        verify(slack, atLeastOnce()).post(any(), any(), any(), contains("系統自動退回"));
        verifyNoInteractions(publisher);
    }
    @Test void publishFailureStaysAwaitingPrApprovalWithOriginalReasonAndCanBeApprovedAgain() throws Exception {
        // #1015：token 沒設定導致開 PR 失敗後轉成 AWAIT_HUMAN，白話改寫又藏掉了原因，補好 token 也沒有按鈕可以再核准。
        engine.run(task);
        when(publisher.publish(eq("42"), any(), any(), eq("feature/task42")))
                .thenThrow(new PullRequestPublisher.PublishException("未設定 AI_TEAM_GITHUB_TOKEN，無法推分支或開 PR。請在 ~/ai-team/env.sh 加入該變數後重啟服務"))
                .thenReturn(new PullRequestPublisher.Result("feature/task42", "https://example.test/pr/2", 2));
        List<Runnable> jobs = new ArrayList<>(); engine.approve(task, "u", jobs::add); jobs.removeFirst().run();

        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        verify(slack).post(any(), any(), any(), contains("未設定 AI_TEAM_GITHUB_TOKEN"), any());
        verify(explainer, never()).explain(any(), any(), any(), any());

        engine.approve(store.find("42").orElseThrow(), "u", jobs::add); jobs.removeFirst().run();
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.PR_CREATED);
    }
    @Test void stoppedAtApprovalCanReenterPrApprovalAfterEvidenceIsBack() throws Exception {
        engine.run(task); String head = workspace.headSha("42");
        when(workspace.headSha("42")).thenReturn("changed");
        List<Runnable> jobs = new ArrayList<>(); engine.approve(task, "u", jobs::add); jobs.removeFirst().run();
        Task stopped = store.find("42").orElseThrow();
        assertThat(stopped.state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(HumanOptions.options(stopped, store.flow("42").orElseThrow(), true, false, null, null))
                .extracting(HumanOptions.Option::label).contains("重新進入核准 PR").doesNotContain("繼續執行");

        when(workspace.headSha("42")).thenReturn(head); calls.clear();
        engine.resume(stopped, "續跑", "u", jobs::add); jobs.removeFirst().run();
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        assertThat(calls).isEmpty(); verifyNoInteractions(publisher);
    }
    @Test void corruptedEvidenceAfterApprovalNeverPublishes() throws Exception {
        engine.run(task); when(verifier.blockingReason(any(), anyString())).thenReturn("log 雜湊不符");
        List<Runnable> jobs = new ArrayList<>(); engine.approve(task, "u", jobs::add); jobs.get(0).run();
        verifyNoInteractions(publisher); assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
    }
    /** #1055：需求變更核准後基準內容沒變、commit 也沒變，計畫與實作直接沿用，只重跑驗收與審查。 */
    @Test void unchangedBaselineAfterOwnerApprovalReusesPlanAndImplementation() {
        mode = "blocked"; engine.run(task); mode = "change";
        List<Runnable> jobs = new ArrayList<>(); engine.resume(task, "需要重新確認需求", "u", jobs::add); jobs.removeFirst().run();
        calls.clear(); clearInvocations(audit);
        mode = "normal"; engine.resume(store.find("42").orElseThrow(), "核准需求變更 內容不變，只是確認", "u", jobs::add); jobs.getFirst().run();
        assertThat(calls).containsExactly("intake:coordinator", "verification:tester", "review:security_auditor");
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        verify(audit).record(eq("42"), eq(AuditLog.ACTOR_SYSTEM), eq(AuditLog.ACTION_RECOVERY), contains("沿用節點 implementation 前次結果"));
        assertThat(store.flow("42").orElseThrow().priorReceipts).doesNotContainKeys("plan", "implementation", "frontend");
    }

    /** 計畫重訂後內容沒變：實作的輸入與 commit 都相同，不重跑實作。 */
    @Test void identicalReplanSkipsImplementationButNewCommitDoesNot() {
        mode = "blocked"; engine.run(task); mode = "replanSame"; calls.clear();
        List<Runnable> jobs = new ArrayList<>(); engine.resume(task, "測試計畫缺漏", "u", jobs::add); jobs.getFirst().run();
        assertThat(calls).containsExactly("triage:coordinator", "plan:tester", "verification:tester", "review:security_auditor");

        // 沿用要求同一個 commit：HEAD 改變就照常執行。
        var flow = store.flow("42").orElseThrow();
        ReceiptReuse.retire(flow, "implementation");
        TeamDefinition.Node implementation = flow.snapshot.definition().nodes().get("implementation");
        assertThat(ReceiptReuse.reusable(flow, "implementation", implementation, workspaceHead, true)).isNotNull();
        assertThat(ReceiptReuse.reusable(flow, "implementation", implementation, "other-head", true)).isNull();
        assertThat(ReceiptReuse.reusable(flow, "implementation", implementation, workspaceHead, false)).isNull();
        assertThat(ReceiptReuse.reusable(flow, "triage", flow.snapshot.definition().nodes().get("triage"), workspaceHead, true)).isNull();
    }

    @Test void planRepairRerunsImplementationAndVerification() {
        mode = "blocked"; engine.run(task); mode = "replan"; calls.clear();
        List<Runnable> jobs = new ArrayList<>(); engine.resume(task, "測試計畫缺漏", "u", jobs::add); jobs.getFirst().run();
        assertThat(calls).containsExactly("triage:coordinator", "plan:tester", "implementation:builder", "frontend:frontend", "verification:tester", "review:security_auditor");
    }
    @Test void baselineChangeRequiresExplicitOwnerApprovalAndKeepsHistory() {
        editBaseline = true;
        mode = "blocked"; engine.run(task); mode = "change";
        List<Runnable> jobs = new ArrayList<>(); engine.resume(task, "需要重新確認需求", "u", jobs::add); jobs.removeFirst().run();
        Task saved = store.find("42").orElseThrow();
        assertThat(saved.state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(store.flow("42").orElseThrow().changeTarget).isEqualTo("intake");
        calls.clear(); engine.resume(saved, "好", "u", jobs::add); assertThat(jobs).isEmpty();
        engine.resume(saved, "核准需求變更 修正寄送驗收條件", "stranger", jobs::add); assertThat(jobs).isEmpty();
        mode = "normal"; engine.resume(saved, "核准需求變更 修正寄送驗收條件", "u", jobs::add); jobs.getFirst().run();
        assertThat(calls).containsExactly("intake:coordinator", "plan:tester", "implementation:builder", "frontend:frontend", "verification:tester", "review:security_auditor");
        assertThat(store.flow("42").orElseThrow().baselineHistory).hasSize(1);
    }

    /**
     * 交接通知在改成配置式引擎後整批漏掉了。這兩個測試釘住的是：
     * 換角色時要以「上一棒的身分」發言並 tag 下一棒，受阻退回時也一樣。
     */
    @Test void springHandoffTagsNextRoleWithPreviousRoleIdentity() {
        when(slack.mention(any())).thenAnswer(c -> "@" + c.getArgument(0));
        engine.run(task);
        var texts = org.mockito.ArgumentCaptor.forClass(String.class);
        var roles = org.mockito.ArgumentCaptor.forClass(TeamDefinition.Role.class);
        verify(slack, atLeastOnce()).postConfigured(roles.capture(), any(), any(), texts.capture());
        assertThat(texts.getAllValues()).anyMatch(t -> t.contains("@ARCHITECT") && t.contains("換你接手"));
        assertThat(texts.getAllValues()).anyMatch(t -> t.contains("@INSPECTOR"));
        // 交接是以上一棒的身分發的，不是系統代發
        assertThat(roles.getAllValues()).extracting(TeamDefinition.Role::botTokenEnv).doesNotContainNull();
    }

    @Test void blockedHandoffTagsTheRoleThatTakesOverAndTheOwner() {
        mode = "blocked";
        when(slack.mention(any())).thenAnswer(c -> "@" + c.getArgument(0));
        engine.run(task);
        var texts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(slack, atLeastOnce()).postConfigured(any(), any(), any(), texts.capture());
        assertThat(texts.getAllValues()).anyMatch(t -> t.contains("@COORDINATOR") && t.contains("受阻"));
        // 停下等人時要 tag 需求提出者，否則沒有人知道輪到自己
        var system = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(slack, atLeastOnce()).post(any(), any(), any(), system.capture());
        assertThat(system.getAllValues()).anyMatch(t -> t.contains("<@u>"));
    }
}
