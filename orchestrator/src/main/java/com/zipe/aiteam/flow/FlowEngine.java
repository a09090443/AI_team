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
    private boolean aborted(Task task) { return store.find(task.id()).map(t -> t.state() == Task.State.ABORTED).orElse(false); }
    private void state(Task task, Task.State state, String text) {
        if (aborted(task)) return;
        Task.State before = task.state(); task.state(state); store.save(task);
        audit.stateChange(task, before, state, AuditLog.ACTOR_SYSTEM);
        slack.update(Identity.SYSTEM, task.slackChannel(), task.statusMessageTs(), text, null);
    }
    private void waitHuman(Task task, FlowRun flow, String reason) {
        store.saveFlow(task.id(), flow);
        state(task, Task.State.AWAIT_HUMAN, "任務 #" + task.id() + " 等待人工，禁止發布");
        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                (flow.acceptanceOwner == null ? "" : "<@" + flow.acceptanceOwner + "> ")
              + "任務 #" + task.id() + " 已停止，目前沒有 agent 在處理。\n"
                + Diagnostics.clip(reason, 7000) + "\n請在原討論串 @主機器人或角色「補充 #" + task.id() + " 具體答案」。");
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
    private void handoff(Task task, FlowRun flow, Node from, Node to, boolean blocked) {
        if (to == null || to.kind() != Kind.AGENT || from.role().equals(to.role())) return;
        var roles = flow.snapshot.definition().roles();
        Role fromRole = roles.get(from.role()), toRole = roles.get(to.role());
        if (fromRole == null || toRole == null) return;
        slack.postConfigured(fromRole, task.slackChannel(), task.slackThreadTs(),
                slack.mention(Identity.of(to.role(), toRole.name())) + " "
              + (blocked ? "我這邊受阻，退回給你處理。" : "我這邊完成了，換你接手。")
              + "（節點 " + from.role() + " → " + to.role() + "）");
    }
    public void run(Task task) {
        FlowRun flow = store.flow(task.id()).orElseThrow();
        try {
            if (aborted(task)) return;
            if (task.statusMessageTs() == null) { task.statusMessageTs(slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "配置流程 #" + task.id() + " 開始處理")); store.save(task); }
            while (!aborted(task)) {
                if (flow.dialogue) { runDialogue(task, flow); return; }
                if (flow.node.equals("end")) { state(task, Task.State.COMPLETED, "任務 #" + task.id() + " 已結束（未發布）"); return; }
                Node node = flow.snapshot.definition().nodes().get(flow.node);
                if (node == null) throw new IllegalStateException("快照缺少節點，禁止猜測恢復路徑");
                if (node.kind() == Kind.END) { state(task, Task.State.COMPLETED, "任務已結束（未發布）"); return; }
                if (node.kind() == Kind.APPROVAL) {
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
                    if (node.requires() != null) for (String dependency : node.requires()) {
                        String invalid = FlowGates.blockingReason(flow.snapshot.definition().nodes().get(dependency), flow.receipts.get(dependency), flow);
                        if (invalid != null) throw new IllegalStateException("節點 " + flow.node + " 缺少有效前置契約 " + dependency + "：" + invalid);
                    }
                    if (++flow.transitions > flow.snapshot.definition().maxTransitions()
                            || flow.visits.merge(flow.node, 1, Integer::sum) > node.maxVisits()) {
                        waitHuman(task, flow, "已達配置的節點／流程重試上限，禁止自動重跑。請人工檢查並建立新任務。"); return;
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
                }
                if (node.workspace()) requireWorkspace(task, flow);
                state(task, Task.State.STAGE_RUNNING, "任務 #" + task.id() + " 節點 " + flow.node + "／角色 " + node.role() + " 處理中");
                if (node.gate() == Gate.REVIEW && flow.snapshot.definition().version() >= 2) {
                    if (flow.independentEvidence == null) {
                        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "任務 #" + task.id() + " 正由系統在全新 clone 執行獨立驗證；通過後才交審查角色。");
                        try (var progress = new StageProgress(slack, store, task, "系統獨立驗證", props.progressIntervalSeconds())) {
                            flow.independentEvidence = verifier.verify(task, flow);
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
                        flow.snapshot.definition().roles().get(node.role()).name() + "／" + flow.node, props.progressIntervalSeconds())) {
                    result = executor.execute(task, flow, node);
                }
                catch (Exception e) { auditScope(task, flow, node); throw e; }
                if (aborted(task)) return;
                auditScope(task, flow, node);
                if (result.isError()) throw new IllegalStateException("runtime 回報 isError；診斷：" + result.diagnosticsLog());
                JsonNode report = result.structuredOutput();
                store.saveStage(task.id(), new StageOutcome(flow.node, result.sessionId(), report, result.costEstimate(), (System.nanoTime() - started) / 1_000_000_000));
                audit.record(task.id(), node.role(), AuditLog.ACTION_RUNTIME, "節點=" + flow.node + "；配置=" + flow.snapshot.revision(), result.costEstimate(), result.sessionId());
                if (result.permissionDenials() != null && !result.permissionDenials().isEmpty()) audit.record(task.id(), node.role(), AuditLog.ACTION_DENIED, Diagnostics.toolFailures(result.permissionDenials()));
                String block = FlowGates.blockingReason(node, report, flow);
                slack.postConfigured(flow.snapshot.definition().roles().get(node.role()), task.slackChannel(), task.slackThreadTs(),
                        "節點 " + flow.node + "\n" + Diagnostics.clip(report == null ? "沒有有效報告" : report.toPrettyString(), 9000));
                String next;
                if (block == null) {
                    flow.receipts.put(flow.node, report);
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
                    flow.context = Diagnostics.clip(flow.context + "\n節點 " + flow.node + " 受阻：\n" + block + "\n報告：" + report, 20000);
                    next = node.blocked();
                }
                flow.activeNode = null; task.continuation(null);
                if (next.equals("human")) { waitHuman(task, flow, block == null ? report.toPrettyString() : block); return; }
                Node target = flow.snapshot.definition().nodes().get(next);
                if (target != null && target.gate() == Gate.REQUIREMENTS && flow.baselineRevision != null) {
                    flow.changeTarget = next;
                    waitHuman(task, flow, "PM 提議重新確認需求，尚未授權變更。只有原需求負責人可補充「核准需求變更 具體變更內容」。\n" + report); return;
                }
                handoff(task, flow, node, target, block != null);
                flow.node = next; store.saveFlow(task.id(), flow);
            }
        } catch (Exception e) {
            if (aborted(task)) return;
            QuotaExceeded quota = QuotaExceeded.find(e);
            if (quota != null && store.checkpoint(task.id()).isPresent()) {
                store.saveFlow(task.id(), flow);
                store.saveCheckpoint(store.checkpoint(task.id()).orElseThrow().withReason(quota.getMessage()));
                state(task, Task.State.WAITING_QUOTA, "任務 #" + task.id() + " 等待額度，原節點與配置已保存");
                String message = "任務 #" + task.id() + " 已因額度停止；恢復後按繼續，不會切換角色、runtime 或配置。\n" + Diagnostics.describe(e);
                slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), message, SlackBlocks.quotaBlocks(task.id(), message));
            } else {
                audit.record(task.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_ERROR, Diagnostics.describe(e));
                waitHuman(task, flow, Diagnostics.describe(e));
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
        if (flow.changeTarget != null && (!user.equals(flow.acceptanceOwner) || feedback == null
                || !feedback.startsWith("核准需求變更 ") || feedback.substring("核准需求變更 ".length()).isBlank())) {
            slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "尚未核准需求變更；請由 <@" + flow.acceptanceOwner + "> 補充「核准需求變更 具體變更內容」。一般補充不會改寫需求基準。"); return;
        }
        if (!store.claimState(task.id(), from, Task.State.RESUME_QUEUED)) return;
        if (flow.changeTarget != null) {
            flow.node = flow.changeTarget; flow.changeTarget = null; flow.changeAuthorized = true; flow.activeNode = null;
            audit.record(task.id(), user, AuditLog.ACTION_STATE, "核准需求變更，原基準=" + flow.baselineRevision + "；內容=" + feedback);
        }
        flow.context = Diagnostics.clip(flow.context + "\n使用者 " + user + " 補充：" + Objects.toString(feedback, "額度恢復，續跑原階段"), 20000);
        store.saveFlow(task.id(), flow);
        enqueue.accept(() -> run(store.find(task.id()).orElseThrow()));
        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "任務 #" + task.id() + " 已排隊，將使用原配置與節點續跑，不代表已核准 PR。");
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
        try (var progress = new StageProgress(slack, store, task, role.name() + "／唯讀對話", props.progressIntervalSeconds())) {
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
