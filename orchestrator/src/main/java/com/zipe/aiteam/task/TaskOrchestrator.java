package com.zipe.aiteam.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.zipe.aiteam.agent.StageRunner;
import com.zipe.aiteam.agent.StageSpec;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.publish.PullRequestPublisher;
import com.zipe.aiteam.runtime.AgentRuntime;
import com.zipe.aiteam.runtime.Diagnostics;
import com.zipe.aiteam.runtime.QuotaExceeded;
import com.zipe.aiteam.sandbox.ExecutionSandbox;
import com.zipe.aiteam.slack.SlackBlocks;
import com.zipe.aiteam.slack.SlackNotifier;
import com.zipe.aiteam.slack.SlackNotifier.Identity;
import com.zipe.aiteam.store.AuditLog;
import com.zipe.aiteam.store.TaskStore;
import com.zipe.aiteam.workspace.WorkspaceManager;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 任務治理層：管線串接、並行控制、狀態機、審批閘門、稽核。
 *
 * 閘門策略為「全自動，只在推 PR 前停」。中間階段不需要人工核准，
 * 但保留兩個自動判斷的停止點（見 shouldStopAfterPm），
 * 用來避免把額度浪費在沒有意義或 PM 自己都沒把握的任務上。
 *
 * 並行槽只在實際執行 Agent 時持有，等待人類期間必定釋放。
 */
