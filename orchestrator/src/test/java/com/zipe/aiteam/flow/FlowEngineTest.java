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
                "automaticHandoffKeys","automaticHandoffExtensionKeys","blockedFindings"));
        FlowRun restored=mapper.treeToValue(json,FlowRun.class);
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

    @Test void deniedToolTurnsAnOtherwiseCleanReportIntoABlock() throws Exception {
        // #1014：coordinator 沒有 Bash，triage 卻回報查核完成並直接分流。
        deniedAt = "intake";
        engine.run(task);

        var flow = store.flow(task.id()).orElseThrow();
        assertThat(flow.blockedFindings).containsKey("intake");
        assertThat(flow.receipts).doesNotContainKey("intake");
        assertThat(flow.lastBlockReason).contains("本輪有工具呼叫遭拒").contains("Bash");
        assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.AWAIT_HUMAN);
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
        blocked.visits.put("verification",blocked.snapshot.definition().nodes().get("verification").maxVisits());
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
        flow.node="triage";flow.visits.put("verification",3);
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
        flow.node="triage";flow.visits.put("verification",3);
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
        verify(slack).post(any(),any(),any(),argThat((String t)->t.contains("本次未呼叫任何模型")&&t.contains("已還原")));
        verifyNoInteractions(publisher);
    }
    @Test void retryCapDoesNotConsumeAnotherVisitOrHideOriginalFailure() {
        when(props.briefReports()).thenReturn(true);
        var flow = store.flow(task.id()).orElseThrow();
        int limit = flow.snapshot.definition().nodes().get(flow.node).maxVisits();
        flow.visits.put(flow.node, limit); flow.transitions = 9;
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
        flow.visits.put("triage", flow.snapshot.definition().nodes().get("triage").maxVisits());
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
        flow.visits.put("triage", flow.snapshot.definition().nodes().get("triage").maxVisits());
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
        flow.visits.put("triage", flow.snapshot.definition().nodes().get("triage").maxVisits());
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
        flow.visits.put("triage", flow.snapshot.definition().nodes().get("triage").maxVisits());
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
    @Test void sameBlockedIncidentCanTraverseConfiguredNodesButStillHasHopLimit() {
        var flow = store.flow(task.id()).orElseThrow();
        flow.node = "triage"; flow.automaticHandoff = "same-incident";
        flow.automaticHandoffKeys.add("incident:same-incident");
        flow.automaticHandoffs = 4; flow.automaticHandoffHops = AutomaticHandoff.HOP_LIMIT;
        flow.visits.put("triage", flow.snapshot.definition().nodes().get("triage").maxVisits());
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
        waiting.visits.put("triage", 3); store.saveFlow(task.id(), waiting);
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
    FlowEngine engine;
    Task task = new Task("42", "c", "t", "u", "email failover");
    List<String> calls = new ArrayList<>();
    List<String> resumed = new ArrayList<>();
    String mode = "normal";
    String deniedAt;
    boolean provisioned;
    boolean workspaceClean;
    String workspaceHead;
    @BeforeEach void setup(TestInfo info) throws Exception {
        Files.createDirectories(tmp.resolve("repo/.git"));
        var ds = new org.sqlite.SQLiteDataSource(); ds.setUrl("jdbc:sqlite:" + tmp.resolve("db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        store = new TaskStore(new JdbcTemplate(ds));
        when(catalog.enabled()).thenReturn(true);
        boolean spring = info.getTestMethod().orElseThrow().getName().startsWith("spring");
        when(catalog.snapshot()).thenReturn(TeamCatalog.load(Path.of(spring ? "teams/spring-starters.json" : "teams/node-web.json"), key -> tmp.resolve("repo").toString()));
        // 既有流程測試維持「不需計畫核准」契約；新核准測試另明確啟用。
        var original = catalog.snapshot();
        ObjectNode legacyDefinition = mapper.valueToTree(original.definition());
        ((ObjectNode)legacyDefinition.path("project")).put("requirePlanApproval", false);
        when(catalog.snapshot()).thenReturn(new TeamDefinition.Snapshot(original.revision(),
                mapper.treeToValue(legacyDefinition, TeamDefinition.class), original.repository(), original.assets()));
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
        when(executor.execute(any(), any(), any())).thenAnswer(c -> {
            FlowRun flow = c.getArgument(1); TeamDefinition.Node node = c.getArgument(2); calls.add(flow.node + ":" + node.role());
            Task executing = c.getArgument(0);
            if (executing.continuation() != null) resumed.add(flow.node + ":" + executing.continuation().sessionId());
            if (mode.equals("usage") && flow.node.equals("verification"))
                throw new RuntimeUsageError("Codex CLI 用法錯誤，指令未送達模型（結束碼 2）：error: unexpected argument '-P' found");
            if (mode.equals("quota") && flow.node.equals("verification")) {
                store.saveCheckpoint(new Checkpoint("42", flow.node, "claude-code", "sonnet", "saved-session", "context", "head1", "quota"));
                throw new QuotaExceeded("usage_limit_reached");
            }
            JsonNode report = report(node.gate());
            if (node.gate() == TeamDefinition.Gate.TEST_PLAN
                    && "spring-starters".equals(flow.snapshot.definition().id())) {
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
                for (String id : List.of("triage", "implementation", "verification", "review"))
                    flow.visits.put(id, flow.snapshot.definition().nodes().get(id).maxVisits());
            }
            if (node.gate() == TeamDefinition.Gate.TEST_PLAN || node.gate() == TeamDefinition.Gate.VERIFICATION)
                ((ObjectNode) report).put("baseline_revision", flow.baselineRevision);
            if (node.gate() == TeamDefinition.Gate.VERIFICATION)
                ((ObjectNode) report).put("plan_revision", Traceability.digest(flow.receipts.get(node.plan())));
            if (flow.node.equals("design")) ((ObjectNode)report).put("route", "ready");
            if (flow.node.equals("triage")) ((ObjectNode)report).put("route", mode.equals("repair") ? "verification"
                    : mode.equals("dirtyVerification") ? "verification"
                    : mode.equals("automaticRepair") ? "implementation" : mode.equals("change") ? "requirements"
                    : mode.equals("replan") ? "plan" : "human");
            if ((mode.equals("blocked") && flow.node.equals("verification")) || firstAutomaticVerification || firstDirtyVerification)
                ((ObjectNode)report).withArray("uncertainties").add("SMTP 無法核實");
            JsonNode denials = deniedAt != null && deniedAt.equals(flow.node)
                    ? mapper.readTree("[{\"tool_name\":\"Bash\",\"tool_use_id\":\"t1\",\"tool_input\":{\"command\":\"git show --stat 814c9b1\"}}]")
                    : null;
            return new AgentRuntime.RuntimeResult("session", "", report, 0.1, false, tmp.resolve("log"), "model", denials);
        });
        when(verifier.verify(any(), any())).thenAnswer(c -> {
            FlowRun f = c.getArgument(1);
            return new IndependentVerifier.Receipt(workspaceHead, f.baselineRevision, f.snapshot.revision(), "start", "end", List.of());
        });
        engine = new FlowEngine(catalog, props, store, executor, workspace, slack, audit, publisher, verifier);
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
        };
        return mapper.readTree("{\"execution_status\":\"COMPLETED\",\"failure_report\":null," + fields + "}");
    }
    @Test void arbitraryFrontendAndReviewerExecuteFromConfiguration() throws Exception {
        engine.run(task);
        assertThat(calls).containsExactly("intake:coordinator", "plan:tester", "implementation:builder", "frontend:frontend", "verification:tester", "review:security_auditor");
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL); verifyNoInteractions(publisher);
        verify(verifier, times(1)).verify(any(), any());
        assertThat(store.flow(task.id()).orElseThrow().independentEvidence).isNotNull();
    }
    @Test void springConfigurationUsesSameEngineWithDifferentRolesAndNodes() {
        engine.run(task);
        assertThat(calls).containsExactly("intake:coordinator", "design:architect", "plan:tester", "implementation:builder", "verification:tester", "review:inspector");
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL); verifyNoInteractions(publisher);
    }
    @Test void blockedVerificationReallyRoutesToConfiguredCoordinatorAndHuman() {
        mode = "blocked"; engine.run(task);
        assertThat(calls).contains("triage:coordinator").doesNotContain("review:security_auditor");
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN); verifyNoInteractions(publisher);
    }
    @Test void springDirtyLimitedVerificationRoutesToConfiguredGitWriterAndReturnsToQa() throws Exception {
        mode = "dirtyVerification";

        engine.run(task);

        assertThat(calls).containsSubsequence("verification:tester", "triage:coordinator",
                "implementation:builder", "verification:tester", "review:inspector");
        assertThat(workspaceClean).isTrue();
        assertThat(workspaceHead).isEqualTo("head2");
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
        verify(audit).record(eq("42"), eq(AuditLog.ACTOR_SYSTEM), eq(AuditLog.ACTION_RECOVERY),
                contains("具 Git 寫入能力"));
    }
    @Test void humanFeedbackResumesConfiguredRepairNotWholePipeline() {
        mode = "blocked"; engine.run(task); calls.clear(); mode = "repair";
        List<Runnable> jobs = new ArrayList<>(); engine.resume(task, "已提供 SMTP 環境", "u", jobs::add); jobs.get(0).run();
        assertThat(calls).containsExactly("triage:coordinator", "verification:tester", "review:security_auditor");
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
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
    @Test void changedHeadAfterApprovalNeverPublishes() throws Exception {
        engine.run(task); when(workspace.headSha("42")).thenReturn("changed");
        List<Runnable> jobs = new ArrayList<>(); engine.approve(task, "u", jobs::add); jobs.get(0).run();
        verifyNoInteractions(publisher); assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
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
    @Test void corruptedEvidenceAfterApprovalNeverPublishes() throws Exception {
        engine.run(task); when(verifier.blockingReason(any(), anyString())).thenReturn("log 雜湊不符");
        List<Runnable> jobs = new ArrayList<>(); engine.approve(task, "u", jobs::add); jobs.get(0).run();
        verifyNoInteractions(publisher); assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
    }
    @Test void planRepairRerunsImplementationAndVerification() {
        mode = "blocked"; engine.run(task); mode = "replan"; calls.clear();
        List<Runnable> jobs = new ArrayList<>(); engine.resume(task, "測試計畫缺漏", "u", jobs::add); jobs.getFirst().run();
        assertThat(calls).containsExactly("triage:coordinator", "plan:tester", "implementation:builder", "frontend:frontend", "verification:tester", "review:security_auditor");
    }
    @Test void baselineChangeRequiresExplicitOwnerApprovalAndKeepsHistory() {
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
