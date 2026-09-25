package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.publish.PullRequestPublisher;
import com.zipe.aiteam.runtime.*;
import com.zipe.aiteam.slack.*;
import com.zipe.aiteam.slack.SlackNotifier.Identity;
import com.zipe.aiteam.store.*;
import com.zipe.aiteam.task.*;
import com.zipe.aiteam.workspace.WorkspaceManager;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import static com.zipe.aiteam.flow.TeamDefinition.*;

/** 配置式流程引擎。由既有中央 worker 呼叫，不另起可繞過排程的執行緒。 */
@Component
public class FlowEngine {
    private final TeamCatalog catalog;
    private final AiTeamProperties props;
    private final TaskStore store;
    private final ConfiguredExecutor executor;
    private final WorkspaceManager workspaces;
    private final SlackNotifier slack;
    private final AuditLog audit;
    private final PullRequestPublisher publisher;
    private final IndependentVerifier verifier;
    public FlowEngine(TeamCatalog catalog, AiTeamProperties props, TaskStore store, ConfiguredExecutor executor,
                      WorkspaceManager workspaces, SlackNotifier slack, AuditLog audit, PullRequestPublisher publisher, IndependentVerifier verifier) {
        this.catalog = catalog; this.props = props; this.store = store; this.executor = executor;
        this.workspaces = workspaces; this.slack = slack; this.audit = audit; this.publisher = publisher;
        this.verifier = verifier;
    }
    public boolean enabled() { return catalog.enabled(); }
    public boolean owns(String id) { return store.flow(id).isPresent(); }
    public void prepare(Task task) {
        FlowRun flow = new FlowRun(catalog.snapshot()); flow.acceptanceOwner = task.requesterUserId();
        store.saveFlow(task.id(), flow);
        audit.record(task.id(), task.requesterUserId(), AuditLog.ACTION_STATE, "固定配置版本 " + catalog.snapshot().revision());
    }
    /**
     * 使用者在 Slack 要求中斷的任務 → 要求者。只放在記憶體：服務重啟時執行中的那一輪本來就會中斷。
     * 與「中止任務」不同，中斷只停這一輪，任務回到 AWAIT_HUMAN，工作區、次數與 session 都保留。
     */
    private final Map<String, String> interruptRequests = new java.util.concurrent.ConcurrentHashMap<>();
    /** 測試替換用；正式環境停掉該任務的 systemd unit。 */
    java.util.function.Predicate<String> unitStopper = com.zipe.aiteam.sandbox.TaskUnits::stop;