@Component
public class TaskOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TaskOrchestrator.class);

    private final AiTeamProperties props;
    private final StageRunner stageRunner;
    private final SlackNotifier slack;
    private final TaskStore store;
    private final AuditLog audit;
    private final ExecutionSandbox sandbox;
    private final WorkspaceManager workspaces;
    private final PullRequestPublisher publisher;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.zipe.aiteam.flow.FlowEngine flowEngine;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.zipe.aiteam.flow.ErrorExplainer explainer;

    private final LinkedBlockingQueue<Runnable> work = new LinkedBlockingQueue<>();
    private final Semaphore slots;
    private final Thread worker;
    private volatile boolean running = true;

    public TaskOrchestrator(AiTeamProperties props, StageRunner stageRunner, SlackNotifier slack,
                            TaskStore store, AuditLog audit, ExecutionSandbox sandbox,
                            WorkspaceManager workspaces, PullRequestPublisher publisher) {
        this.props = props;
        this.stageRunner = stageRunner;
        this.slack = slack;
        this.store = store;
        this.audit = audit;
        this.sandbox = sandbox;
        this.workspaces = workspaces;
        this.publisher = publisher;
        this.slots = new Semaphore(props.maxConcurrency());
        this.worker = new Thread(this::loop, "ai-team-worker");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public Optional<Task> lookup(String taskId) { return store.find(taskId); }

    private boolean legacyBlockedByNewConfiguration(Task task) {
        if (flowEngine == null || !flowEngine.enabled() || flowEngine.owns(task.id())) return false;
        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "任務 #" + task.id()
                + " 是沒有配置快照的歷史任務。為避免套用新專案／新角色，已拒絕執行或發布。"
                + "請以原專案環境停用 AI_TEAM_DEFINITION 處理此任務，或另行規劃明確遷移；未重新 clone。");
        return true;
    }

    /** 所有角色的入口仍由中央授權、佇列與沙箱治理；對話不等於實作授權。 */
    public synchronized void requestRole(Identity role, String channel, String thread, String user, String text) {
        if (role == Identity.SYSTEM || !props.allowedUsers().contains(user) || slack.isAgentUser(user)) return;
        if (props.channelId() != null && !props.channelId().isBlank() && !props.channelId().equals(channel)) return;
        if (flowEngine != null && flowEngine.enabled()) {
            flowEngine.requestRole(role, channel, thread, user, text, job -> work.offer(job));
            return;
        }
        List<Task> history = store.inThread(channel, thread);
        List<Task> parents = history.stream().filter(t -> store.direct(t.id()).isEmpty()).toList();
        if (parents.size() > 1) {
            slack.post(role, channel, thread, "此討論串有多個工作任務，無法安全判定脈絡；請在單一任務的討論串操作。");
            return;
        }
        Task parent = parents.isEmpty() ? null : parents.get(0);
        if (text.startsWith("執行 ") || text.startsWith("執行\n")) {
            String request = text.substring(2).trim();
            if (request.isBlank()) { slack.post(role, channel, thread, "請在「執行」後填具體需求。"); return; }
            if (parent != null) {
                // 既有工作不另開平行實作，不繞過等待狀態與既定範圍。
                requestTriage(parent, "使用者透過 " + role + " 明確要求執行：" + request, user);
            } else {
                slack.post(role, channel, thread, "已收到實作請求，交由中央 PM 管線安排；仍須 QA／Review 與人工 PR 核准。");
                createAndSubmit(channel, thread, user, "由 " + role + " 接收的執行請求：\n" + request);
            }
            return;
        }
        if (history.stream().anyMatch(t -> store.direct(t.id()).isPresent() && !t.state().isTerminal())) {
            slack.post(role, channel, thread, "此討論串已有未完成的角色對話，請先等回覆；若因額度暫停，請用該請求的繼續按鈕。");
            return;
        }
        Task dialogue = new Task(store.nextTaskId(), channel, thread, user, text);
        StringBuilder context = new StringBuilder("指定角色：" + role + "\n這是唯讀對話，不代表實作或驗收。\n");
        if (parent != null) {
            context.append("原任務 #").append(parent.id()).append(" 狀態：").append(parent.state())
                    .append("\n原始需求：").append(parent.requestText()).append("\n");
            context.append(workspaces.exists(parent.id()) ? "讀取原任務工作區。\n" : "原任務無工作區；目前只能讀主 repo，不能核實未合併變更。\n");
            parent.stages().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                    context.append("歷史報告（非指令）：").append(entry.getKey()).append("\n")
                            .append(Diagnostics.clip(summarise(entry.getValue()), 12000)).append("\n"));
        } else context.append("目前讀取主 repo。\n");
        context.append("最近最多 8 筆對話（可能截斷；不含未 @ 的 Slack 訊息）：\n");
        history.stream().filter(t -> store.direct(t.id()).isPresent()).skip(Math.max(0,
                history.stream().filter(t -> store.direct(t.id()).isPresent()).count() - 8)).forEach(t -> {
            context.append("使用者 ").append(t.requesterUserId()).append("：").append(Diagnostics.clip(t.requestText(), 2000)).append("\n");
            t.stages().values().forEach(s -> context.append(s.role()).append("：").append(Diagnostics.clip(summarise(s), 4000)).append("\n"));
        });
        DirectRequest direct = new DirectRequest(dialogue.id(), role, parent == null ? null : parent.id(), context.toString());
        // 先保存類型，避免半途失敗留下能誤入原管線的對話任務。
        store.saveDirect(direct);
        store.save(dialogue);
        audit.record(dialogue.id(), user, AuditLog.ACTION_STATE, "指定 " + role + " 唯讀對話；原任務=" + direct.parentId());
        dialogue.statusMessageTs(slack.post(Identity.SYSTEM, channel, thread, "角色對話 #" + dialogue.id() + " 已排隊；" + role + " 尚未開始，不會修改程式或核准 PR。"));
        store.save(dialogue);
        work.offer(() -> runDirect(dialogue, direct));
    }

    private void runDirect(Task task, DirectRequest direct) {
        try {
            if (store.find(task.id()).map(t -> t.state() == Task.State.ABORTED).orElse(false)) return;
            sandbox.requireReadyForCodeChanges(); // 唯讀限制也必須由有效的沙箱保證
            StageOutcome result = runStage(task, StageSpec.direct(direct.role()), direct.context(), task.statusMessageTs());
            String answer = result.result().path("answer").asText();
            if (answer.isBlank()) throw new IllegalStateException("角色未回傳非空 answer，請查 stage_result 與 session");
            slack.post(direct.role(), task.slackChannel(), task.slackThreadTs(), Diagnostics.clip(answer, 1200)
                    + "\n（簡要回覆；如需細節，請在同一討論串繼續追問該角色。"
                    + "本次未改動原任務、未派工或核准 PR。）");
            finish(task, Task.State.COMPLETED, AuditLog.ACTION_STATE, "角色唯讀對話完成");
            slack.update(Identity.SYSTEM, task.slackChannel(), task.statusMessageTs(), "角色對話 #" + task.id() + " 已完成", null);
        } catch (Exception e) {
            if (task.state() == Task.State.ABORTED) return;
            if (pauseForQuota(task, e)) return;
            task.failure(Diagnostics.describe(e)); store.save(task);
            audit.record(task.id(), direct.role().name(), AuditLog.ACTION_ERROR, Diagnostics.describe(e));
            String explained = explainer == null ? Diagnostics.forUser(e)
                    : explainer.explain(task, "回答你的提問時", e, List.of("在同一討論串重新提問"));
            slack.post(direct.role(), task.slackChannel(), task.slackThreadTs(), "對話 #" + task.id()
                    + " 沒有完成，目前沒有 agent 在處理；原本的工作任務沒有被改動。\n\n" + explained);
            slack.update(Identity.SYSTEM, task.slackChannel(), task.statusMessageTs(), "角色對話 #" + task.id() + " 沒有完成，原因見討論串說明", null);
        }
    }

    // ------------------------------------------------------------------ 啟動恢復

    public void recoverOnStartup() {
        recoverTasks();
        purgeWorkspaces();
    }

    private void recoverTasks() {
        List<Task> unfinished = store.findUnfinished();
        if (unfinished.isEmpty()) {
            log.info("啟動恢復：沒有未完成的任務");
            return;
        }
        log.info("啟動恢復：發現 {} 個未完成的任務", unfinished.size());

        for (Task t : unfinished) {
            if (flowEngine != null && flowEngine.owns(t.id())) { flowEngine.recover(t); continue; }
            if (legacyBlockedByNewConfiguration(t)) {
                if (t.state().isRunning() || t.state() == Task.State.QUEUED || t.state() == Task.State.RESUME_QUEUED || t.state() == Task.State.TRIAGE_QUEUED) {
                    t.state(Task.State.AWAIT_HUMAN); store.save(t);
                }
                audit.record(t.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_RECOVERY, "新配置模式拒絕自動遷移無快照的 legacy 任務");
                continue;
            }
            if (store.direct(t.id()).isPresent() && (t.state().isRunning() || t.state() == Task.State.QUEUED)) {
                t.failure("角色對話因服務重啟中斷，未自動重跑，請重新 @角色提問");
                store.save(t);
                slack.post(Identity.SYSTEM, t.slackChannel(), t.slackThreadTs(), "對話 #" + t.id() + " 因重啟停止；目前沒有 agent 處理此對話，請重新 @角色提問。");
                continue;
            }
            if (t.state() == Task.State.TRIAGE_QUEUED) {
                finish(t, Task.State.AWAIT_HUMAN, AuditLog.ACTION_RECOVERY, "PM 派工前重啟，請重新送出「處理 #" + t.id() + "」");
                slack.post(Identity.SYSTEM, t.slackChannel(), t.slackThreadTs(), "PM 派工因服務重啟暫停，目前無 agent 處理；請在原討論串 @主機器人「處理 #" + t.id() + "」。");
                continue;
            }
            if (t.state().isRunning() && store.remediation(t.id()).isPresent()) {
                finish(t, Task.State.AWAIT_HUMAN, AuditLog.ACTION_RECOVERY, "PM 修正流程被服務重啟中斷，保留工作區");
                slack.post(Identity.SYSTEM, t.slackChannel(), t.slackThreadTs(), "問題處理因服務重啟停止，目前無 agent 處理，禁止發布。請人工查核後在此 @主機器人「處理 #" + t.id() + "」。");
                continue;
            }
            if (t.state() == Task.State.RESUME_QUEUED) {
                t.state(Task.State.WAITING_QUOTA);
                store.save(t);
                postQuotaPause(t, "服務重啟前尚未開始續跑；現場保留，請再次按繼續任務。");
                continue;
            }
            if (t.state().isRunning()) {
                Task.State from = t.state();
                t.failure("服務重啟時中斷（原狀態 " + from + "）");
                store.save(t);
                audit.record(t.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_RECOVERY,
                        "重啟時中斷：" + from + " → FAILED");
                log.warn("{} 上次執行到一半被中斷，已標記 FAILED", t);
            } else if (t.state() == Task.State.QUEUED) {
                audit.record(t.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_RECOVERY, "重新排入佇列");
                work.offer(() -> runPipeline(t));
                log.info("{} 重新排入佇列", t);
            } else {
                audit.record(t.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_RECOVERY,
                        "保留等待狀態 " + t.state());
                log.info("{} 維持等待中，按鈕仍有效", t);
            }
        }
    }

    /**
     * 清掉不再需要的工作區。每個約 9 MB，不清會無限累積。
     *
     * 保留的兩種：等待中的任務（可能還要推 PR）、以及 FAILED 的任務
     * —— 失敗現場是診斷用的，砍掉就查不出當時發生什麼事。
     * 其餘（COMPLETED、ABORTED、以及資料庫裡已經查無此任務的孤兒）一律刪除。
     */
    private void purgeWorkspaces() {
        List<String> ids = workspaces.listTaskIds();
        if (ids.isEmpty()) return;

        int removed = 0, kept = 0;
        for (String id : ids) {
            Optional<Task> t = store.find(id);
            // 啟用新配置不能順便清除舊模式現場；歷史任務要由明確清理操作處理。
            if (t.isPresent() && flowEngine != null && flowEngine.enabled() && !flowEngine.owns(id)) {
                kept++;
                continue;
            }
            boolean keep = t.map(x -> x.state().isWaiting()
                                   || x.state() == Task.State.PR_CREATED
                                   || x.state() == Task.State.FAILED
                                   || (x.state() == Task.State.ABORTED
                                       && x.stage("code_review").map(o ->
                                           ReviewGate.blockingReason(o.result()) != null).orElse(false)))
                    .orElse(false);
            if (keep) {
                kept++;
                continue;
            }
            try {
                workspaces.discard(id);
                removed++;
            } catch (Exception e) {
                log.warn("清除工作區 task-{} 失敗：{}", id, e.getMessage());
            }
        }
        log.info("啟動清理：工作區共 {} 個，刪除 {} 個，保留 {} 個（等待中或失敗待查）",
                ids.size(), removed, kept);
    }

    // ------------------------------------------------------------------ 入口

    public Task createAndSubmit(String channel, String threadTs, String userId, String text) {
        if (flowEngine != null && flowEngine.enabled()) {
            Optional<Task> existing = store.inThread(channel, threadTs).stream()
                    .filter(t -> store.direct(t.id()).isEmpty() && store.flow(t.id()).map(f -> !f.dialogue).orElse(true)).findFirst();
            if (existing.isPresent()) {
                slack.post(Identity.SYSTEM, channel, threadTs, threadTakenMessage(existing.get()));
                return existing.get();
            }
        }
        Task task = new Task(store.nextTaskId(), channel, threadTs, userId, text);
        if (flowEngine != null && flowEngine.enabled()) flowEngine.prepare(task);
        store.save(task);
        audit.record(task.id(), userId, AuditLog.ACTION_RECEIVED, text);
        log.info("{} 已排入佇列，來自 {}", task, userId);
        work.offer(() -> runPipeline(task));
        return task;
    }

    /**
     * 同串再 @主機器人時的說明。「補充」只在 AWAIT_HUMAN／WAITING_QUOTA 有效（FlowEngine.resume），
     * 其他狀態不能叫使用者用它續跑，否則照做只會再收到一次拒絕。
     */
    static String threadTakenMessage(Task task) {
        String id = task.id();
        Task.State state = task.state();
        if (state.isTerminal())
            return "此討論串的任務 #" + id + " 已結束（" + state + "），未建立新任務。"
                    + "追問請直接 @角色；新需求請在頻道開新討論串。";
        if (state == Task.State.AWAIT_HUMAN || state == Task.State.WAITING_QUOTA)
            return "此討論串已關聯任務 #" + id + "（" + state + "），未建立平行任務。"
                    + "要回答或續跑請用「補充 #" + id + " 答案」，查可用選項用「補充 #" + id + " 狀態」；"
                    + "追問請直接 @角色；新需求請在頻道開新討論串。";
        return "此討論串已關聯任務 #" + id + "（目前 " + state + "），未建立平行任務。"
                + "追問請直接 @角色；新需求請在頻道開新討論串。";
    }

    public void abort(Task task, String byUser) {
        Task.State from = task.state();
        task.state(Task.State.ABORTED);
        store.save(task);
        audit.stateChange(task, from, task.state(), byUser);
        // 以前只改狀態，執行中的 agent 會在背景繼續跑到結束。
        if (from.isRunning()) com.zipe.aiteam.sandbox.TaskUnits.stop(props.unitPrefix(), task.id());
        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                "🛑 任務 #" + task.id() + " 已由 <@" + byUser + "> 中止。");
    }

    /** 只停目前這一輪，任務保留可續跑；與 abort 不同，見 FlowEngine.interrupt。 */
    public void interrupt(Task task, String byUser) {
        if (flowEngine != null && flowEngine.owns(task.id())) { flowEngine.interrupt(task, byUser); return; }
        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                "任務 #" + task.id() + " 是舊版管線任務，不支援中斷這一輪；只能等它結束或中止任務。");
    }

    /**
     * 核准推分支與開 PR —— 全自動管線唯一需要人按下去的動作。
     *
     * 實際的推送與 API 呼叫要好幾秒，不能在這裡做：
     * SlackListener 是先處理再 ack，擋住就會超過 Slack 的 3 秒上限而收到重送。
     * 因此這裡只記錄與回覆，真正的工作丟給工作執行緒。
     */
    public void approvePr(Task task, String byUser) {
        if (flowEngine != null && flowEngine.owns(task.id())) { flowEngine.approve(task, byUser, job -> work.offer(job)); return; }
        if (legacyBlockedByNewConfiguration(task)) return;
        if (store.direct(task.id()).isPresent()) {
            slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "角色對話不能提交 PR，請操作原工作任務。");
            return;
        }
        task = store.find(task.id()).orElse(task);
        if (task.state() != Task.State.AWAIT_PR_APPROVAL) return;
        if (blockUnverifiedReview(task)) return;
        audit.record(task.id(), byUser, AuditLog.ACTION_BUTTON, "核准推送分支與開 PR");
        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                "✅ 任務 #" + task.id() + " 已由 <@" + byUser + "> 核准，正在推送分支並開 PR…");
        Task approved = task;
        work.offer(() -> publish(approved, byUser));
    }

    /**
     * 失敗時刻意不把任務標成 FAILED，狀態留在 AWAIT_PR_APPROVAL。
     *
     * 因為工作本身是好的，只是沒送出去 —— 多半是 token 沒設或權限不足。
     * 保持在等待狀態，使用者修好設定後可以再按一次核准，不必整個重跑。
     */
    private void publish(Task task, String byUser) {
        task = store.find(task.id()).orElse(task);
        if (task.state() != Task.State.AWAIT_PR_APPROVAL) return;
        if (blockUnverifiedReview(task)) return;
        try {
            PullRequestPublisher.Result r =
                    publisher.publish(task.id(), prTitle(task), prBody(task, byUser));

            finish(task, Task.State.COMPLETED, AuditLog.ACTION_STATE,
                    "PR 已建立：" + r.prUrl());
            slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                    "🎉 任務 #" + task.id() + " 的 PR 已建立\n"
                  + r.prUrl() + "\n"
                  + "分支 `" + r.branch() + "`　累計花費 US$"
                  + String.format("%.4f", task.totalCost()));

            try {
                workspaces.discard(task.id());
            } catch (Exception e) {
                log.warn("PR 已建立但清理工作區失敗：{}", e.getMessage());
            }
        } catch (Exception e) {
            log.error("{} 推送或開 PR 失敗", task, e);
            audit.record(task.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_ERROR,
                    "推送或開 PR 失敗：" + e.getMessage());
            slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                    "❌ 任務 #" + task.id() + " 推送分支或開 PR 失敗\n```\n"
                  + abbreviate(e.getMessage()) + "\n```\n"
                  + "任務仍停在等待核准的狀態，修正後可以再按一次核准，不需要整個重跑。");
        }
    }

    /** PR 標題優先用 Backend 的 commit 主旨 —— 它已經照 repository 的慣例寫好了。 */
    private static String prTitle(Task task) {
        String fromCommit = task.stage("backend")
                .map(StageOutcome::result)
                .map(r -> r.path("commits"))
                .filter(c -> c.isArray() && !c.isEmpty())
                .map(c -> c.get(0).path("message").asText(""))
                .filter(s -> !s.isBlank())
                .orElse(null);
        if (fromCommit != null) return fromCommit;

        String pmSummary = task.stage("pm")
                .map(o -> o.result().path("summary").asText(""))
                .orElse("");
        String fallback = pmSummary.isBlank() ? task.requestText() : pmSummary;
        return "[AI Team #" + task.id() + "] " + clip(fallback, 60);
    }

    private String prBody(Task task, String byUser) {
        StringBuilder sb = new StringBuilder();
        sb.append("由 AI 團隊產生，經人工核准後開立。\n\n");
        sb.append("| 項目 | 內容 |\n|---|---|\n");
        sb.append("| 任務編號 | #").append(task.id()).append(" |\n");
        sb.append("| 需求提出 | <@").append(task.requesterUserId()).append("> |\n");
        sb.append("| 核准者 | <@").append(byUser).append("> |\n");
        sb.append("| 分支 | `").append(workspaces.branchFor(task.id())).append("` |\n");
        sb.append("| 花費 | US$").append(String.format("%.4f", task.totalCost())).append(" |\n\n");

        sb.append("## 原始需求\n\n").append(task.requestText()).append("\n\n");

        appendStageSection(sb, task, "pm", "PM 分析", "summary");
        appendStageSection(sb, task, "architecture", "架構決策", "proposal");
        task.stage("qa_plan").ifPresent(o -> sb.append("## 實作前測試計畫\n\n```json\n")
                .append(summarise(o)).append("\n```\n\n"));
        appendStageSection(sb, task, "backend", "實作", "summary");
        appendStageSection(sb, task, "qa", "驗收", "summary");

        // QA 的保留意見必須進 PR —— 它們是給審查者的訊息，留在 Slack 就等於沒說
        task.stage("qa").ifPresent(o -> {
            sb.append("## 情境執行證據\n\n```json\n")
                    .append(o.result().path("scenario_results").toPrettyString()).append("\n```\n\n");
            JsonNode findings = o.result().get("findings");
            if (findings != null && findings.isArray() && !findings.isEmpty()) {
                sb.append("## QA 提出的問題\n\n");
                for (JsonNode f : findings) {
                    sb.append("- **").append(f.path("severity").asText()).append("** ")
                      .append(f.path("detail").asText()).append("\n");
                }
                sb.append("\n");
            }
            JsonNode unc = o.result().get("uncertainties");
            if (unc != null && unc.isArray() && !unc.isEmpty()) {
                sb.append("## QA 無法驗證的部分\n\n");
                for (JsonNode u : unc) sb.append("- ").append(u.asText()).append("\n");
                sb.append("\n");
            }
        });

        return sb.toString();
    }

    private static void appendStageSection(StringBuilder sb, Task task,
                                           String role, String heading, String field) {
        task.stage(role).ifPresent(o -> {
            String text = o.result().path(field).asText("");
            if (!text.isBlank()) {
                sb.append("## ").append(heading).append("\n\n").append(text).append("\n\n");
            }
        });
    }

    /** PR 標題不能有換行。刻意不用正則，避開跳脫字元，也比 \s 涵蓋更完整。 */
    private static String clip(String s, int max) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        boolean prevSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean space = Character.isWhitespace(c);
            if (space) {
                if (!prevSpace && b.length() > 0) b.append(' ');
            } else {
                b.append(c);
            }
            prevSpace = space;
        }
        String one = b.toString().trim();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }


    public void redo(Task task, String feedback, String byUser) {
        if (legacyBlockedByNewConfiguration(task)) return;
        if (flowEngine != null && flowEngine.owns(task.id())) {
            slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "配置流程不以舊版重做按鈕重新 clone；請補充續跑，或在新討論串建立新任務。"); return;
        }
        if (store.direct(task.id()).isPresent()) {
            slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "角色對話請重新 @角色提問，不使用工作任務的重做流程。");
            return;
        }
        if (task.state() == Task.State.WAITING_QUOTA || task.state() == Task.State.RESUME_QUEUED
                || task.state() == Task.State.TRIAGE_QUEUED || task.state().isRunning()) {
            slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                    "目前不能重新開始。額度暫停請使用「繼續任務」，以保留原工作區與階段。");
            return;
        }
        if (task.incrementRedo() > Task.MAX_REDO) {
            task.failure("重做次數已達上限 " + Task.MAX_REDO);
            store.save(task);
            audit.record(task.id(), byUser, AuditLog.ACTION_ERROR, "重做次數已達上限");
            slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                    "⛔ 任務 #" + task.id() + " 重做次數已達上限（" + Task.MAX_REDO + " 次），已停止。");
            return;
        }
        store.save(task);
        audit.record(task.id(), byUser, AuditLog.ACTION_BUTTON,
                "要求重做（第 " + task.redoCount() + " 次）");
        store.clearRemediation(task.id());
        task.continuation(null);
        task.qaBaseline(null);
        work.offer(() -> runPipeline(task));
    }

    public void continueTask(Task task, String byUser) {
        if (flowEngine != null && flowEngine.owns(task.id())) { flowEngine.resume(task, null, byUser, job -> work.offer(job)); return; }
        if (legacyBlockedByNewConfiguration(task)) return;
        if (store.checkpoint(task.id()).isEmpty()) return;
        if (!store.claimContinuation(task.id())) return;
        audit.record(task.id(), byUser, AuditLog.ACTION_BUTTON, "繼續額度中斷任務，保留原工作區");
        work.offer(() -> {
            Task current = store.find(task.id()).orElseThrow();
            if (current.state() != Task.State.RESUME_QUEUED) return;
            current.continuation(store.checkpoint(task.id()).orElseThrow());
            DirectRequest direct = store.direct(current.id()).orElse(null);
            if (direct != null) { runDirect(current, direct); return; }
            Remediation repair = store.remediation(current.id()).orElse(null);
            if (repair != null && repair.active()) runRemediation(current, repair);
            else runPipeline(current);
        });
        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                "已排入續跑佇列；原工作區保留。若額度仍不足，會再次暫停。");
    }

    private boolean pauseForQuota(Task task, Throwable failure) {
        QuotaExceeded quota = QuotaExceeded.find(failure);
        if (quota == null) return false;
        Checkpoint checkpoint = store.checkpoint(task.id()).orElse(null);
        if (checkpoint == null) return false;
        store.saveCheckpoint(checkpoint.withReason(quota.getMessage()));
        finish(task, Task.State.WAITING_QUOTA, AuditLog.ACTION_STATE, quota.getMessage());
        postQuotaPause(task, "角色：" + checkpoint.role() + "；runtime：" + checkpoint.runtime()
                + "；模型：" + checkpoint.model() + "\n" + quota.getMessage()
                + "\n已保留原工作區、未提交修改、上游報告與 session=" + checkpoint.sessionId());
        return true;
    }

    private void postQuotaPause(Task task, String detail) {
        slack.update(Identity.SYSTEM, task.slackChannel(), task.statusMessageTs(),
                "⏸️ 任務 #" + task.id() + " 等待額度恢復，禁止發布", null);
        String message = slack.mention(Identity.PM) + " <@" + task.requesterUserId() + ">\n"
                + "任務 #" + task.id() + " 已因額度暫停。\n" + Diagnostics.safe(detail)
                + "\n系統將安排自動續跑並回報时间，也可按「繼續任務」。沿用原供應商與 session。";
        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), message,
                SlackBlocks.quotaBlocks(task.id(), message));
    }

    // ------------------------------------------------------------------ 管線

    private void loop() {
        while (running) {
            try {
                Runnable job = work.poll(1, TimeUnit.SECONDS);
                if (job != null) job.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("工作迴圈發生未預期的例外", e);
            }
        }
    }

    /**
     * 全自動管線。中途不需要人工介入，除非：
     *   ・PM 判定為純詢問或不需要下游角色 → 直接結束
     *   ・PM 自陳把握度低 → 停下來問人
     *   ・任一階段失敗 → 停止並回報
     */
    private void runPipeline(Task task) {
        if (flowEngine != null && flowEngine.owns(task.id())) { flowEngine.run(task); return; }
        if (legacyBlockedByNewConfiguration(task)) return;
        String progressTs = slack.post(Identity.SYSTEM,
                task.slackChannel(), task.slackThreadTs(),
                "🗒️ 任務 #" + task.id() + " 已排入佇列…");
        task.statusMessageTs(progressTs);

        try {
            // ---- PM ----
            StageOutcome pm = runStage(task, StageSpec.PM, null, progressTs);
            postStageResult(task, StageSpec.PM, pm);

            if (stopAfterPm(task, pm)) return;

            // ---- Architecture ----
            StageOutcome arch = null;
            if (needsRole(pm, "architecture")) {
                handoff(task, Identity.PM, Identity.ARCHITECTURE,
                        "需求已經拆解完成，這次需要先定案架構再動手。"
                      + modulesNote(pm) + "我的分析在上一則。");
                arch = runStage(task, StageSpec.ARCHITECTURE, summarise(pm), progressTs);
                postStageResult(task, StageSpec.ARCHITECTURE, arch);
            } else {
                slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                        "ℹ️ PM 判定不需要 Architecture Agent，略過該階段。");
            }

            // ---- Backend ----
            if (!needsRole(pm, "backend")) {
                finish(task, Task.State.COMPLETED, AuditLog.ACTION_STATE,
                        "PM 判定不需要 Backend，管線結束");
                slack.update(Identity.SYSTEM, task.slackChannel(), progressTs,
                        "✅ 任務 #" + task.id() + " 已完成", null);
                slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                        "✅ 任務 #" + task.id() + " 已完成 —— PM 判定不需要實作階段。\n"
                      + "花費：US$" + String.format("%.4f", task.totalCost()));
                return;
            }

            StageOutcome plan = null;
            if (needsRole(pm, "qa")) {
                handoff(task, arch != null ? Identity.ARCHITECTURE : Identity.PM, Identity.QA,
                        "請先依需求與驗收條件制定情境、測試方式、資料與預期結果，再交給 Backend 實作。");
                plan = runStage(task, StageSpec.QA_PLAN, upstreamFor(pm, arch), progressTs);
                postStageResult(task, StageSpec.QA_PLAN, plan);
            }

            // 工作區必須在 Backend 之前備妥：沙箱掛載不存在的路徑會讓 unit 起不來，
            // 而空目錄不是 git repository，agent 會在裡面做出無意義的結果。
            slack.update(Identity.SYSTEM, task.slackChannel(), progressTs,
                    "🧰 任務 #" + task.id() + "　準備隔離工作區…", null);
            boolean reuseWorkspace = task.continuation() != null
                    && roleOrder(task.continuation().role()) >= roleOrder("backend");
            if (reuseWorkspace && !workspaces.exists(task.id())) {
                throw new IllegalStateException("續跑工作區不存在，拒絕重新 clone 取代現場：" + task.id());
            }
            java.nio.file.Path ws = reuseWorkspace ? workspaces.pathFor(task.id()) : workspaces.provision(task);
            audit.record(task.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_STATE,
                    "工作區已備妥：" + ws + "（分支 " + workspaces.branchFor(task.id()) + "）");

            if (plan != null) {
                handoff(task, Identity.QA, Identity.BACKEND,
                        "情境測試計畫已完成，請依需求實作並完成單元測試；交接時提供執行方式、環境需求與已知限制。");
            } else if (arch != null) {
                handoff(task, Identity.ARCHITECTURE, Identity.BACKEND,
                        "實作邊界與驗收條件已經定好了，請照著做。邊界外的需求不要自行擴大，回報給我們。");
            } else {
                handoff(task, Identity.PM, Identity.BACKEND,
                        "這次不需要架構決策，請直接實作。" + modulesNote(pm));
            }

            StageOutcome be = runStage(task, StageSpec.BACKEND,
                    upstreamFor(pm, arch) + (plan == null ? "" : "\n【實作前的 QA 測試計畫】\n" + summarise(plan)), progressTs);
            postStageResult(task, StageSpec.BACKEND, be);

            if (stopAfterBackend(task, be)) return;

            // ---- QA ----
            StageOutcome qa = null;
            if (needsRole(pm, "qa")) {
                String headBeforeQa = task.continuation() != null && "qa".equals(task.continuation().role())
                        ? task.continuation().qaHead() : workspaces.headSha(task.id());
                if (headBeforeQa == null) throw new IllegalStateException("缺少 QA 起始 commit，拒絕略過越權檢查");
                task.qaBaseline(headBeforeQa);
                handoff(task, Identity.BACKEND, Identity.QA,
                        "實作完成" + commitNote(be) + "，請驗收。"
                      + "請依實作前的情境測試計畫執行驗證，記錄實際結果與證據；環境不足請明確向上回報。");
                qa = runStage(task, StageSpec.QA, qaUpstream(pm, arch, be)
                        + "\n【已保存的測試計畫；不可刪除情境或降低預期結果】\n" + summarise(plan), progressTs);
                postStageResult(task, StageSpec.QA, qa);

                String acceptanceBlock = QaGate.executionReason(plan.result(), qa.result());
                if (acceptanceBlock != null) throw new FailureReport.Blocked(acceptanceBlock);
                if (enforceQaWriteScope(task, headBeforeQa)) return;
                if (stopAfterQa(task, qa)) return;
            } else {
                slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                        "ℹ️ PM 判定不需要 QA Agent，略過該階段。");
            }

            // ---- 人工閘門：推分支與開 PR ----
            // 最後一棒交回給人。由實際跑完的角色以自己的身分 tag 提出需求的人，
            // 而不是由系統代發 —— 交接鏈要一路可見到終點。
            // ---- Code Review：無法核實或要求修正時停止 ----
            StageOutcome review = runCodeReview(task, pm, arch, be, qa, progressTs);
            if (task.state().isWaiting()) return;
            if (blockUnverifiedReview(task)) return;

            Identity last = review != null ? Identity.CODE_REVIEW
                          : qa != null ? Identity.QA : Identity.BACKEND;
            slack.post(last, task.slackChannel(), task.slackThreadTs(),
                    "<@" + task.requesterUserId() + "> 我這邊完成了，接下來需要你決定要不要開 PR。");

            awaitPrApproval(task, progressTs, qa, review);

        } catch (FailureReport.Blocked e) {
            if (task.state() == Task.State.ABORTED) return;
            if (task.state() == Task.State.QA_RUNNING) beginRemediation(task, e.getMessage());
            else escalateReview(task, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("{} 管線失敗", task, e);
            if (task.state() == Task.State.ABORTED) return;
            if (pauseForQuota(task, e)) return;
            if (task.state() == Task.State.QA_RUNNING) {
                beginRemediation(task, Diagnostics.describe(e));
                return;
            }
            if (preserveCommittedWork(task, progressTs, e)) return;

            String detail = Diagnostics.describe(e);
            task.failure(detail);
            store.save(task);
            audit.record(task.id(), AuditLog.ACTOR_SYSTEM, AuditLog.ACTION_ERROR, detail);
            slack.update(Identity.SYSTEM, task.slackChannel(), progressTs,
                    "❌ 任務 #" + task.id() + " 失敗", null);
            slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                    slack.mention(Identity.PM) + " <@" + task.requesterUserId() + ">\n"
                  + "❌ 任務 #" + task.id() + " 執行失敗，停止下游與 PR 發布。\n"
                  + detail);
        }
    }

    /**
     * 已經 commit 的工作不要因為回報失敗而丟掉。
     *
     * 實測 #1004：Backend 寫完程式碼、跑過 verify、也 commit 了，
     * 但最後那份結構化輸出連續五次沒通過 schema 驗證
     * （`subtype: error_max_structured_output_retries`），整輪被標成 FAILED。
     * 一次 US$0.86 的完整實作，因為報告格式而被當成沒發生過。
     *
     * 回報是手段，commit 才是產出。工作區裡有新 commit 就代表事情做了，
     * 這時停在 AWAIT_HUMAN 讓人決定要不要用，比標成 FAILED 誠實也實用。
     *
     * @return true 表示已改用保留路徑處理，呼叫端不要再走失敗流程
     */
    private boolean preserveCommittedWork(Task task, String progressTs, Exception cause) {
        if (!task.state().isRunning() || !workspaces.exists(task.id())) return false;

        List<String> commits;
        try {
            commits = workspaces.commitsSinceBase(task.id());
        } catch (Exception e) {
            log.warn("{} 想確認是否有已完成的 commit，但查詢失敗：{}", task, e.getMessage());
            return false;
        }
        if (commits.isEmpty()) return false;

        finish(task, Task.State.AWAIT_HUMAN, AuditLog.ACTION_STATE,
                "階段失敗但工作已 commit（" + commits.size() + " 筆），保留等待人工判斷");

        StringBuilder sb = new StringBuilder();
        sb.append("⏸️ 任務 #").append(task.id()).append(" 的階段執行中斷，")
          .append("已確認存在以下 commit，完成度與正確性尚未核實：\n");
        for (String c : commits) {
            sb.append("　• ").append(c).append("\n");
        }
        sb.append("\n分支 `").append(workspaces.branchFor(task.id())).append("`　")
          .append("累計花費 US$").append(String.format("%.4f", task.totalCost())).append("\n")
          .append("中斷原因：\n").append(Diagnostics.describe(cause)).append("\n")
          .append("_請排除阻礙後重做並重新核實；目前禁止推分支與開 PR。_");

        slack.update(Identity.SYSTEM, task.slackChannel(), progressTs,
                "⏸️ 任務 #" + task.id() + " 中斷，但工作已保留", null);
        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                slack.mention(Identity.PM) + " <@" + task.requesterUserId() + ">\n" + sb);
        return true;
    }

    /** 執行單一階段。並行槽在此取得並確保釋放。 */
    private StageOutcome runStage(Task task, StageSpec stage, String upstream, String progressTs)
            throws Exception {

        Checkpoint checkpoint = task.continuation();
        if (checkpoint != null && stage.role().startsWith("direct_") && !stage.role().equals(checkpoint.role()))
            throw new IllegalStateException("角色對話 checkpoint 不匹配，拒絕跨角色續跑");
        if (checkpoint != null && !stage.role().equals(checkpoint.role())
                && roleOrder(stage.role()) < roleOrder(checkpoint.role())) {
            StageOutcome saved = task.stage(stage.role()).orElseThrow(() ->
                    new IllegalStateException("缺少續跑所需的上游報告：" + stage.role()));
            if (FailureReport.blockingReason(stage.role(), saved.result()) != null)
                throw new IllegalStateException("上游報告未通過驗證，不能沿用：" + stage.role());
            return saved;
        }

        // 會改程式碼的階段一律先確認沙箱真的有效。
        // 自檢是在啟動時做的，這裡只是把結果轉成拒絕執行 —— 寧可任務失敗，
        // 也不要在沒有隔離的情況下讓 agent 動 repo。
        slots.acquire();
        try {
            if (stage.needsWorkspace()) sandbox.requireReadyForCodeChanges();
            Task.State from = task.state();
            task.state(stage.runningState());
            store.save(task);
            audit.stateChange(task, from, task.state(), AuditLog.ACTOR_SYSTEM);

            slack.update(Identity.SYSTEM, task.slackChannel(), progressTs,
                    "⚙️ 任務 #" + task.id() + "　" + stage.displayName() + " 執行中…", null);

            long t0 = System.currentTimeMillis();
            boolean resuming = checkpoint != null && checkpoint.role().equals(stage.role());
            String resume = resuming ? checkpoint.sessionId() : null;
            String feedback = resuming ? "這是額度中斷後的續跑，先檢查 git status、git log 與尚未提交的修改，"
                    + "核實完成到哪裡再接著做，不要清除現場。原始需求：" + task.requestText()
                    + "\n上游脈絡：" + checkpoint.upstream()
                    + "\n前次中斷：" + checkpoint.reason() : null;
            AgentRuntime.RuntimeResult r = stageRunner.run(task, stage, upstream, feedback, resume);
            if (store.find(task.id()).map(t -> t.state() == Task.State.ABORTED).orElse(false)) {
                task.state(Task.State.ABORTED);
                throw new InterruptedException("任務已中止，不採用本次結果或繼續派工");
            }
            long elapsed = (System.currentTimeMillis() - t0) / 1000;

            reportDenials(task, stage, r);

            if (r.isError()) {
                throw new AgentRuntime.RuntimeExecutionException("Runtime 回報 isError=true。"
                        + "\n觀察輸出：" + Diagnostics.clip(r.rawText(), 900)
                        + "\n證據：" + r.diagnosticsLog()
                        + "\n影響：本階段不能視為完成。根因：依上述錯誤查核，尚未獨立確認。"
                        + "\n下一步：維運人員檢查診斷檔並重跑此階段。");
            }

            JsonNode structured = r.structuredOutput();
            if (structured == null || structured.isNull()) {
                throw new IllegalStateException(stage.displayName()
                        + " 未回傳 structured_output。原始輸出：" + abbreviate(r.rawText()));
            }

            StageOutcome outcome = new StageOutcome(
                    stage.role(), r.sessionId(), structured, r.costEstimate(), elapsed);
            task.putStage(outcome);
            store.saveStage(task.id(), outcome);

            String models = r.modelsUsed() == null || r.modelsUsed().isBlank()
                    ? "未知" : r.modelsUsed();
            audit.record(task.id(), stage.role() + "-agent", AuditLog.ACTION_RUNTIME,
                    stage.displayName() + " 回傳報告，耗時 " + elapsed + " 秒，模型 " + models,
                    r.costEstimate(), r.sessionId());
            String blocked = FailureReport.blockingReason(stage.role(), structured);
            if (blocked == null && "qa".equals(stage.role())) {
                blocked = QaGate.executionReason(task.stage("qa_plan").map(StageOutcome::result).orElse(null), structured);
            }
            if (blocked != null) {
                throw new FailureReport.Blocked(stage.displayName() + " 未完成：\n" + blocked
                        + "\n報告位置：stage_result(task_id=" + task.id() + ", role=" + stage.role()
                        + ")；session=" + r.sessionId() + "；診斷檔=" + r.diagnosticsLog());
            }
            return outcome;

        } catch (FailureReport.Blocked e) {
            throw e;
        } catch (Exception e) {
            throw new AgentRuntime.RuntimeExecutionException(stage.displayName() + " 執行失敗。"
                    + "\n證據目錄：" + props.logsPath().resolve(task.id())
                    + "\n影響：此階段尚未核實，下游與 PR 發布停止。"
                    + "\n根因狀態：UNKNOWN；底層錯誤是觀察證據，未自行推論成根因。"
                    + "\n下一步／處理者：維運人員查核底層例外與此階段 log 後重跑。", e);
        } finally {
            slots.release();   // 不論成敗都要放掉，否則整個團隊會卡死
        }
    }

    /**
     * 把被權限擋下的工具呼叫記進稽核並警告。
     *
     * 不看這個的話，一個因為工具沒開而做不完的任務，看起來會像是 agent 自己能力不足。
     * 兩者的處理方式完全不同：前者要改 StageSpec 的 allowedTools，後者要改提示詞。
     */
    private void reportDenials(Task task, StageSpec stage, AgentRuntime.RuntimeResult r) {
        JsonNode denials = r.permissionDenials();
        if (denials == null || !denials.isArray() || denials.isEmpty()) return;

        String detail = Diagnostics.toolFailures(denials)
                + "\n證據：session=" + r.sessionId() + "；" + r.diagnosticsLog()
                + "\n影響：以上操作未成功，是否已有替代操作須查核角色報告。"
                + "\n下一步／處理者：該角色提供替代驗證證據；仍受阻時必須 BLOCKED，交維運查核。";

        log.warn("{} 的 {} 有 {} 次工具失敗或拒絕：{}",
                task, stage.displayName(), denials.size(), detail);
        audit.record(task.id(), stage.role() + "-agent", AuditLog.ACTION_DENIED,
                stage.displayName() + " 工具失敗／拒絕的 runtime 紀錄：" + detail);
        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                stage.displayName() + " 工具失敗／拒絕的 runtime 紀錄：" + detail);
    }

    // ------------------------------------------------------------------ 停止判斷

    /**
     * PM 之後的兩個自動停止點。這不是審批閘門 —— 不需要使用者點任何東西，
     * 只是避免把額度花在沒有意義或連 PM 自己都沒把握的任務上。
     */
    private boolean stopAfterPm(Task task, StageOutcome pm) {
        JsonNode r = pm.result();
        String type = r.path("task_type").asText("");
        String confidence = r.path("confidence").asText("");
        JsonNode agents = r.get("required_agents");
        boolean noDownstream = agents == null || !agents.isArray() || agents.isEmpty();

        if ("QUESTION".equals(type) || noDownstream) {
            finish(task, Task.State.COMPLETED, AuditLog.ACTION_STATE,
                    "PM 判定為詢問或不需下游角色，管線結束");
            slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                    "✅ 任務 #" + task.id() + " 已完成 —— PM 判定這是詢問類需求，不需要啟動實作流程。\n"
                  + "花費：US$" + String.format("%.4f", task.totalCost()));
            return true;
        }

        if ("LOW".equals(confidence)) {
            finish(task, Task.State.AWAIT_HUMAN, AuditLog.ACTION_STATE,
                    "PM 自陳把握度低，停止等待人工確認");
            slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                    "⏸️ 任務 #" + task.id() + " 已暫停。\n"
                  + "*PM 自己表示對這次分析的把握程度是 LOW*，繼續往下跑可能會浪費額度。\n"
                  + "請補充需求細節後重新提出，或直接人工處理。");
            return true;
        }
        return false;
    }

    /**
     * Backend 之後的停止判斷。
     *
     * 三種都停：明確失敗、只做了一半、以及踩到實作邊界。
     * 最後一種是刻意設計的 —— Backend 的提示詞要求它遇到邊界外的需求時停下來回報，
     * 而不是自行擴大範圍（見 docs-site/docs/backend-qa-decisions.md 決策 1）。
     * 如果這裡不真的停，那條規則就只是一句空話。
     */
    private boolean stopAfterBackend(Task task, StageOutcome be) {
        JsonNode r = be.result();
        String status = r.path("status").asText("");
        boolean hasConflict = r.hasNonNull("boundary_conflict");

        if ("FAILED".equals(status)) {
            finish(task, Task.State.FAILED, AuditLog.ACTION_ERROR, "Backend 回報 FAILED");
            slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                    "❌ 任務 #" + task.id() + " 的 Backend 無法完成，已停止。\n"
                  + "詳細阻礙見上一則訊息。累計花費：US$"
                  + String.format("%.4f", task.totalCost()));
            return true;
        }

        if (hasConflict) {
            finish(task, Task.State.AWAIT_HUMAN, AuditLog.ACTION_STATE,
                    "Backend 回報超出實作邊界，停止等待人工判斷");
            slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                    "⏸️ 任務 #" + task.id() + " 已暫停 —— Backend 需要動到實作邊界之外的東西。\n"
                  + "它依規則停下來回報而沒有自行擴大範圍。請確認要不要放寬邊界，"
                  + "或補充需求後重新提出。\n"
                  + "累計花費：US$" + String.format("%.4f", task.totalCost()));
            return true;
        }

        if ("PARTIAL".equals(status)) {
            finish(task, Task.State.AWAIT_HUMAN, AuditLog.ACTION_STATE,
                    "Backend 只完成一部分，停止等待人工判斷");
            slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                    "⏸️ 任務 #" + task.id() + " 已暫停 —— Backend 只完成了一部分。\n"
                  + "在確認剩下的部分要怎麼處理之前，不往下跑 QA。\n"
                  + "累計花費：US$" + String.format("%.4f", task.totalCost()));
            return true;
        }
        return false;
    }

    /**
     * 給 Backend 的上游脈絡。
     *
     * PM 與 Architecture 兩份都要給：PM 有原始需求的分類與計畫，
     * Architecture 有實作邊界與驗收條件。只給其中一份，Backend 會缺一半的約束。
     */
    private static String upstreamFor(StageOutcome pm, StageOutcome arch) {
        StringBuilder sb = new StringBuilder();
        sb.append("【PM 的分析】\n").append(summarise(pm)).append("\n");
        if (arch != null) {
            sb.append("\n【Architecture 的決策】\n").append(summarise(arch)).append("\n");
            sb.append("\n其中 implementation_boundary 是你的實作邊界，qa_criteria 是之後的驗收條件。\n");
        } else {
            sb.append("\n（PM 判定這次不需要 Architecture，因此沒有正式的實作邊界。")
              .append("請把變更範圍控制在 PM 指出的受影響模組內。）\n");
        }
        return sb.toString();
    }

    /**
     * 給 QA 的上游脈絡。
     *
     * 三份都要：qa_criteria 是驗收清單，Backend 的報告是待驗的對象，
     * PM 的原始分析則讓 QA 能判斷驗收條件本身有沒有漏掉什麼。
     */
    private static String qaUpstream(StageOutcome pm, StageOutcome arch, StageOutcome be) {
        StringBuilder sb = new StringBuilder();
        sb.append("【PM 的分析】\n").append(summarise(pm)).append("\n");
        if (arch != null) {
            sb.append("\n【Architecture 的決策】\n").append(summarise(arch)).append("\n");
            sb.append("\n其中 qa_criteria 就是你要逐條對照的驗收條件。\n");
        } else {
            sb.append("\n（這次沒有 Architecture 階段，因此沒有現成的 qa_criteria。")
              .append("請依 PM 的分析自行判定關鍵驗收點，一樣逐條處理。）\n");
        }
        sb.append("\n【Backend 的實作報告】\n").append(summarise(be)).append("\n");
        sb.append("\nBackend 報告只提供操作線索，不是驗收證據。依既定情境實際執行測試，")
          .append("記錄輸入、預期與實際結果、指令及 log。讀碼僅輔助定位，不得從實作反推預期行為。\n");
        return sb.toString();
    }

    /**
     * 核對 QA 實際 commit 了哪些路徑。
     *
     * 沙箱把 src/main 釘成唯讀，但 .git 必須可寫（QA 要 commit 自己補的測試），
     * 而實測發現 `git checkout <舊commit> -- src/main` 雖然寫不了工作樹，
     * **index 仍然會被改掉** —— 接著 commit 就能把產品程式碼的舊版本送進 PR。
     *
     * 檔案系統擋不住索引，所以在沙箱外面再核對一次實際進了哪些路徑。
     * 這裡判定為 FAILED 而不是等人決定：QA 動到產品程式碼不是判斷問題，是越權。
     *
     * @return true 表示發現越權，管線應中止
     */
    private boolean enforceQaWriteScope(Task task, String headBeforeQa) throws Exception {
        List<String> changed = workspaces.changedPathsBetween(
                task.id(), headBeforeQa, workspaces.headSha(task.id()));

        List<String> illegal = changed.stream().filter(p -> !isTestPath(p)).toList();
        if (illegal.isEmpty()) {
            if (!changed.isEmpty()) {
                log.info("{} QA 新增了 {} 個測試檔，全部在允許範圍內", task, changed.size());
            }
            return false;
        }

        String list = String.join("\n", illegal.stream().map(p -> "　• " + p).toList());
        finish(task, Task.State.FAILED, AuditLog.ACTION_ERROR,
                "QA 的 commit 動到測試以外的檔案，已中止");
        audit.record(task.id(), "qa-agent", AuditLog.ACTION_REJECTED,
                "QA 越權變更：" + String.join(", ", illegal));

        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                "🚨 任務 #" + task.id() + " 已中止 —— QA 動到了測試以外的檔案\n"
              + list + "\n\n"
              + "QA 只被允許新增測試。沙箱擋住了對 src/main 的直接寫入，"
              + "但仍可能透過 git 索引繞道，因此這裡會核對實際 commit 的路徑。\n"
              + "工作區保留在 `" + workspaces.branchFor(task.id()) + "` 供調查。");
        return true;
    }

    /** 只有測試路徑是 QA 可以動的。判準對齊 Maven 的目錄慣例。 */
    private static boolean isTestPath(String path) {
        return path.startsWith("src/test/") || path.contains("/src/test/");
    }

    /**
     * QA 之後的停止判斷。
     *
     * FAIL 就不該進 PR —— 讓它停在 AWAIT_HUMAN 而不是 FAILED，
     * 因為工作區裡的東西通常還有救，人看過之後可以要求重做。
     */
    private boolean stopAfterQa(Task task, StageOutcome qa) {
        JsonNode r = qa.result();
        if ("FAIL".equals(r.path("verdict").asText(""))) {
            finish(task, Task.State.AWAIT_HUMAN, AuditLog.ACTION_STATE,
                    "QA 判定 FAIL，不進入 PR 閘門");
            slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                    "❌ 任務 #" + task.id() + " 未通過驗收，不會進入開 PR 的審核。\n"
                  + "問題見上一則訊息。工作留在分支 `" + workspaces.branchFor(task.id())
                  + "`，要修的話請要求重做。\n"
                  + "累計花費：US$" + String.format("%.4f", task.totalCost()));
            return true;
        }
        return false;
    }

    /**
     * 全自動管線唯一保留的人工閘門。
     *
     * 到這裡為止的每一步都沒有問過人；推分支與開 PR 會把結果送出去，
     * 那一步必須有人按下去（見 docs-site/docs/interaction-model.md）。
     */
    /**
     * Code Review 階段。QA 之後、PR 閘門之前，審的是即將開 PR 的最終狀態。
     *
     * 無法核實、逾時或回報格式錯誤時保留工作並向上回報，不得進入 PR 閘門。
     *
     * 回傳 null 表示沒跑或跑失敗，呼叫端據此決定由誰把最後一棒交回給人。
     */
    private StageOutcome runCodeReview(Task task, StageOutcome pm, StageOutcome arch,
                                       StageOutcome be, StageOutcome qa, String progressTs) {
        if (!props.codeReviewEnabled()) return null;

        Identity from = qa != null ? Identity.QA : Identity.BACKEND;
        try {
            handoff(task, from, Identity.CODE_REVIEW,
                    "工作區裡就是即將開 PR 的最終狀態，麻煩你看過一遍。");
            StageOutcome review = runStage(task, StageSpec.CODE_REVIEW,
                    reviewUpstream(pm, arch, be, qa)
                            + task.stage("qa_plan").map(p -> "\n【實作前測試計畫】\n" + summarise(p)).orElse(""), progressTs);
            postStageResult(task, StageSpec.CODE_REVIEW, review);
            return review;
        } catch (FailureReport.Blocked e) {
            escalateReview(task, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("{} Code Review 階段失敗，停止發布", task, e);
            if (pauseForQuota(task, e)) return null;
            escalateReview(task, "Code Review 執行失敗：" + Diagnostics.describe(e));
            return null;
        }
    }

    /** 舊的 AWAIT_HUMAN 任務可明確要求 PM 接手，補充內容不另建任務。 */
    public void planDecision(Task task, String user, com.zipe.aiteam.slack.PlanReview.Decision decision,
                             boolean approve, String channel, String thread) {
        if (flowEngine != null && flowEngine.owns(task.id())) flowEngine.planDecision(task, user, decision, approve, channel, thread, job -> work.offer(job));
    }
    public void resumeApprovedPlanTask(String id) {
        Task task = store.find(id).orElseThrow(() -> new IllegalArgumentException("指定任務不存在"));
        if (flowEngine == null || !flowEngine.owns(id)) throw new IllegalArgumentException("指定任務不是配置流程");
        flowEngine.resumeApprovedPlan(task, job -> work.offer(job));
    }
    public void requestTriage(Task task, String feedback, String byUser) {
        if (flowEngine != null && flowEngine.owns(task.id())) { flowEngine.resume(task, feedback, byUser, job -> work.offer(job)); return; }
        if (legacyBlockedByNewConfiguration(task)) return;
        if (task.stage("qa").isEmpty() || task.stage("qa_plan").isEmpty()) {
            slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "此任務缺少 QA／測試計畫，不能沿用本處理流程；請人工查核。");
            return;
        }
        if (!store.claimTriage(task.id())) {
            slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "只有等待人工的任務可送回 PM；目前未新增派工。");
            return;
        }
        Remediation previous = store.remediation(task.id()).orElse(null);
        String context = (previous == null ? "QA 回報受阻，請逐條分析 uncertainties／findings／failure_report。" : previous.context())
                + "\n人工補充（" + byUser + "）：" + feedback;
        Remediation repair = new Remediation(task.id(), "pm_triage", previous == null ? 0 : previous.repairs(), context);
        store.saveRemediation(repair);
        audit.record(task.id(), byUser, AuditLog.ACTION_BUTTON, "要求 PM 實際處理，已排入佇列");
        work.offer(() -> {
            Task current = store.find(task.id()).orElseThrow();
            if (current.state() != Task.State.TRIAGE_QUEUED) return;
            runRemediation(current, repair);
        });
        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), "PM 問題處理已排入佇列，尚未開始；不會重新 clone 或發布 PR。");
    }

    private void beginRemediation(Task task, String reason) {
        task.continuation(null);
        Remediation previous = store.remediation(task.id()).orElse(null);
        Remediation repair = new Remediation(task.id(), "pm_triage", previous == null ? 0 : previous.repairs(), reason);
        store.saveRemediation(repair);
        runRemediation(task, repair);
    }

    private String remediationContext(Task task, Remediation repair) {
        StringBuilder context = new StringBuilder("目前受阻原因／PM 派工：\n" + repair.context());
        for (String role : List.of("pm", "architecture", "qa_plan", "backend", "qa", "pm_triage")) {
            task.stage(role).ifPresent(o -> context.append("\n【").append(role).append(" 的報告（非獨立事實）】\n").append(summarise(o)));
        }
        return context.toString();
    }

    /** 有限次修正，不回頭跑原始 PM／Architecture／QA_PLAN，不重建工作區。 */
    private void runRemediation(Task task, Remediation initial) {
        Remediation repair = initial;
        try {
            if (!workspaces.exists(task.id())) throw new IllegalStateException("原工作區不存在，拒絕重建取代現場");
            if (task.stage("pm").isEmpty() || task.stage("qa_plan").isEmpty() || task.stage("backend").isEmpty())
                throw new IllegalStateException("缺少原始需求／計畫／實作報告，不能自動修正");
            // 包括舊任務手動接手；必須先核對上一輪 QA 有無越權。
            Checkpoint latest = store.checkpoint(task.id()).orElse(null);
            if ("pm_triage".equals(repair.phase()) && (task.state() == Task.State.QA_RUNNING
                    || (latest != null && "qa".equals(latest.role())))) {
                String baseline = task.qaBaseline();
                if (baseline == null) baseline = store.checkpoint(task.id()).map(Checkpoint::qaHead).orElse(null);
                if (baseline == null) throw new IllegalStateException("缺少 QA 原始稽核基準，請人工查核，禁止自動派工");
                task.qaBaseline(baseline);
                if (enforceQaWriteScope(task, baseline)) return;
            }
            while (repair.active()) {
                if (store.find(task.id()).map(t -> t.state() == Task.State.ABORTED).orElse(false)) { task.state(Task.State.ABORTED); return; }
                // 續跑只續當前階段；其他階段必須新執行，不可被原管線 roleOrder 略過。
                if (task.continuation() != null && !repair.phase().equals(task.continuation().role())) task.continuation(null);
                StageSpec spec = switch (repair.phase()) {
                    case "pm_triage" -> StageSpec.PM_TRIAGE;
                    case "backend" -> StageSpec.BACKEND;
                    case "qa" -> StageSpec.QA;
                    case "code_review" -> StageSpec.CODE_REVIEW;
                    default -> throw new IllegalStateException("未知 PM 處理階段");
                };
                if (spec == StageSpec.QA) {
                    String head = task.continuation() == null ? workspaces.headSha(task.id()) : task.continuation().qaHead();
                    if (head == null) throw new IllegalStateException("QA 續跑缺少原始稽核基準");
                    task.qaBaseline(head);
                }
                slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                        spec == StageSpec.PM_TRIAGE ? "PM 處理中：正在分析 QA 阻礙，尚未解決，禁止發布。"
                                : "PM 已派工，" + spec.displayName() + " 處理中；自動修正第 " + repair.repairs() + "/2 輪。");
                StageOutcome outcome;
                try {
                    outcome = runStage(task, spec, remediationContext(task, repair), task.statusMessageTs());
                    postStageResult(task, spec, outcome);
                } catch (Exception e) {
                    if (task.state() == Task.State.ABORTED) return;
                    if (pauseForQuota(task, e)) return;
                    if (spec == StageSpec.PM_TRIAGE || spec == StageSpec.CODE_REVIEW) throw e;
                    if (spec == StageSpec.QA && enforceQaWriteScope(task, task.qaBaseline())) return;
                    if (repair.repairs() >= Remediation.MAX_REPAIRS) throw new FailureReport.Blocked("自動修正已達兩輪，仍受阻：" + Diagnostics.describe(e));
                    task.continuation(null);
                    repair = repair.next("pm_triage", repair.repairs(), Diagnostics.describe(e));
                    store.saveRemediation(repair);
                    continue;
                }
                task.continuation(null);
                if (spec == StageSpec.PM_TRIAGE) {
                    JsonNode result = outcome.result();
                    String invalid = TriageGate.blockingReason(result);
                    if (invalid != null) throw new FailureReport.Blocked(invalid);
                    if ("HUMAN".equals(result.path("action").asText())) {
                        repair = repair.next("HUMAN", repair.repairs(), repair.context());
                        store.saveRemediation(repair);
                        finish(task, Task.State.AWAIT_HUMAN, AuditLog.ACTION_STATE, "PM 已完成分析，等待需求提出者回覆");
                        StringBuilder questions = new StringBuilder();
                        result.path("questions").forEach(q -> questions.append("\n• ").append(q.asText()));
                        slack.update(Identity.SYSTEM, task.slackChannel(), task.statusMessageTs(), "PM 已完成分析；等待你回覆，禁止發布", null);
                        slack.post(Identity.PM, task.slackChannel(), task.slackThreadTs(), "<@" + task.requesterUserId() + "> PM 已完成分析，現在沒有 agent 在執行。"
                                + questions + "\n處理建議：" + result.path("instructions").asText()
                                + "\n請在此討論串 @主機器人，輸入「補充 #" + task.id() + " 你的答案」；不是只 tag PM。這不會自動核准 PR。");
                        return;
                    }
                    if (repair.repairs() >= Remediation.MAX_REPAIRS) throw new FailureReport.Blocked("自動修正已達兩輪，PM 不得再自動派工；請人工處理。");
                    repair = repair.next("BACKEND".equals(result.path("action").asText()) ? "backend" : "qa", repair.repairs() + 1,
                            repair.context() + "\nPM 指示：" + summarise(outcome));
                } else if (spec == StageSpec.BACKEND) {
                    repair = repair.next("qa", repair.repairs(), repair.context());
                } else if (spec == StageSpec.QA) {
                    if (enforceQaWriteScope(task, task.qaBaseline())) return;
                    repair = repair.next(props.codeReviewEnabled() ? "code_review" : "DONE", repair.repairs(), repair.context());
                } else {
                    repair = repair.next("DONE", repair.repairs(), repair.context());
                }
                store.saveRemediation(repair);
            }
            if ("DONE".equals(repair.phase()) && !blockUnverifiedReview(task)) {
                awaitPrApproval(task, task.statusMessageTs(), task.stage("qa").orElseThrow(), task.stage("code_review").orElse(null));
            }
        } catch (Exception e) {
            if (task.state() == Task.State.ABORTED) return;
            if (pauseForQuota(task, e)) return;
            store.saveRemediation(repair.next("HUMAN", repair.repairs(), Diagnostics.describe(e)));
            escalateReview(task, "PM 問題處理流程停止，沒有 agent 在背景處理：\n" + Diagnostics.describe(e));
        }
    }

    private boolean blockUnverifiedReview(Task task) {
        Remediation remediation = store.remediation(task.id()).orElse(null);
        if (remediation != null && !"DONE".equals(remediation.phase())) {
            escalateReview(task, "PM 處理流程尚未完成，目前階段：" + remediation.phase());
            return true;
        }
        if (task.stage("pm").map(p -> needsRole(p, "qa") && needsRole(p, "backend")).orElse(false)) {
            String reason = QaGate.executionReason(
                    task.stage("qa_plan").map(StageOutcome::result).orElse(null),
                    task.stage("qa").map(StageOutcome::result).orElse(null));
            if (reason != null) { escalateReview(task, reason); return true; }
        }
        for (StageOutcome stage : task.stages().values()) {
            String blocked = FailureReport.blockingReason(stage.role(), stage.result());
            if (blocked != null) {
                escalateReview(task, stage.role() + "：" + blocked);
                return true;
            }
        }
        StageOutcome review = task.stage("code_review").orElse(null);
        if (!props.codeReviewEnabled() && review == null) return false;
        String reason = ReviewGate.blockingReason(review == null ? null : review.result());
        if (reason == null) return false;
        escalateReview(task, reason);
        return true;
    }

    private void escalateReview(Task task, String reason) {
        finish(task, Task.State.AWAIT_HUMAN, AuditLog.ACTION_REJECTED, reason);
        if (task.statusMessageTs() != null) {
            slack.update(Identity.SYSTEM, task.slackChannel(), task.statusMessageTs(),
                    "⛔ 任務 #" + task.id() + " 審查受阻，禁止推分支與開 PR", null);
        }
        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                slack.mention(Identity.PM) + " <@" + task.requesterUserId() + ">\n"
              + "⛔ 任務 #" + task.id() + " 已向上回報並停止發布。\n"
              + Diagnostics.safe(reason) + "\n"
              + "已有工作區保留。請排除上述原因、補齊證據並重新完成受阻階段與 Code Review；"
              + "在核實通過前不能推分支或建立 PR，人工核准也不能略過此檢查。");
    }

    /**
     * 給 Code Reviewer 的上游脈絡。
     *
     * 一樣強調各角色的自陳不是事實 —— 這個角色的價值就在於獨立確認，
     * 若它只是覆述上游的說法，那等於沒有第二意見。
     */
    private static String reviewUpstream(StageOutcome pm, StageOutcome arch,
                                         StageOutcome be, StageOutcome qa) {
        StringBuilder sb = new StringBuilder();
        sb.append("【PM 的分析】\n").append(summarise(pm)).append("\n");
        if (arch != null) {
            sb.append("\n【Architecture 的決策】\n").append(summarise(arch)).append("\n");
        }
        sb.append("\n【Backend 的實作報告】\n").append(summarise(be)).append("\n");
        if (qa != null) {
            sb.append("\n【QA 的驗收結果】\n").append(summarise(qa)).append("\n");
        } else {
            sb.append("\n（這次沒有跑 QA 階段。）\n");
        }
        sb.append("\n以上都是各角色的自我陳述，不是事實。工作區裡有這次任務的所有 commit，")
          .append("請自己到程式碼與 git 紀錄中確認。\n");
        return sb.toString();
    }

    private void awaitPrApproval(Task task, String progressTs, StageOutcome qa, StageOutcome review) {
        finish(task, Task.State.AWAIT_PR_APPROVAL, AuditLog.ACTION_STATE,
                "管線完成，等待人工核准推分支與開 PR");

        slack.update(Identity.SYSTEM, task.slackChannel(), progressTs,
                "🔔 任務 #" + task.id() + " 等待你的核准", null);

        StringBuilder sb = new StringBuilder();
        sb.append("*任務 #").append(task.id()).append(" 已完成，等待核准*\n\n");
        sb.append("分支：`").append(workspaces.branchFor(task.id())).append("`\n");
        if (qa != null) {
            sb.append("驗收：").append(qa.result().path("verdict").asText("－")).append("\n");
        } else {
            sb.append("驗收：_未執行 QA_\n");
        }
        if (review != null) {
            sb.append("Code Review：")
              .append(review.result().path("verdict").asText("－"))
              .append("（").append(review.result().path("findings").size())
              .append(" 項 findings）\n");
        }
        sb.append("累計花費：US$").append(String.format("%.4f", task.totalCost())).append("\n\n");
        sb.append("按下核准會把分支推上 GitHub 並開 PR。這是唯一需要你決定的一步。");

        slack.post(Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                "任務 #" + task.id() + " 等待核准",
                SlackBlocks.approvalBlocks(task.id(), sb.toString()));
    }


    private void finish(Task task, Task.State to, String action, String detail) {
        Task.State from = task.state();
        task.state(to);
        store.save(task);
        audit.record(task.id(), AuditLog.ACTOR_SYSTEM, action, from + " → " + to + "：" + detail);
    }

    private static boolean needsRole(StageOutcome pm, String role) {
        JsonNode agents = pm.result().get("required_agents");
        if (agents == null || !agents.isArray()) return false;
        for (JsonNode a : agents) {
            if (role.equals(a.asText())) return true;
        }
        return false;
    }

    /** 把上游產出轉成給下游看的文字。 */
    private static String summarise(StageOutcome outcome) {
        return outcome.result().toPrettyString();
    }

    /**
     * 交接訊息：由剛完成的角色以自己的身分 tag 下一棒。
     *
     * mention 是真的 —— 對方會收到通知、名字會 highlight。
     * 但它只是「看得見的交接」，不是觸發機制：實際的執行順序仍由這個類別驅動。
     * 這樣做的理由是，讓管線在 Slack 上讀起來像四個同事在交接工作，
     * 同時不把去重、佇列、狀態機、稽核這些東西攤成分散式問題。
     *
     * 角色沒有獨立 Slack 帳號時，mention 會退成粗體名稱（tag 不到，但讀得懂）。
     */
    private void handoff(Task task, Identity from, Identity to, String note) {
        slack.post(from, task.slackChannel(), task.slackThreadTs(),
                slack.mention(to) + " " + note);
    }

    /** 交接訊息裡帶上受影響模組，讓下一棒不必回頭翻上一則。 */
    private static String modulesNote(StageOutcome pm) {
        JsonNode mods = pm.result().get("affected_modules");
        if (mods == null || !mods.isArray() || mods.isEmpty()) return "";
        List<String> names = new java.util.ArrayList<>();
        mods.forEach(m -> names.add("`" + m.asText() + "`"));
        return "受影響模組：" + String.join("、", names) + "。";
    }

    private static String commitNote(StageOutcome be) {
        JsonNode commits = be.result().get("commits");
        if (commits == null || !commits.isArray() || commits.isEmpty()) return "";
        return "（commit `" + commits.get(0).path("sha").asText() + "`）";
    }

    private void postStageResult(Task task, StageSpec stage, StageOutcome outcome) {
        String text = SlackBlocks.renderStage(task, stage, outcome);
        slack.post(stage.identity(), task.slackChannel(), task.slackThreadTs(), text);
    }

    private static String abbreviate(String s) {
        if (s == null) return "(無訊息)";
        return s.length() <= 800 ? s : s.substring(0, 800) + "…";
    }

    private static int roleOrder(String role) {
        int index = List.of("pm", "architecture", "qa_plan", "backend", "qa", "pm_triage", "code_review").indexOf(role);
        if (index < 0) throw new IllegalArgumentException("未知的續跑階段：" + role);
        return index;
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        worker.interrupt();
    }
}