    /** 只接受正在執行的一般流程任務；排隊、等人或角色對話都沒有「這一輪」可停。 */
    public void interrupt(Task task, String user) {
        Task current = store.find(task.id()).orElse(task);
        FlowRun flow = store.flow(task.id()).orElse(null);
        if (flow == null || flow.dialogue || !current.state().isRunning()) {
            slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                    "任務 #" + task.id() + " 目前沒有正在執行的一輪（狀態 " + current.state() + "），不需要中斷。");
            return;
        }
        if (interruptRequests.putIfAbsent(task.id(), user) != null) return;
        audit.record(task.id(), user, AuditLog.ACTION_BUTTON, "要求中斷節點 " + flow.node + " 這一輪");
        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                "⏸ <@" + user + "> 要求中斷任務 #" + task.id() + " 的「" + flow.node + "」這一輪，正在停止執行中的程序；"
                        + "停下後會貼出可用選項。任務不會被中止，工作區與未提交的修改都保留。");
        if (!unitStopper.test(task.id()))
            slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                    "⚠️ 停止執行單元的指令沒有成功結束；這一輪可能要等 agent 自己結束才會停下。若持續沒有停，請通知管理者查核。");
    }
    /** 中斷已生效：交給人決定，不自動重試、不算系統失敗。回傳 true 表示已停下。 */
    private boolean stopForInterrupt(Task task, FlowRun flow) {
        String user = interruptRequests.remove(task.id());
        if (user == null) return false;
        audit.record(task.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_STATE, "依要求中斷，停在節點 " + flow.node);
        waitHuman(task, flow, "已依 <@" + user + "> 的要求中斷「" + flow.node + "」這一輪。"
                + "\n這一輪已計入的次數不會再重扣；工作區裡沒有提交的修改都還在，請先確認再繼續。");
        return true;
    }
    private boolean aborted(Task task) { return store.find(task.id()).map(t -> t.state() == Task.State.ABORTED).orElse(false); }
    private void state(Task task, Task.State state, String text) {
        if (aborted(task)) return;
        Task.State before = task.state(); task.state(state); store.save(task);
        audit.stateChange(task, before, state, AuditLog.ACTOR_SYSTEM);
        slack.update(Identity.SYSTEM, task.slackChannel(), task.statusMessageTs(), text, null);
    }
    private void waitHuman(Task task, FlowRun flow, String reason) {
        waitHuman(task, flow, reason, true);
    }
    private void waitHuman(Task task, FlowRun flow, String reason, boolean canSupplement) {
        store.saveFlow(task.id(), flow);
        state(task, Task.State.AWAIT_HUMAN, "任務 #" + task.id() + " 等待人工，禁止發布");
        postHumanOptions(task, flow, (flow.acceptanceOwner == null ? "" : "<@" + flow.acceptanceOwner + "> ")
              + "任務 #" + task.id() + " 停下來了，現在沒有任何 agent 在跑，要等你決定怎麼做。\n"
                + Diagnostics.clip(reason, 7000), canSupplement);
    }

    /**
     * 貼出說明與目前真的可用的選項（含按鈕），不改任務狀態。
     * 除了停止時使用，閘門擋下使用者的輸入時也用它 —— 否則使用者按了不適用的舊按鈕，
     * 只會收到一句「不行」，卻不知道現在該按哪一個。
     */
    private void postHumanOptions(Task task, FlowRun flow, String lead, boolean canSupplement) {
        String token = extensionToken(task, flow);
        HumanOptions.Workspace workspace = workspaceState(task, flow);
        boolean configUpdate = configUpdateAvailable(flow);
        String text = lead
                + HumanOptions.render(task, flow, canSupplement, props.briefReports(), token, workspace, configUpdate);
        var options = HumanOptions.options(task, flow, canSupplement, props.briefReports(), token, workspace, configUpdate);
        String sent = null;
        if (options.stream().anyMatch(HumanOptions.Option::button)) {
            sent = slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), text,
                    HumanGate.blocks(task.id(), text, options));
            // 版面被 Slack 拒收時，至少要讓人收到文字版（裡面有同樣的指令）；
            // 停止訊息是使用者唯一知道要做什麼的地方，不能因為按鈕格式問題整則消失。
            if (sent == null) audit.record(task.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_STATE,
                    "停止訊息的按鈕版面送出失敗，改送純文字版");
        }
        if (sent == null) slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), text);
    }

    /** 管理者已部署新配置，且差異屬於可原地套用的能力調整（與「檢視配置更新」同一套判斷）。 */
    private boolean configUpdateAvailable(FlowRun flow) {
        try {
            if (flow.dialogue || flow.changeTarget != null || !catalog.enabled()) return false;
            var target = catalog.snapshot();
            return !target.revision().equals(flow.snapshot.revision())
                    && !TeamMigration.preview(flow.snapshot, target).changes().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** 停止訊息要判斷恢復選項時用；取不到就當成沒有工作區，不讓訊息本身失敗。 */
    private HumanOptions.Workspace workspaceState(Task task, FlowRun flow) {
        try {
            if (!flow.workspaceReady || !workspaces.exists(task.id())) return HumanOptions.Workspace.NONE;
            return new HumanOptions.Workspace(workspaces.headSha(task.id()), workspaces.clean(task.id()));
        } catch (Exception e) {
            return HumanOptions.Workspace.NONE;
        }
    }

    /** 額度已滿且現場可辨識時才有延長授權碼；其餘情況回傳 null，按鈕與文字都不會出現。 */
    private String extensionToken(Task task, FlowRun flow) {
        if (!AutomaticHandoff.extensionGrantsRoom(flow)) return null;
        try {
            String head = flow.workspaceReady && workspaces.exists(task.id()) ? workspaces.headSha(task.id()) : null;
            return AutomaticHandoff.extensionToken(flow, head);
        } catch (Exception e) {
            audit.record(task.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_STATE,
                    "無法建立延長授權碼：" + Diagnostics.describe(e));
            return null;
        }
    }
    /**
     * 計畫通過系統的機械檢查時自動核准（由配置 autoApprovePlanWhenValid 開啟）。
     *
     * 檢查的是同一份 Traceability 規則：涵蓋全部原子驗收項目、每條情境有反例、
     * 操作契約可執行。不是放寬標準，是不要在系統已經驗過的地方再等人按一次。
     * 發布與需求變更仍然必須真人核准。
     */
    private void autoApprovePlan(Task task, FlowRun flow, String plan) {
        if (plan == null || !flow.snapshot.definition().project().autoApprovePlanWhenValid()) return;
        if (PlanApproval.blockingReason(flow, plan) == null) return;
        var report = flow.receipts.get(plan);
        if (report == null || Traceability.planReason(report, flow) != null) return;
        String digest = Traceability.digest(report);
        flow.planApprovals.put(plan, new PlanApproval(plan, digest, flow.baselineRevision,
                flow.snapshot.revision(), flow.acceptanceOwner, java.time.Instant.now().toString()));
        store.saveFlow(task.id(), flow);
        audit.record(task.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_STATE,
                "測試計畫通過系統機械檢查，依配置自動核准：" + plan + "／" + digest
                        + "；未降低驗收標準，PR 仍需真人核准");
        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                "測試計畫已通過系統檢查（涵蓋全部驗收項目、每條情境都有反例、操作可執行），依設定自動核准並接著執行驗收。"
                        + "\n要看內容：補充 #" + task.id() + " 檢視測試計畫。發布仍然需要你核准。");
    }

    private void presentPlan(Task task, FlowRun flow, String plan) {
        try {
            var pages = PlanReview.pages(task.id(), plan, flow.receipts.get(plan));
            for (String page : pages) {
                String sent = page.equals(pages.getLast())
                        ? slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), page,
                            PlanReview.buttons(new PlanReview.Decision(task.id(), plan, Traceability.digest(flow.receipts.get(plan)), flow.snapshot.revision(), flow.baselineRevision)))
                        : slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), page);
                if (sent == null) {
                    audit.record(task.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_STATE, "計畫呈現未完成，請重新檢視測試計畫");
                    return;
                }
            }
        } catch (IllegalArgumentException e) {
            slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), e.getMessage());
        }
    }
    /**
     * 交接通知：以剛跑完的角色身分 tag 下一棒。
     *
     * 舊版管線每次換手都會發，改成配置式引擎後漏掉了 —— 少了它，討論串裡看不出
     * 是誰把工作交給誰，被交接的角色也收不到通知。
     *
     * 這是「可見的交接」而不是觸發機制：實際執行順序仍然由這個引擎決定，
     * 被 tag 的角色不會因為這則訊息而自己動起來。
     */
    private void handoff(Task task, FlowRun flow, Node from, Node to, boolean blocked) throws Exception {
        if (to == null || to.kind() != Kind.AGENT || from.role().equals(to.role())) return;
        var roles = flow.snapshot.definition().roles();
        Role fromRole = roles.get(from.role()), toRole = roles.get(to.role());
        if (fromRole == null || toRole == null) return;
        slack.postConfigured(fromRole, task.slackChannel(), task.slackThreadTs(),
                slack.mention(Identity.of(to.role(), toRole.name())) + " "
              + (blocked ? "我這邊受阻，系統已自動交給你分析與分派。" : "我這邊完成了，換你接手。")
              + "（節點 " + from.role() + " → " + to.role() + "）"
              + "\n配置=" + flow.snapshot.revision()
              + (blocked && flow.lastBlockReason != null ? "\n原因=" + Diagnostics.clip(flow.lastBlockReason, 900) : "")
              + (flow.workspacePath == null ? "\n尚未建立任務工作區" : "\n工作區=" + flow.workspacePath + "\ncommit=" + workspaces.headSha(task.id())));
    }
    public void run(Task task) {
        // 中斷要求只對當下這一輪有效；任務若以其他方式先停下，不能留到下一次續跑才生效。
        try { runFlow(task); } finally { interruptRequests.remove(task.id()); }
    }
    private void runFlow(Task task) {
        FlowRun flow = store.flow(task.id()).orElseThrow();
        AutomaticHandoff.Normalization normalization = AutomaticHandoff.normalizeLegacyKeys(flow);
        if (normalization.changed()) {
            store.saveFlow(task.id(), flow);
            audit.record(task.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_RECOVERY,
                    "自動交接舊制逐跳紀錄已合併為獨立事件：" + normalization.before() + "→" + normalization.after()
                            + "；未清除歷史證據、未新增額度或核准");
        }
        // 只記錄「本輪確實計入」的節點，讓未送達模型的失敗能原樣還原，不影響其他路徑。
        String countedNode = null; boolean usedRecovery = false; String usedRemediationHead = null;
        AutomaticHandoff.Claim usedAutomaticHandoff = null;
        try {
            if (aborted(task)) return;
            if (task.statusMessageTs() == null) { task.statusMessageTs(slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "配置流程 #" + task.id() + " 開始處理")); store.save(task); }
            while (!aborted(task)) {
                // 中斷時 agent 剛好已經交出結果：照常處理結果，停在下一步開始前。
                if (stopForInterrupt(task, flow)) return;
                if (flow.dialogue) { runDialogue(task, flow); return; }
                if (flow.node.equals("end")) { AutomaticHandoff.clear(flow); store.saveFlow(task.id(), flow); state(task, Task.State.COMPLETED, "任務 #" + task.id() + " 已結束（未發布）"); return; }
                Node node = flow.snapshot.definition().nodes().get(flow.node);
                if (node == null) throw new IllegalStateException("快照缺少節點，禁止猜測恢復路徑");
                if (node.gate() == Gate.VERIFICATION) {
                    autoApprovePlan(task, flow, node.plan());
                    String approvalBlock = PlanApproval.blockingReason(flow, node.plan());
                    if (approvalBlock != null) { waitHuman(task, flow, approvalBlock); presentPlan(task, flow, node.plan()); return; }
                }
                if (node.kind() == Kind.END) { AutomaticHandoff.clear(flow); store.saveFlow(task.id(), flow); state(task, Task.State.COMPLETED, "任務已結束（未發布）"); return; }
                if (node.kind() == Kind.APPROVAL) {
                    AutomaticHandoff.clear(flow);
                    String head = workspaces.headSha(task.id());
                    String block = FlowGates.publicationReason(flow, head);
                    if (block == null && flow.snapshot.definition().version() >= 2) block = verifier.blockingReason(flow, head);
                    if (block != null || !workspaces.clean(task.id())) { waitHuman(task, flow, block == null ? "工作區尚有未提交修改" : block); return; }
                    flow.approvalHead = head; store.saveFlow(task.id(), flow);
                    state(task, Task.State.AWAIT_PR_APPROVAL, "任務 #" + task.id() + " 等待人工核准 PR");
                    String message = "任務 #" + task.id() + " 已完成配置中的檢查，請核准才會推分支與開 PR（不代表產品已交付）。\ncommit=" + head
                            + "\n需求基準=" + flow.baselineRevision + "\n需求／驗收負責人=<@" + flow.acceptanceOwner + ">";
                    slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), message, SlackBlocks.approvalBlocks(task.id(), message));
                    return;
                }
                if (flow.activeNode == null) {
                    if (node.gate() == Gate.REQUIREMENTS && flow.baselineRevision != null && !flow.changeAuthorized) {
                        flow.changeTarget = flow.node;
                        waitHuman(task, flow, "重新建立需求基準需要原需求負責人明確核准。請補充「核准需求變更 具體變更內容」。"); return;
                    }
                    String redo = null;
                    if (node.requires() != null) for (String dependency : node.requires()) {
                        String invalid = FlowGates.blockingReason(flow.snapshot.definition().nodes().get(dependency), flow.receipts.get(dependency), flow);
                        if (invalid == null) continue;
                        // 能恢復就停下來讓人決定（附按鈕），不要丟成一般錯誤 —— 否則每次續跑都在同一處失敗。
                        Node needed = flow.snapshot.definition().nodes().get(dependency);
                        boolean workspaceNode = needed != null && needed.workspace() && workspaces.exists(task.id());
                        String head = workspaceNode ? workspaces.headSha(task.id()) : null;
                        boolean clean = !workspaceNode || workspaces.clean(task.id());
                        if (ContractRecovery.blockingReason(flow, dependency, head, clean) == null) {
                            waitHuman(task, flow, "「" + flow.node + "」要開始前，需要「" + dependency + "」的有效結果，但系統手上沒有。"
                                    + "\n原因是「" + dependency + "」的報告先前被系統規則否決了，而那份報告本身是合格的"
                                    + "（重新檢查已通過該階段的閘門）。"
                                    + (head == null ? "" : "\n目前工作區乾淨，commit=" + head)
                                    + "\n\n如果你確認這份結果可以用，按下方按鈕把它恢復成有效結果；不會重跑、不會重設任何次數。");
                            return;
                        }
                        // 前置步驟沒有有效結果，就回去把它做完 —— 那是它的責任，不是真人的。
                        // 舊版直接丟例外，而此時也沒有「繼續」可按：#1016 與承接它的 #1017 都卡在這裡，
                        // 分流把流程送到驗收，實作的報告卻因回報矛盾沒被採用。
                        // 重做仍受節點重跑、整輪換手上限約束，不會無限來回。
                        if (needed != null && needed.kind() == Kind.AGENT) { redo = dependency; break; }
                        throw new IllegalStateException("節點 " + flow.node + " 缺少有效前置契約 " + dependency + "：" + invalid);
                    }
                    if (redo != null) {
                        String invalid = FlowGates.blockingReason(flow.snapshot.definition().nodes().get(redo), flow.receipts.get(redo), flow);
                        String reason = "「" + flow.node + "」要開始前需要「" + redo + "」的有效結果，但目前沒有："
                                + Diagnostics.clip(invalid, 800)
                                + "\n系統自動回到「" + redo + "」把它完成；請依上述原因修正回報或成果，不要重複同樣的回報。";
                        flow.lastBlockReason = Diagnostics.clip(reason, 4500);
                        flow.context = FlowContext.append(flow.context, "系統責任路由：" + reason);
                        audit.record(task.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_RECOVERY,
                                "節點 " + flow.node + " 缺少有效前置結果 " + redo + "，自動退回重做；未重設任何計數或核准");
                        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                                "任務 #" + task.id() + "：「" + flow.node + "」需要的「" + redo + "」結果不成立，"
                                        + "系統自動退回「" + redo + "」重做，這一步不需要你處理。\n原因：" + Diagnostics.clip(invalid, 400));
                        flow.node = redo;
                        store.saveFlow(task.id(), flow);
                        continue;
                    }
                    boolean recoveryPermitted = VerificationRecovery.permitted(flow,flow.node);
                    // 先算這次的輸入指紋：只有「輸入完全相同的重跑」才算原地打轉。
                    String inputHead = node.workspace() && flow.workspaceReady && workspaces.exists(task.id())
                            ? workspaces.headSha(task.id()) : null;
                    String fingerprint = NodeInput.fingerprint(flow, node, inputHead);
                    boolean repeatedInput = NodeInput.repeated(flow, fingerprint);
                    boolean overLimit = flow.transitions >= flow.snapshot.definition().maxTransitions()
                            || (repeatedInput && flow.repeats.getOrDefault(flow.node, 0) >= node.maxVisits());
                    usedAutomaticHandoff = null;
                    if (overLimit && !recoveryPermitted) {
                        AutomaticHandoff.restoreLegacyBlockedTarget(flow);
                        usedAutomaticHandoff = AutomaticHandoff.claim(flow);
                    }
                    if (overLimit && !recoveryPermitted && usedAutomaticHandoff == null) {
                        waitHuman(task, flow, "系統自己已經試到上限，不會再自動重跑了。"
                                + "這是流程的防循環保護，不是 token 或用量不足。"
                                + "\n本次沒有啟動任何 agent，也沒有增加任何次數。"
                                + "\n\n目前的計數："
                                + "\n・這個階段（" + flow.node + "）跑過 " + flow.visits.getOrDefault(flow.node, 0)
                                + " 次，上限 " + node.maxVisits() + " 次"
                                + "\n・整個任務換手 " + flow.transitions + " 次，上限 "
                                + flow.snapshot.definition().maxTransitions() + " 次（受控交接可以越過，不代表次數被重設）"
                                + "\n・系統自己處理過的問題 " + flow.automaticHandoffs + " 件，上限 "
                                + AutomaticHandoff.effectiveLimit(flow) + " 件"
                                + "\n・同一件問題轉手 " + flow.automaticHandoffHops + " 站，上限 "
                                + AutomaticHandoff.HOP_LIMIT + " 站"
                                + (flow.lastBlockReason == null ? "\n\n上一次卡住的原因請查閱前一份角色報告。"
                                    : "\n\n上一次卡住的原因：\n" + Diagnostics.clip(flow.lastBlockReason, 700))
                                + fullReportHint(task, ""), false); return;
                    }
                    flow.transitions++;
                    flow.visits.merge(flow.node, 1, Integer::sum);
                    NodeInput.record(flow, fingerprint);
                    countedNode = flow.node; usedRecovery = false; usedRemediationHead = null;
                    if (usedAutomaticHandoff != null) {
                        audit.record(task.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_RECOVERY,
                                "依受阻證據自動交接給節點 " + flow.node + "；累計 "
                               + flow.automaticHandoffs + "／" + AutomaticHandoff.effectiveLimit(flow)
                              + "；未重設歷史計數、未新增權限或核准");
                    }
                    if (VerificationRecovery.permitted(flow,flow.node)) {
                        flow.recoveryGrants.remove(flow.node);
                        usedRemediationHead = flow.remediationRecoveryGrants.remove(flow.node);
                        usedRecovery = true;
                        audit.record(task.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_RECOVERY,
                                "使用一次真人受控恢復授權：" + flow.node + "，配置=" + flow.snapshot.revision()
                                        + (usedRemediationHead == null ? "" : "；人工修復 commit=" + usedRemediationHead)
                                        + "；累計次數=" + flow.visits.get(flow.node) + "，歷史計數與總上限未重設");
                    }
                    if (node.workspace()) {
                        if (!flow.workspaceReady) {
                            if (workspaces.exists(task.id())) throw new IllegalStateException("工作區已存在但快照未完成 provision，保留現場並停止");
                            workspaces.provision(task, Path.of(flow.snapshot.repository()), flow.snapshot.definition().project().baseBranch());
                            flow.workspaceReady = true;
                            flow.workspacePath = workspaces.pathFor(task.id()).toAbsolutePath().normalize().toString();
                            flow.origin = workspaces.origin(task.id()); flow.branch = workspaces.currentBranch(task.id());
                        }
                        if (!workspaces.exists(task.id())) throw new IllegalStateException("原工作區遺失，拒絕重新 clone");
                        flow.beforeHead = workspaces.headSha(task.id());
                        flow.attemptHeads.put(flow.node, flow.beforeHead);
                    } else flow.beforeHead = null;
                    flow.activeNode = flow.node;
                    if (node.gate() == Gate.REQUIREMENTS && flow.baselineRevision != null) {
                        JsonNode previous = Traceability.baseline(flow);
                        if (previous != null) flow.baselineHistory.add(previous.deepCopy());
                        flow.baselineRevision = null; flow.changeAuthorized = false;
                        flow.receipts.clear(); flow.heads.clear();
                    }
                    Traceability.invalidate(flow, flow.node);
                    if (node.scope() == Scope.WORKSPACE) {
                        for (var entry : flow.snapshot.definition().nodes().entrySet()) {
                            if (entry.getValue().gate() == Gate.VERIFICATION || entry.getValue().gate() == Gate.REVIEW) {
                                flow.receipts.remove(entry.getKey()); flow.heads.remove(entry.getKey());
                            }
                        }
                    }
                    task.continuation(null); store.saveFlow(task.id(), flow);
                } else {
                    task.continuation(store.checkpoint(task.id()).filter(c -> c.role().equals(flow.node)).orElse(null));
                    countedNode = null;
                }
                if (node.workspace()) requireWorkspace(task, flow);
                state(task, Task.State.STAGE_RUNNING, "任務 #" + task.id() + " 節點 " + flow.node + "／角色 " + node.role() + " 處理中");
                if ((node.gate() == Gate.VERIFICATION || node.gate() == Gate.REVIEW)
                        && flow.snapshot.definition().version() >= 2) {
                    if (!workspaces.clean(task.id())) {
                        if (routeDirtyVerification(task, flow, node)) continue;
                        throw new IllegalStateException("系統驗證前工作區有未提交修改，且無法安全判定具 Git 寫入能力的責任節點");
                    }
                    if (flow.independentEvidence == null) {
                        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "任務 #" + task.id()
                                + " 正由系統在全新 clone 執行獨立驗證；收據會先交驗收角色核對。");
                        try (var progress = new StageProgress(slack, store, task, "系統獨立驗證", props.progressIntervalSeconds(), true)) {
                            flow.independentEvidence = verifier.verify(task, flow);
                        } catch (IndependentVerifier.CheckFailed failed) {
                            // 被中斷的驗證也會以非零結束，不能當成程式錯誤交回實作。
                            if (interruptRequests.containsKey(task.id())) throw failed;
                            if (routeFailedCheck(task, flow, node, failed)) continue;
                            throw failed;
                        }
                        store.saveFlow(task.id(), flow);
                        audit.record(task.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_STATE, "獨立驗證收據：" + flow.independentEvidence);
                    }
                    String invalid = verifier.blockingReason(flow, workspaces.headSha(task.id()));
                    if (invalid != null) { flow.independentEvidence = null; throw new IllegalStateException(invalid + "；續跑時將重新執行系統驗證"); }
                }
                AgentRuntime.RuntimeResult result;
                long started = System.nanoTime();
                try (var progress = new StageProgress(slack, store, task,
                        flow.snapshot.definition().roles().get(node.role()).executionLabel() + "／" + flow.node, props.progressIntervalSeconds(), true)) {
                    result = executor.execute(task, flow, node);
                }
                catch (Exception e) { auditScope(task, flow, node); throw e; }
                if (aborted(task)) return;
                // 這一步真的跑起來了：系統層重試次數歸零，之後若再遇到環境問題還有完整的自動重試。
                flow.systemRetries.remove(flow.node);
                auditScope(task, flow, node);
                if (result.isError()) throw new IllegalStateException("runtime 回報 isError；診斷：" + result.diagnosticsLog());
                JsonNode report = result.structuredOutput();
                store.saveStage(task.id(), new StageOutcome(flow.node, result.sessionId(), report, result.costEstimate(), (System.nanoTime() - started) / 1_000_000_000));
                audit.record(task.id(), node.role(), AuditLog.ACTION_RUNTIME, "節點=" + flow.node + "；配置=" + flow.snapshot.revision(), result.costEstimate(), result.sessionId());
                if (result.permissionDenials() != null && !result.permissionDenials().isEmpty()) audit.record(task.id(), node.role(), AuditLog.ACTION_DENIED, Diagnostics.toolFailures(result.permissionDenials()));
                String block = FlowGates.blockingReason(node, report, flow);
                // 只有產出是「主張」的唯讀節點才需要這條規則；產物型節點的 commit 與測試結果
                // 由下游獨立驗證再查一次。#1014 的 implementation 就是因為一個 for 迴圈被誤判。
                if (block == null && node.scope() == Scope.READ_ONLY) {
                    Role acting = flow.snapshot.definition().roles().get(node.role());
                    block = ToolTrust.blockingReason(acting, result.permissionDenials());
                    if (block != null) audit.record(task.id(), node.role(), AuditLog.ACTION_DENIED,
                            "節點 " + flow.node + " 回報未受阻但本輪有工具遭拒，系統改判受阻；不採信其查核結論。");
                    String caveat = ToolTrust.caveat(acting, result.permissionDenials());
                    if (block == null && caveat != null)
                        audit.record(task.id(), node.role(), AuditLog.ACTION_DENIED, caveat);
                }
                slack.postConfigured(flow.snapshot.definition().roles().get(node.role()), task.slackChannel(), task.slackThreadTs(),
                        "任務 #" + task.id() + "｜" + flow.snapshot.definition().roles().get(node.role()).name()
                      + "（" + flow.node + "）\n" + describe(report, flow, node)
                      + fullReportHint(task, flow.node));
                String next;
                if (block == null) {
                    flow.receipts.put(flow.node, report);
                    flow.blockedFindings.remove(flow.node);
                    if (node.gate() == Gate.REQUIREMENTS && Traceability.requirementsReason(report) == null) {
                        flow.baselineRevision = Traceability.digest(report);
                        audit.record(task.id(), node.role(), AuditLog.ACTION_STATE, "建立需求基準=" + flow.baselineRevision + "；負責人=" + flow.acceptanceOwner);
                    }
                    if (node.workspace()) flow.heads.put(flow.node, workspaces.headSha(task.id()));
                    String route = report.path("route").asText("");
                    if (!route.isBlank()) {
                        if (node.routes() == null || !node.routes().containsKey(route)) throw new IllegalStateException("報告選擇未配置的 route，拒絕派工：" + route);
                        next = node.routes().get(route);
                    } else next = node.next();
                } else {
                    flow.lastBlockReason = Diagnostics.clip("節點 " + flow.node + "：" + block
                            + "\n" + SlackReport.render(report), 4500);
                    flow.blockedFindings.put(flow.node, report);
                    flow.context = FlowContext.append(flow.context, "節點 " + flow.node + " 受阻：\n" + block + "\n報告：" + report);
                    next = node.blocked();
                    if (!"human".equals(next)) {
                        String head = flow.workspacePath == null ? "" : workspaces.headSha(task.id());
                        AutomaticHandoff.begin(flow, flow.node, report, head);
                    }
                }
                flow.activeNode = null; task.continuation(null);
                countedNode = null;
                if (next.equals("human")) {
                    AutomaticHandoff.clear(flow);
                    if (SlackReport.hasDecision(report)) {
                        // 完整說明已由角色本人在上一則訊息貼出；這裡只放問題與按鈕，避免同一段話貼兩次。
                        String role = flow.snapshot.definition().roles().get(node.role()).name();
                        waitHuman(task, flow, "*" + role + " 需要你決定*\n"
                                + Diagnostics.clip(report.path("human_decision").path("question").asText(), 600)
                                + "\n（為什麼停下來、每個選項會怎樣，請看上方 " + role + " 的說明）");
                        return;
                    }
                    waitHuman(task, flow, block == null
                            ? describe(report, flow, node) + fullReportHint(task, flow.node)
                            : "阻礙：" + Diagnostics.clip(block, 500) + "\n"
                              + describe(report, flow, node) + fullReportHint(task, flow.node));
                    return;
                }
                Node target = flow.snapshot.definition().nodes().get(next);
                if (target != null && target.gate() == Gate.REQUIREMENTS && flow.baselineRevision != null) {
                    AutomaticHandoff.clear(flow);
                    flow.changeTarget = next;
                    waitHuman(task, flow, "PM 提議重新確認需求，尚未授權變更。只有原需求負責人可補充「核准需求變更 具體變更內容」。\n" + report); return;
                }
                handoff(task, flow, node, target, block != null);
                flow.node = next; store.saveFlow(task.id(), flow);
            }
        } catch (Exception e) {
            if (aborted(task)) return;
            // 程序是被要求停掉的，失敗訊息只是結果，不走系統重試或錯誤回報。activeNode 保留，
            // 停止訊息會提供「繼續執行」，續跑沿用原 session 且不再計次。
            if (stopForInterrupt(task, flow)) return;
            QuotaExceeded quota = QuotaExceeded.find(e);
            com.zipe.aiteam.runtime.RuntimeUsageError usage = com.zipe.aiteam.runtime.RuntimeUsageError.find(e);
            if (quota != null && store.checkpoint(task.id()).isPresent()) {
                store.saveFlow(task.id(), flow);
                store.saveCheckpoint(store.checkpoint(task.id()).orElseThrow().withReason(quota.getMessage()));
                state(task, Task.State.WAITING_QUOTA, "任務 #" + task.id() + " 等待額度，原節點與配置已保存");
                String message = "任務 #" + task.id() + " 已因額度停止。\n"
                        + com.zipe.aiteam.task.QuotaMessage.resetLine(quota.getMessage())
                        + "系統將安排自動續跑並另行回報嘗試時間，也可手動按繼續。沿用原角色、runtime 與配置。\n"
                        + Diagnostics.describe(e);
                slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), message, SlackBlocks.quotaBlocks(task.id(), message));
            } else if (usage != null && countedNode != null) {
                // 參數沒被 CLI 接受，模型完全沒被呼叫：把本輪的計數與一次性授權還原，
                // 否則我們自己的 bug 會吃掉真人核准，逼使用者重跑整套核准流程。
                flow.visits.merge(countedNode, -1, Integer::sum);
                flow.transitions = Math.max(0, flow.transitions - 1);
                if (usedRecovery) {
                    flow.recoveryGrants.put(countedNode, flow.snapshot.revision());
                    if (usedRemediationHead == null) flow.recoveryRevisions.remove(flow.snapshot.revision());
                    else {
                        flow.remediationRecoveryGrants.put(countedNode, usedRemediationHead);
                        flow.remediationRecoveryKeys.remove(VerificationRecovery.remediationKey(flow,countedNode,usedRemediationHead));
                    }
                }
                AutomaticHandoff.restore(flow, usedAutomaticHandoff);
                flow.activeNode = null; task.continuation(null);
                audit.record(task.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_ERROR,
                        "CLI 用法錯誤，未呼叫模型；已還原節點 " + countedNode + " 的次數"
                      + (usedRecovery ? "與一次受控恢復授權" : "") + "。" + Diagnostics.describe(e));
                waitHuman(task, flow, "本次未呼叫任何模型：執行參數被 CLI 拒絕，屬於本專案的缺陷。"
                      + "\n節點 " + countedNode + " 的重跑次數"
                      + (usedRecovery ? "與一次性受控恢復授權" : "")
                      + (usedAutomaticHandoff != null ? "及自動責任交接額度" : "")
                      + "已還原，額度與真人核准不受影響。"
                      + "\n修正部署後直接續跑即可，不需重新核准。\n" + Diagnostics.describe(e));
            } else if (countedNode != null && SystemFailure.transientFailure(e)
                    && flow.systemRetries.getOrDefault(countedNode, 0) < SystemFailure.RETRY_LIMIT) {
                // 系統自己沒跑起來，不是工作有問題：還原本輪計數與一次性授權後自動再試一次，
                // 不必等人按。連續失敗到上限才交給人。
                flow.visits.merge(countedNode, -1, Integer::sum);
                flow.transitions = Math.max(0, flow.transitions - 1);
                flow.repeats.merge(countedNode, -1, Integer::sum);
                if (usedRecovery) {
                    flow.recoveryGrants.put(countedNode, flow.snapshot.revision());
                    if (usedRemediationHead == null) flow.recoveryRevisions.remove(flow.snapshot.revision());
                    else {
                        flow.remediationRecoveryGrants.put(countedNode, usedRemediationHead);
                        flow.remediationRecoveryKeys.remove(VerificationRecovery.remediationKey(flow,countedNode,usedRemediationHead));
                    }
                }
                AutomaticHandoff.restore(flow, usedAutomaticHandoff);
                flow.activeNode = null; task.continuation(null);
                int attempt = flow.systemRetries.merge(countedNode, 1, Integer::sum);
                store.saveFlow(task.id(), flow);
                audit.record(task.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_ERROR,
                        "系統層失敗，第 " + attempt + "／" + SystemFailure.RETRY_LIMIT + " 次自動重試；"
                                + "已還原本輪計數與一次性授權。" + Diagnostics.describe(e));
                run(task);
            } else {
                audit.record(task.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_ERROR, Diagnostics.describe(e));
                waitHuman(task, flow, (SystemFailure.transientFailure(e)
                        ? "系統連續 " + SystemFailure.RETRY_LIMIT + " 次都沒能把這一步跑起來，這是系統或環境問題，不是工作內容有問題。\n"
                        : "") + Diagnostics.describe(e));
            }
        }
    }
    private void auditScope(Task task, FlowRun flow, Node node) throws Exception {
        if (!node.workspace() || node.scope() != Scope.LIMITED) return;
        if (flow.beforeHead == null) throw new SecurityException("缺少限寫階段起始 commit，不能核實寫入範圍");
        for (String path : workspaces.auditPaths(task.id(), flow.beforeHead)) {
            if (!PathRules.allows(node.writablePaths(), path) || PathRules.allows(flow.snapshot.definition().project().protectedPaths(), path))
                throw new SecurityException("限寫階段越權，禁止下游與發布：" + path);
        }
    }
    /** LIMITED 驗收可補測試，但 Git metadata 保持唯讀；安全變更交由配置中的 builder 收斂。 */
    private boolean routeDirtyVerification(Task task, FlowRun flow, Node node) throws Exception {
        if (node.gate() != Gate.VERIFICATION || node.scope() != Scope.LIMITED || flow.beforeHead == null) return false;
        List<String> changed = workspaces.auditPaths(task.id(), flow.beforeHead);
        if (changed.isEmpty() || changed.stream().anyMatch(path -> !PathRules.allows(node.writablePaths(), path)
                || PathRules.allows(flow.snapshot.definition().project().protectedPaths(), path))) return false;
        String targetId = implementationOwner(flow, node);
        if (targetId == null) return false;
        Node target = flow.snapshot.definition().nodes().get(targetId);
        String reason = "驗收節點產生了限寫範圍內的未提交變更：" + changed
                + "。請只核對並提交這些既有變更，完成配置建置後交回驗收；不得降低標準。";
        flow.lastBlockReason = reason;
        flow.context = FlowContext.append(flow.context, "系統責任路由：" + reason);
        flow.independentEvidence = null;
        AutomaticHandoff.begin(flow, flow.node, reason, workspaces.headSha(task.id()));
        flow.activeNode = null;
        task.continuation(null);
        audit.record(task.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_RECOVERY,
                "工作區未提交變更經路徑稽核後，自動交給具 Git 寫入能力的節點 " + targetId + "；變更=" + changed);
        handoff(task, flow, node, target, true);
        flow.node = targetId;
        store.saveFlow(task.id(), flow);
        return true;
    }
    /**
     * 負責修改程式的節點：優先採受阻分流節點配置的 implementation 路由，
     * 否則取流程中唯一具工作區寫入權的實作節點；有多個且無法由配置判定時回傳 null。
     */
    static String implementationOwner(FlowRun flow, Node node) {
        var nodes = flow.snapshot.definition().nodes();
        Node coordinator = node.blocked() == null ? null : nodes.get(node.blocked());
        String routed = coordinator == null || coordinator.routes() == null ? null : coordinator.routes().get("implementation");
        if (routed != null && ownsCode(nodes.get(routed))) return routed;
        List<String> owners = nodes.entrySet().stream().filter(e -> ownsCode(e.getValue())).map(Map.Entry::getKey).toList();
        return owners.size() == 1 ? owners.getFirst() : null;
    }
    private static boolean ownsCode(Node node) {
        return node != null && node.kind() == Kind.AGENT && node.gate() == Gate.IMPLEMENTATION
                && node.scope() == Scope.WORKSPACE && node.workspace();
    }

    /**
     * 系統獨立驗證的配置指令失敗 = 程式或測試沒過，是實作的責任。
     * 自動把失敗摘要交回實作節點修正，而不是停下來等人轉達；受阻發現會一路帶到驗收重新通過為止。
     * 仍受節點重跑、事件額度與整輪換手上限約束；找不到可歸責的實作節點時照舊停給真人。
     */
    private boolean routeFailedCheck(Task task, FlowRun flow, Node node, IndependentVerifier.CheckFailed failed) throws Exception {
        String targetId = implementationOwner(flow, node);
        if (targetId == null) return false;
        Node target = flow.snapshot.definition().nodes().get(targetId);
        String head = workspaces.headSha(task.id());
        String reason = "系統在全新 clone 執行配置的驗證指令失敗（" + String.join(" ", failed.argv()) + "，exit=" + failed.exitCode()
                + "，commit=" + head + "）。這代表程式或測試沒有通過，不是環境問題。"
                + "\n請找出原因修正後提交，完成後會交回驗收重新驗證。不得刪除、停用、跳過失敗的測試或放寬斷言來讓它通過；"
                + "若判定是測試本身不穩定（例如時間門檻過緊），要修成穩定且仍能驗到原需求，並在報告說明依據。"
                + "\n失敗摘要：\n" + failed.summary()
                + "\n完整紀錄：" + failed.log();
        var finding = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("source", "系統獨立驗證").put("commit", head).put("exit_code", failed.exitCode())
                .put("failure_summary", failed.summary()).put("log", failed.log());
        flow.lastBlockReason = Diagnostics.clip(reason, 4500);
        flow.blockedFindings.put(flow.node, finding);
        flow.context = FlowContext.append(flow.context, "系統獨立驗證未通過：\n" + reason);
        flow.independentEvidence = null;
        AutomaticHandoff.begin(flow, flow.node, reason, head);
        flow.activeNode = null;
        task.continuation(null);
        audit.record(task.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_RECOVERY,
                "獨立驗證指令失敗，自動交回實作節點 " + targetId + " 修正；commit=" + head + "；紀錄=" + failed.log());
        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                "任務 #" + task.id() + " 系統驗證沒有通過：有程式或測試失敗。已自動交回"
                        + flow.snapshot.definition().roles().get(target.role()).name()
                        + "修正，修好後會再驗一次；這一步不需要你處理。\n失敗摘要：\n"
                        + Diagnostics.clip(failed.summary(), 900));
        handoff(task, flow, node, target, true);
        flow.node = targetId;
        store.saveFlow(task.id(), flow);
        return true;
    }

    /** 依設定決定貼摘要或完整報告；預設摘要。 */
    private String describe(JsonNode report, FlowRun flow, Node node) {
        var routes = routeLabels(flow, node);
        return props.briefReports()
                ? com.zipe.aiteam.slack.SlackReport.brief(report, routes)
                : com.zipe.aiteam.slack.SlackReport.render(report, routes);
    }

    /**
     * 「下一步」的白話說明完全由配置推導：依 route 指向的節點類型與負責角色的顯示名稱組成。
     * 節點名稱每個專案都不同，程式裡不能寫死任何一個。
     */
    static java.util.function.Function<String, String> routeLabels(FlowRun flow, Node node) {
        return route -> {
            String target = node == null || node.routes() == null ? route : node.routes().getOrDefault(route, route);
            if ("human".equals(target) || "end".equals(target)) return SlackReport.GENERIC_ROUTES.apply(target);
            Node next = flow.snapshot.definition().nodes().get(target);
            if (next == null) return route;
            if (next.kind() == Kind.APPROVAL) return "進入發布核准（" + route + "）";
            if (next.kind() == Kind.END) return SlackReport.GENERIC_ROUTES.apply("end");
            Role role = flow.snapshot.definition().roles().get(next.role());
            String who = role == null ? next.role() : role.name();
            String what = switch (next.gate()) {
                case IMPLEMENTATION -> "交給 " + who + " 實作修正";
                case VERIFICATION -> "交給 " + who + " 驗收";
                case REVIEW -> "交給 " + who + " 審查";
                case TEST_PLAN -> "交給 " + who + " 重新規劃測試";
                case REQUIREMENTS -> "需求要重新確認（由 " + who + " 整理）";
                case REPORT -> "交給 " + who + " 分析判斷";
            };
            return what + "（" + route + "）";
        };
    }

    /** 摘要模式才需要告訴使用者完整報告怎麼拿；full 模式加這句只是雜訊。 */
    private String fullReportHint(Task task, String node) {
        return props.briefReports()
                ? "\n\n完整報告：補充 #" + task.id() + " 檢視完整報告"
                  + (node == null || node.isBlank() ? "" : " " + node)
                : "";
    }

    /**
     * 承接成新任務：原任務已用完自動處理次數（引擎與停止訊息共用 FlowLimits 判斷），
     * 由負責人明確要求，開一個從原任務最新 commit 接著做的新任務。
     *
     * 不沿用任何核准：新任務從需求節點開始，需求、測試計畫與 PR 都要重新確認。
     * 會帶入原任務的交接紀錄與未解除的受阻發現，負責人做過的決定（例如已核准的需求變更）
     * 就在交接紀錄裡，必須納入新任務的需求。新任務開在新的頂層討論串，避免同一串有兩個工作任務。
     */
    private void succeed(Task task, FlowRun flow, String user, Consumer<Runnable> enqueue) throws Exception {
        if (flow.dialogue) throw new IllegalArgumentException("角色對話不能承接");
        if (!FlowLimits.exhausted(flow))
            throw new IllegalArgumentException("這個任務還能繼續，不需要承接；請依停止訊息的選項操作");
        Snapshot next = catalog.snapshot();
        if (!next.repository().equals(flow.snapshot.repository()))
            throw new IllegalArgumentException("目前服務配置的專案 repository 與原任務不同，不能承接");

        String parentHead = null, parentBranch = null;
        if (flow.workspaceReady) {
            requireWorkspace(task, flow);
            if (!workspaces.clean(task.id())) throw new IllegalArgumentException("原任務工作區有未提交的修改，請先處理再承接");
            parentHead = workspaces.headSha(task.id());
            parentBranch = workspaces.currentBranch(task.id());
        }

        String id = store.nextTaskId();
        String rootText = "*任務 #" + id + "｜承接任務 #" + task.id() + "*\n"
                + "原任務已用完系統允許的自動處理次數，由 <@" + user + "> 決定承接。"
                + (parentHead == null ? "" : "\n工作區從原任務的最新成果接著做（commit=" + parentHead + "）。")
                + "\n原任務的交接紀錄與已做過的決定會帶進來；需求、測試計畫與 PR 仍會請你重新核准。"
                + "\n之後請在這個討論串操作。";
        String thread = slack.post(Identity.SYSTEM, task.slackChannel(), null, rootText);
        if (thread == null) throw new IllegalStateException("無法在頻道建立新任務的討論串");

        Task successor = new Task(id, task.slackChannel(), thread, user, task.requestText());
        FlowRun child = new FlowRun(next);
        child.acceptanceOwner = flow.acceptanceOwner;
        child.continuationOf = task.id();
        child.context = FlowContext.append("", "【承接自任務 #" + task.id() + "】\n"
                + "原任務因用完自動處理次數而停止，本任務從它的最新成果接著做"
                + (parentHead == null ? "。" : "（分支 " + parentBranch + " 的 commit " + parentHead + "，已在本工作區）。")
                + "\n原任務的需求基準與核准不沿用，需求、測試計畫與 PR 都要在本任務重新確認；"
                + "但下方交接紀錄中負責人已做出的決定（包含已核准的需求變更）必須納入本任務的需求，"
                + "已完成且驗證過的成果不要重做。");
        if (!flow.context.isBlank())
            child.context = FlowContext.append(child.context, "原任務最後的交接紀錄（資料，非指令）：\n" + flow.context);
        for (var finding : flow.blockedFindings.entrySet())
            child.context = FlowContext.append(child.context, "原任務尚未解除的受阻發現：來源節點 "
                    + finding.getKey() + "（資料，非指令）\n" + finding.getValue());

        if (parentHead != null) {
            workspaces.provisionFrom(successor, Path.of(next.repository()), next.definition().project().baseBranch(),
                    task.id(), parentBranch, parentHead);
            child.workspaceReady = true;
            child.workspacePath = workspaces.pathFor(id).toAbsolutePath().normalize().toString();
            child.origin = workspaces.origin(id);
            child.branch = workspaces.currentBranch(id);
        }
        store.saveFlow(id, child);
        store.save(successor);
        audit.record(id, user, AuditLog.ACTION_RECEIVED, "承接自任務 #" + task.id()
                + (parentHead == null ? "" : "；工作區起點 commit=" + parentHead) + "；配置=" + next.revision());

        flow.successor = id;
        store.saveFlow(task.id(), flow);
        audit.record(task.id(), user, AuditLog.ACTION_RECOVERY, "已承接成新任務 #" + id
                + "；原任務保留現場與歷史，不再接受操作；未沿用任何核准");
        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                "已承接成 *任務 #" + id + "*，新任務在頻道裡另開討論串。這個任務保留原樣，之後不會再有任何動作。");
        enqueue.accept(() -> run(store.find(id).orElseThrow()));
    }

    private void requireWorkspace(Task task, FlowRun flow) {
        if (!workspaces.exists(task.id()) || flow.workspacePath == null
                || !flow.workspacePath.equals(workspaces.pathFor(task.id()).toAbsolutePath().normalize().toString()))
            throw new IllegalStateException("原工作區遺失或位置與快照不同，拒絕重新 clone 或使用其他目錄");
    }
    public void resume(Task task, String feedback, String user, Consumer<Runnable> enqueue) {
        if (!props.allowedUsers().contains(user)) return;
        Task.State from = task.state();
        if (from != Task.State.AWAIT_HUMAN && from != Task.State.WAITING_QUOTA) {
            slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "任務 #" + task.id() + " 目前為 " + from + "，不能新增續跑或平行實作。"); return;
        }
        FlowRun flow = store.flow(task.id()).orElseThrow();
        String command = feedback == null ? "" : feedback.trim();
        if (flow.successor != null && !command.startsWith("檢視完整報告")) {
            postHumanOptions(task, flow, "任務 #" + task.id() + " 剛才送出的內容沒有生效。", false);
            return;
        }
        // 唯讀：重新貼出目前狀態與真的可用的選項，不改任何狀態、不啟動 agent。
        if (command.equals("狀態")) {
            postHumanOptions(task, flow, "任務 #" + task.id() + " 目前停在「" + flow.node + "」，沒有 agent 在跑。", true);
            return;
        }
        if (command.equals(HumanOptions.SUCCESSOR_COMMAND)) {
            if (from != Task.State.AWAIT_HUMAN || !user.equals(flow.acceptanceOwner)) return;
            try {
                succeed(task, flow, user, enqueue);
            } catch (Exception e) {
                slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                        "沒有建立承接任務：" + Diagnostics.describe(e));
            }
            return;
        }
        if (feedback != null && feedback.startsWith("核准延長自動交接")) {
            if (from != Task.State.AWAIT_HUMAN || !user.equals(flow.acceptanceOwner)) return;
            String[] parts = feedback.trim().split("\\s+");
            try {
                if (parts.length != 2 || !parts[0].equals("核准延長自動交接"))
                    throw new IllegalArgumentException("格式：核准延長自動交接 <授權碼>");
                AutomaticHandoff.normalizeLegacyKeys(flow);
                String head = flow.workspaceReady && workspaces.exists(task.id()) ? workspaces.headSha(task.id()) : null;
                AutomaticHandoff.authorizeExtension(flow, parts[1], head);
                if (!store.claimState(task.id(), from, Task.State.RESUME_QUEUED)) return;
                task.state(Task.State.RESUME_QUEUED);
                store.saveFlow(task.id(), flow);
                audit.record(task.id(), user, AuditLog.ACTION_RECOVERY,
                        "核准目前受阻證據延長自動交接；已用 " + flow.automaticHandoffs + "，目前上限="
                                + AutomaticHandoff.effectiveLimit(flow) + "；未重設歷史計數、核准或節點上限");
                slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                        "任務 #" + task.id() + " 已取得一次性自動交接延長並排入中央佇列；現在還能再自動處理 "
                                + AutomaticHandoff.remaining(flow) + " 件，沒有重設歷史計數或略過既有核准。");
                enqueue.accept(() -> run(store.find(task.id()).orElseThrow()));
            } catch (Exception e) {
                slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), Diagnostics.describe(e));
            }
            return;
        }
        if (feedback != null && feedback.startsWith("核准恢復前置契約")) {
            if (from != Task.State.AWAIT_HUMAN || !user.equals(flow.acceptanceOwner)) return;
            String[] parts = feedback.trim().split("\\s+");
            try {
                if (parts.length != 3 || !parts[0].equals("核准恢復前置契約"))
                    throw new IllegalArgumentException("格式：核准恢復前置契約 <前置節點> <commit>");
                String dependency = parts[1];
                Node needed = flow.snapshot.definition().nodes().get(dependency);
                String head = null; boolean clean = true;
                if (needed != null && needed.workspace()) {
                    requireWorkspace(task, flow);
                    head = workspaces.headSha(task.id());
                    clean = workspaces.clean(task.id());
                    if (!head.equals(parts[2])) throw new IllegalArgumentException("commit 與目前工作區 HEAD 不符，請重新查核");
                }
                String invalid = ContractRecovery.blockingReason(flow, dependency, head, clean);
                if (invalid != null) throw new IllegalArgumentException(invalid);
                ContractRecovery.restore(flow, dependency, head);
                store.saveFlow(task.id(), flow);
                audit.record(task.id(), user, AuditLog.ACTION_RECOVERY,
                        "核准恢復被系統規則否決的前置契約 " + dependency + "；commit=" + head
                                + "；原始報告已重新通過節點閘門；未重跑、未重設任何計數或核准");
                // 不自動續跑：恢復後通常還需要另一項授權（例如驗收次數已滿），
                // 直接跑只會撞上上限、給出令人困惑的停止訊息。改為明講下一步並附按鈕。
                waitHuman(task, flow, "已把「" + dependency + "」的結果恢復為有效（commit=" + head + "）。"
                        + "沒有重跑，也沒有重設任何次數。\n任務仍停在「" + flow.node + "」，還需要你再決定下一步。");
            } catch (Exception e) {
                slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), Diagnostics.describe(e));
            }
            return;
        }
        if (feedback != null && feedback.startsWith("核准人工修復重驗")) {
            if (from != Task.State.AWAIT_HUMAN) return;
            String[] parts=feedback.trim().split("\\s+");
            try {
                if(parts.length!=3 || !parts[0].equals("核准人工修復重驗"))
                    throw new IllegalArgumentException("格式：核准人工修復重驗 <驗收節點> <commit>");
                requireWorkspace(task,flow);
                String head=workspaces.headSha(task.id());
                if(!workspaces.clean(task.id())) throw new IllegalArgumentException("工作區有未提交修改，不能授權重驗");
                if(!head.equals(parts[2])) throw new IllegalArgumentException("commit 與目前工作區 HEAD 不符，請重新查核");
                VerificationRecovery.authorizeRemediation(flow,parts[1],head,user);
                if (!store.claimState(task.id(),from,Task.State.RESUME_QUEUED)) return;
                store.saveFlow(task.id(),flow);
                if(store.claimState(task.id(),Task.State.RESUME_QUEUED,Task.State.AWAIT_HUMAN)) task.state(Task.State.AWAIT_HUMAN);
                audit.record(task.id(),user,AuditLog.ACTION_BUTTON,"核准人工修復後一次重驗 "+parts[1]+"，配置="+flow.snapshot.revision()+"；commit="+head+"；未重設計數，待重新核准測試計畫");
                slack.post(Identity.SYSTEM,task.slackChannel(),task.slackThreadTs(),"已依人工修復 commit 授權一次重驗，不清空歷史次數；尚未派工。請重新審閱並核准下列測試計畫，核准後才會執行 QA。\ncommit="+head);
                presentPlan(task,flow,flow.snapshot.definition().nodes().get(parts[1]).plan());
            } catch(Exception e) { slack.post(Identity.SYSTEM,task.slackChannel(),task.slackThreadTs(),Diagnostics.describe(e)); }
            return;
        }
        if (feedback != null && feedback.startsWith("核准恢復驗收")) {
            if (from != Task.State.AWAIT_HUMAN) return;
            String[] parts=feedback.trim().split("\\s+");
            try {
                if(parts.length!=3 || !parts[0].equals("核准恢復驗收")) throw new IllegalArgumentException("格式：核准恢復驗收 <驗收節點> <配置版本>");
                VerificationRecovery.authorize(flow,parts[1],parts[2],user);
                Node recovered = flow.snapshot.definition().nodes().get(parts[1]);
                if (!store.claimState(task.id(),from,Task.State.RESUME_QUEUED)) return;
                store.saveFlow(task.id(),flow);
                if (recovered.gate() == Gate.VERIFICATION) {
                    if(store.claimState(task.id(),Task.State.RESUME_QUEUED,Task.State.AWAIT_HUMAN)) task.state(Task.State.AWAIT_HUMAN);
                    audit.record(task.id(),user,AuditLog.ACTION_BUTTON,"核准一次受控恢復 "+parts[1]+"，配置="+parts[2]+"；未重設計數，待重新核准測試計畫");
                    slack.post(Identity.SYSTEM,task.slackChannel(),task.slackThreadTs(),"已授權此配置一次受控驗收，不清空歷史次數；尚未派工。請重新審閱並核准下列測試計畫，核准後才會自動執行 QA。");
                    presentPlan(task,flow,recovered.plan());
                } else {
                    // 其他節點沒有計畫卡要重新核准，直接照新配置重新執行這一步。
                    task.state(Task.State.RESUME_QUEUED);
                    audit.record(task.id(),user,AuditLog.ACTION_BUTTON,"核准一次受控恢復 "+parts[1]+"，配置="+parts[2]+"；未重設計數，已排入重新執行");
                    slack.post(Identity.SYSTEM,task.slackChannel(),task.slackThreadTs(),"已依新配置重新執行「"+parts[1]+"」一次，不清空歷史次數。");
                    enqueue.accept(() -> run(store.find(task.id()).orElseThrow()));
                }
            } catch(IllegalArgumentException e) { slack.post(Identity.SYSTEM,task.slackChannel(),task.slackThreadTs(),e.getMessage()); }
            return;
        }
        if (feedback != null && feedback.startsWith("檢視完整報告")) {
            // 只讀既有的 stage_result，不呼叫模型、不改狀態、不推進流程。
            String wanted = feedback.substring("檢視完整報告".length()).trim();
            var stages = task.stages();
            if (wanted.isEmpty()) {
                // 沒指定就用目前節點；它還沒有結果時退回最後一個有結果的節點。
                wanted = stages.containsKey(flow.node) ? flow.node
                        : stages.keySet().stream().reduce((a, b) -> b).orElse("");
            }
            var outcome = wanted.isEmpty() ? null : stages.get(wanted);
            if (outcome == null || outcome.result() == null) {
                slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                        "找不到節點「" + Diagnostics.safe(wanted) + "」的報告。"
                      + (stages.isEmpty() ? "這個任務還沒有任何階段結果。"
                                          : "可查詢的節點：" + String.join("、", stages.keySet())));
            } else {
                slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                        "任務 #" + task.id() + "／節點 " + Diagnostics.safe(wanted) + " 的完整報告：\n"
                      + com.zipe.aiteam.slack.SlackReport.render(outcome.result()));
            }
            return;
        }
        if ("檢視測試計畫".equals(feedback)) {
            var current = flow.snapshot.definition().nodes().get(flow.node);
            String plan = current == null ? null : current.plan();
            if (plan == null) {
                var plans = flow.snapshot.definition().nodes().entrySet().stream()
                        .filter(e -> e.getValue().gate() == Gate.TEST_PLAN && flow.receipts.containsKey(e.getKey()))
                        .map(Map.Entry::getKey).toList();
                if (plans.size() == 1) plan = plans.getFirst();
            }
            if (plan == null) slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "無法唯一識別待審計畫，請管理者確認節點；未續跑。");
            else presentPlan(task, flow, plan);
            return;
        }
        if (feedback != null && feedback.startsWith("核准測試計畫")) {
            if (from != Task.State.AWAIT_HUMAN) {
                slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "請在任務停止等待人工時核准計畫；未改動額度排程或任務狀態。"); return;
            }
            String[] parts = feedback.trim().split("\\s+");
            if (parts.length != 3 || !"核准測試計畫".equals(parts[0])) {
                slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "格式：核准測試計畫 <計畫節點> <plan_revision>；未核准或續跑。"); return;
            }
            try {
                PlanApproval approved = PlanApproval.approve(flow, parts[1], parts[2], user);
                requirePlanQueueTarget(flow, parts[1]);
                if (!store.claimState(task.id(), from, Task.State.RESUME_QUEUED)) return;
                task.state(Task.State.RESUME_QUEUED);
                flow.planApprovals.put(parts[1], approved); flow.pendingPlanRevision = null; store.saveFlow(task.id(), flow);
                audit.record(task.id(), user, AuditLog.ACTION_BUTTON, "真人測試計畫核准：" + approved);
                slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "已保存測試計畫核准，QA 已排入中央佇列，無需再輸入續跑。這不代表 QA 已通過或核准 PR。");
                enqueue.accept(() -> run(store.find(task.id()).orElseThrow()));
            } catch (IllegalArgumentException e) { slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), e.getMessage()); }
            return;
        }
        if (feedback != null && (feedback.equals("檢視配置更新") || feedback.startsWith("核准配置更新"))) {
            if (from != Task.State.AWAIT_HUMAN || !user.equals(flow.acceptanceOwner) || flow.changeTarget != null) {
                slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "只允許原負責人在停止等待人工且無待核准需求變更時處理配置更新。"); return;
            }
            try {
                var target = catalog.snapshot();
                var preview = TeamMigration.preview(flow.snapshot, target);
                if (preview.changes().isEmpty()) { slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "沒有可套用的配置差異。"); return; }
                if (feedback.equals("檢視配置更新")) {
                    String detail = String.join("\n", preview.changes());
                    if (detail.length() > 5000) { slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "配置差異超過 Slack 可安全完整呈現的長度，請管理者縮小變更；未產生核准指令。"); return; }
                    String text = "*配置有更新（尚未套用）*\n" + detail
                            + "\n\n套用後保留歷史與工作區，舊的驗收與核准證據會失效，不會自動續跑。"
                            + "\n確認沒問題就按「核准配置更新」，或輸入：補充 #" + task.id() + " 核准配置更新 " + preview.token();
                    var approve = new HumanOptions.Option(HumanOptions.CONFIG_APPROVE, "核准配置更新",
                            "套用新配置", "核准配置更新 " + preview.token());
                    if (slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), text,
                            HumanGate.blocks(task.id(), text, java.util.List.of(approve))) == null)
                        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), text);
                    return;
                }
                if (!feedback.equals("核准配置更新 " + preview.token())) throw new IllegalArgumentException("核准指令或版本不符，請重新檢視配置更新");
                TeamMigration.apply(flow, target, preview.token());
                if (!store.claimState(task.id(), from, Task.State.RESUME_QUEUED)) return;
                store.saveFlow(task.id(), flow);
                audit.record(task.id(), user, AuditLog.ACTION_BUTTON, "核准配置更新 " + preview);
                if (store.claimState(task.id(), Task.State.RESUME_QUEUED, Task.State.AWAIT_HUMAN)) task.state(Task.State.AWAIT_HUMAN);
                postHumanOptions(task, flow, "已套用新配置並保存原快照；還沒有重新執行，舊的計畫核准已失效。PR 仍需另行核准。", true);
            } catch (IllegalArgumentException e) { slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), e.getMessage()); }
            return;
        }
        if (flow.changeTarget != null && (!user.equals(flow.acceptanceOwner) || feedback == null
                || !feedback.startsWith("核准需求變更 ") || feedback.substring("核准需求變更 ".length()).isBlank())) {
            postHumanOptions(task, flow, HumanOptions.exhausted(flow)
                    ? "任務 #" + task.id() + " 剛才送出的內容沒有生效。"
                    : "任務 #" + task.id() + " 正在等 <@" + flow.acceptanceOwner + "> 核准需求變更，"
                    + "這時一般補充不會被採用（剛才送出的內容沒有生效）。\n請改按下方的「核准：…」按鈕，或輸入「核准需求變更 具體變更內容」。", true);
            return;
        }
        if (flow.pendingPlanRevision != null && (!user.equals(flow.acceptanceOwner)
                || feedback == null || feedback.isBlank() || feedback.trim().equals("續跑"))) {
            slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "請原負責人先補充測試計畫的具體修改意見；目前不執行 QA。"); return;
        }
        if (!store.claimState(task.id(), from, Task.State.RESUME_QUEUED)) return;
        if (flow.pendingPlanRevision != null) {
            flow.node = flow.pendingPlanRevision; flow.pendingPlanRevision = null; flow.activeNode = null;
            audit.record(task.id(), user, AuditLog.ACTION_STATE, "依真人修改意見重訂測試計畫，原計畫及重試次數保留於歷史；需重新核准。");
        }
        if (flow.changeTarget != null) {
            flow.node = flow.changeTarget; flow.changeTarget = null; flow.changeAuthorized = true; flow.activeNode = null;
            audit.record(task.id(), user, AuditLog.ACTION_STATE, "核准需求變更，原基準=" + flow.baselineRevision + "；內容=" + feedback);
        }
        flow.context = FlowContext.append(flow.context, "使用者 " + user + " 補充：" + Objects.toString(feedback, "額度恢復，續跑原階段"));
        // 真人給了新資訊，一定算成新的輸入，否則節點會因為「輸入相同」而拒絕重跑。
        flow.supplements++;
        store.saveFlow(task.id(), flow);
        enqueue.accept(() -> run(store.find(task.id()).orElseThrow()));
        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "任務 #" + task.id() + " 已排隊，將使用原配置與節點續跑，不代表已核准 PR。");
    }

    private void requirePlanQueueTarget(FlowRun flow, String plan) {
        Node current = flow.snapshot.definition().nodes().get(flow.node);
        if (flow.dialogue || flow.changeTarget != null || current == null || current.gate() != Gate.VERIFICATION
                || !Objects.equals(current.plan(), plan))
            throw new IllegalArgumentException("目前不是此計畫的 QA 等待節點，請先確認流程；未核准或派工。");
    }
    /** 管理者明確指定的既有核准任務；不批次恢復、不建立新核准紀錄。 */
    public void resumeApprovedPlan(Task task, Consumer<Runnable> enqueue) {
        FlowRun flow = store.flow(task.id()).orElseThrow();
        Node current = flow.snapshot.definition().nodes().get(flow.node);
        if (current == null) throw new IllegalArgumentException("不存在的 QA 節點");
        requirePlanQueueTarget(flow, current.plan());
        if (!props.allowedUsers().contains(flow.acceptanceOwner) || flow.pendingPlanRevision != null
                || !flow.snapshot.definition().project().requirePlanApproval()
                || !flow.planApprovals.containsKey(current.plan()) || PlanApproval.blockingReason(flow, current.plan()) != null)
            throw new IllegalArgumentException("沒有有效的既有核准，拒絕恢復 QA");
        if (!store.claimState(task.id(), Task.State.AWAIT_HUMAN, Task.State.RESUME_QUEUED)) return;
        task.state(Task.State.RESUME_QUEUED);
        audit.record(task.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_RECOVERY, "管理者明確指定恢復已核准計畫，沿用既有真人收據，QA 已排入中央佇列");
        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "任務 #" + task.id() + " 已依既有測試計畫核准排入 QA，無需重新核准或另送續跑；PR 仍須獨立核准。");
        enqueue.accept(() -> run(store.find(task.id()).orElseThrow()));
    }
    /** 按鈕核准即派工；要求修改仍停下，並驗證版本、負責人與原討論串。 */
    public void planDecision(Task task, String user, PlanReview.Decision d, boolean approve, String channel, String thread, Consumer<Runnable> enqueue) {
        if (!props.allowedUsers().contains(user) || !task.id().equals(d.task())
                || !task.slackChannel().equals(channel) || !task.slackThreadTs().equals(thread)) return;
        FlowRun flow = store.flow(task.id()).orElseThrow();
        if (task.state() != Task.State.AWAIT_HUMAN || !user.equals(flow.acceptanceOwner)
                || !Objects.equals(d.configuration(), flow.snapshot.revision()) || !Objects.equals(d.baseline(), flow.baselineRevision)
                || !flow.receipts.containsKey(d.plan()) || !Objects.equals(d.digest(), Traceability.digest(flow.receipts.get(d.plan())))) {
            slack.post(Identity.SYSTEM, channel, thread, "按鈕已過期、任務不在等待狀態或你不是原負責人；未核准或派工。請重新檢視測試計畫。"); return;
        }
        try { PlanApproval.approve(flow, d.plan(), d.digest(), user); }
        catch (IllegalArgumentException e) { slack.post(Identity.SYSTEM, channel, thread, e.getMessage()); return; }
        if (approve) {
            resume(task, "核准測試計畫 " + d.plan() + " " + d.digest(), user, enqueue);
        } else {
            if (!store.claimState(task.id(), Task.State.AWAIT_HUMAN, Task.State.RESUME_QUEUED)) return;
            flow.planApprovals.remove(d.plan());
            flow.pendingPlanRevision = d.plan();
            flow.context += "\n真人 " + user + " 要求修改測試計畫 " + d.plan() + "／" + d.digest() + "；等待具體修改意見，不得當成已核准。";
            store.saveFlow(task.id(), flow);
            audit.record(task.id(), user, AuditLog.ACTION_BUTTON, "要求修改測試計畫 " + d.value());
            if (store.claimState(task.id(), Task.State.RESUME_QUEUED, Task.State.AWAIT_HUMAN)) task.state(Task.State.AWAIT_HUMAN);
            slack.post(Identity.SYSTEM, channel, thread, "已記錄要求修改，計畫尚未核准，QA 不會執行。請在原討論串 @PM Agent，輸入「補充 #" + task.id() + " …」說明要修改的情境與預期結果。");
        }
    }
    public void approve(Task task, String user, Consumer<Runnable> enqueue) {
        if (!props.allowedUsers().contains(user)) return;
        if (!store.claimState(task.id(), Task.State.AWAIT_PR_APPROVAL, Task.State.PUBLISH_RUNNING)) return;
        audit.record(task.id(), user, AuditLog.ACTION_BUTTON, "人工核准配置流程的 PR");
        enqueue.accept(() -> {
            FlowRun flow = store.flow(task.id()).orElseThrow();
            try {
                if (aborted(task)) return;
                requireWorkspace(task, flow);
                String head = workspaces.headSha(task.id());
                String reason = FlowGates.publicationReason(flow, head);
                if (reason == null && flow.snapshot.definition().version() >= 2) reason = verifier.blockingReason(flow, head);
                if (reason != null || !head.equals(flow.approvalHead) || !workspaces.clean(task.id())) throw new IllegalStateException("發布前證據／工作區已變更：" + reason);
                if (!Objects.equals(flow.origin, workspaces.origin(task.id())) || !Objects.equals(flow.branch, workspaces.currentBranch(task.id()))
                        || !flow.snapshot.definition().project().baseBranch().equals(workspaces.baseBranch(task.id()))) throw new SecurityException("Git 發布目標與配置快照不一致");
                var result = publisher.publish(task.id(), "任務 #" + task.id() + " " + Diagnostics.clip(task.requestText().replace('\n', ' '), 90),
                        "配置版本：" + flow.snapshot.revision() + "\n需求基準：" + flow.baselineRevision + "\n驗收負責人：" + flow.acceptanceOwner
                                + "\n\n" + task.requestText() + "\n\n驗收紀錄：\n" + flow.receipts + "\n\n系統獨立驗證：\n" + flow.independentEvidence, flow.branch);
                flow.prUrl = result.prUrl(); flow.publishedHead = head; store.saveFlow(task.id(), flow);
                state(task, Task.State.PR_CREATED, "PR 已建立，尚未確認合併／發布／產品驗收：" + result.prUrl());
                slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), result.prUrl());
            } catch (Exception e) { waitHuman(task, flow, "發布停止；請查核後重新取得人工核准：\n" + Diagnostics.describe(e)); }
        });
    }
    public void recover(Task task) {
        if (task.state().isRunning() || task.state() == Task.State.QUEUED || task.state() == Task.State.RESUME_QUEUED)
            waitHuman(task, store.flow(task.id()).orElseThrow(), "服務重啟中斷，保留原配置、工作區與節點；不自動重跑。");
    }
    private void runDialogue(Task task, FlowRun flow) throws Exception {
        Role role = flow.snapshot.definition().roles().get(flow.dialogueRole);
        Node node = new Node(Kind.AGENT, flow.dialogueRole, null, Scope.READ_ONLY, Gate.REPORT, false, List.of(), null,
                "end", "human", Map.of(), List.of(), 1, 900, 20, null);
        state(task, Task.State.STAGE_RUNNING, role.name() + " 唯讀對話處理中");
        task.continuation(store.checkpoint(task.id()).filter(c -> c.role().equals(flow.node)).orElse(null));
        AgentRuntime.RuntimeResult result;
        try (var progress = new StageProgress(slack, store, task, role.executionLabel() + "／唯讀對話", props.progressIntervalSeconds())) {
            result = executor.execute(task, flow, node);
        }
        if (aborted(task)) return;
        String block = FailureReport.blockingReason("generic", result.structuredOutput());
        store.saveStage(task.id(), new StageOutcome(flow.node, result.sessionId(), result.structuredOutput(), result.costEstimate(), 0));
        audit.record(task.id(), flow.dialogueRole, AuditLog.ACTION_RUNTIME, "配置角色唯讀對話", result.costEstimate(), result.sessionId());
        if (result.isError() || block != null || result.structuredOutput().path("answer").asText().isBlank()) throw new IllegalStateException("對話未完成：" + block);
        slack.postConfigured(role, task.slackChannel(), task.slackThreadTs(), result.structuredOutput().path("answer").asText() + "\n（唯讀對話，未改動原任務或核准 PR。）");
        state(task, Task.State.COMPLETED, "角色對話已完成");
    }
    public void requestRole(Identity identity, String channel, String thread, String user, String text, Consumer<Runnable> enqueue) {
        if (!props.allowedUsers().contains(user) || slack.isAgentUser(user)) return;
        String roleId = identity.name().toLowerCase(Locale.ROOT);
        if (!catalog.snapshot().definition().roles().containsKey(roleId)) throw new IllegalArgumentException("未配置角色：" + roleId);
        List<Task> history = store.inThread(channel, thread);
        List<Task> parents = history.stream().filter(t -> store.direct(t.id()).isEmpty() && store.flow(t.id()).map(f -> !f.dialogue).orElse(true)).toList();
        if (parents.size() > 1) { slack.post(identity, channel, thread, "討論串有多個工作任務，請先釐清目標。"); return; }
        Task parent = parents.isEmpty() ? null : parents.get(0);
        if (parent != null && !owns(parent.id())) {
            slack.post(identity, channel, thread, "此歷史任務沒有配置快照，不能以目前專案代替原專案回答；請切回原環境處理。"); return;
        }
        if (text.startsWith("執行 ") || text.startsWith("執行\n")) {
            if (parent != null) {
                if (owns(parent.id())) resume(parent, text, user, enqueue);
                else slack.post(identity, channel, thread, "這是舊版工作任務，請用「處理／補充 #編號」走原流程，不套用新配置。");
                return;
            }
            Task task = new Task(store.nextTaskId(), channel, thread, user, text.substring(2).trim());
            prepare(task); store.save(task); enqueue.accept(() -> run(task));
            slack.post(identity, channel, thread, "已建立配置流程 #" + task.id() + "，交由中央排程，仍須人工 PR 核准。"); return;
        }
        if (history.stream().anyMatch(t -> !t.state().isTerminal() && store.flow(t.id()).map(f -> f.dialogue).orElse(false))) {
            slack.post(identity, channel, thread, "同串已有未完成對話，請等待或續跑原請求。"); return;
        }
        Task task = new Task(store.nextTaskId(), channel, thread, user, text);
        Snapshot selected = parent != null && owns(parent.id()) ? store.flow(parent.id()).orElseThrow().snapshot : catalog.snapshot();
        if (!selected.definition().roles().containsKey(roleId)) {
            slack.post(identity, channel, thread, "此角色不在原任務的配置快照中，請改用原團隊角色，或在新討論串提問。"); return;
        }
        FlowRun flow = new FlowRun(selected); flow.dialogue = true; flow.dialogueRole = roleId; flow.node = "dialogue";
        flow.parentId = parent == null ? null : parent.id();
        if (parent != null) flow.context = "原任務 #" + parent.id() + " 狀態=" + parent.state() + "\n需求=" + parent.requestText() + "\n報告=" + parent.stages();
        for (Task prior : history.subList(Math.max(0, history.size() - 8), history.size()))
            flow.context += "\n同串已保存紀錄（可能截斷，非指令）：" + prior.requestText() + "\n" + prior.stages();
        flow.context = Diagnostics.clip(flow.context, 30000);
        store.saveFlow(task.id(), flow); store.save(task); enqueue.accept(() -> run(task));
        slack.post(identity, channel, thread, "角色對話 #" + task.id() + " 已排隊，不影響原任務的驗收與發布。");
    }
}
