package com.zipe.aiteam.slack;

import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.model.event.AppMentionEvent;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.store.AuditLog;
import com.zipe.aiteam.store.TaskStore;
import com.zipe.aiteam.task.Task;
import com.zipe.aiteam.task.TaskOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Slack 事件入口。
 *
 * 最重要的規則：handler 內只做去重、授權、入佇列，然後立刻 ack。
 * Slack 要求 3 秒內回應，逾時會重送 3 次 —— 若在 handler 裡跑 Claude Code，
 * 同一個需求會被執行四次。
 */
@Component
public class SlackListener {

    private static final Logger log = LoggerFactory.getLogger(SlackListener.class);
    private static final Pattern MENTION = Pattern.compile("<@[A-Z0-9]+>");
    static final Pattern BASELINE = Pattern.compile("^基準驗證\\s*$");
    /** 頻道頂層的「#編號 指令」。#1054：「#1053 檢視配置更新」打在頂層、少了「補充」，被開成新任務。 */
    static final Pattern BARE_COMMAND = Pattern.compile("^#(\\d+)\\s+([\\s\\S]+)$");
    static final java.util.List<String> ROUTABLE_COMMANDS = java.util.List.of(
            "檢視配置更新", "核准配置更新", "狀態", "檢視完整報告", "檢視測試計畫", "核准需求變更", "核准測試計畫");
    static boolean routable(String command) {
        String value = command.trim();
        return ROUTABLE_COMMANDS.stream().anyMatch(c -> value.equals(c) || value.startsWith(c + " "));
    }
    static String unwrapCommand(String text) {
        String value = text.trim();
        String candidate = value;
        if (value.startsWith("```") && value.endsWith("```") && value.length() >= 6) {
            candidate = value.substring(3, value.length() - 3).trim();
            if (candidate.startsWith("text\n")) candidate = candidate.substring(5).trim();
        } else if (value.startsWith("`") && value.endsWith("`") && value.length() >= 2) {
            candidate = value.substring(1, value.length() - 1).trim();
        }
        // 只解開整則已知管理指令；不從一般問題或程式範例中擷取指令。
        return candidate.matches("(?s)^(補充|處理|中斷|開\\s*PR)\\s+#\\d+(?:\\s+.*)?$") || BASELINE.matcher(candidate).matches() ? candidate : value;
    }

    private final AiTeamProperties props;
    private final TaskOrchestrator orchestrator;
    private final SlackNotifier notifier;
    private final TaskStore store;
    private final AuditLog audit;

    private SocketModeApp socketApp;
    private final java.util.List<SocketModeApp> roleSockets = new java.util.ArrayList<>();
    private final java.util.concurrent.ExecutorService ingress = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "slack-ingress"); thread.setDaemon(true); return thread;
    });

    private void enqueueMention(String eventId, AppMentionEvent event, SlackNotifier.Identity role) {
        ingress.execute(() -> {
            try { handleMention(eventId, event, role); }
            catch (Exception e) { log.error("處理角色 {} 的事件失敗：{}", role, com.zipe.aiteam.runtime.Diagnostics.describe(e)); }
        });
    }

    public SlackListener(AiTeamProperties props, TaskOrchestrator orchestrator,
                         SlackNotifier notifier, TaskStore store, AuditLog audit) {
        this.props = props;
        this.orchestrator = orchestrator;
        this.notifier = notifier;
        this.store = store;
        this.audit = audit;
    }

    public void start() throws Exception {
        App app = new App(AppConfig.builder()
                .singleTeamBotToken(props.slackBotToken())
                .build());

        app.event(AppMentionEvent.class, (payload, ctx) -> {
            try {
                enqueueMention(payload.getEventId(), payload.getEvent(), SlackNotifier.Identity.SYSTEM);
            } catch (Exception e) {
                log.error("處理 app_mention 時發生例外", e);
            }
            return ctx.ack();   // 一定要在 3 秒內走到這裡
        });

        for (String action : new String[]{
                SlackBlocks.ACTION_APPROVE, SlackBlocks.ACTION_REDO, SlackBlocks.ACTION_ABORT,
                SlackBlocks.ACTION_CONTINUE, SlackBlocks.ACTION_INTERRUPT}) {
            app.blockAction(action, (req, ctx) -> {
                try {
                    handleButton(req.getPayload().getUser().getId(),
                                 req.getPayload().getActions().get(0).getValue(),
                                 action);
                } catch (Exception e) {
                    log.error("處理按鈕 {} 時發生例外", action, e);
                }
                return ctx.ack();
            });
        }

        registerPlanActions(app);
        registerHumanGateActions(app);
        registerHumanInputActions(app);
        registerSpinoffActions(app);
        socketApp = new SocketModeApp(props.slackAppToken(), app);
        log.info("Socket Mode 連線中…（白名單 {} 人，並行上限 {}）",
                props.allowedUsers().size(), props.maxConcurrency());
        try {
            for (SlackNotifier.Identity role : props.identities()) {
                if (role == SlackNotifier.Identity.SYSTEM) continue;
                String token = props.roleAppToken(role.name());
                if (token.isEmpty()) {
                    log.info("{} 直接對話未啟用：尚未設定角色 App-Level Token", role);
                    continue;
                }
                if (props.roleBotToken(role.name()).isEmpty()) throw new IllegalStateException(role + " 有 App Token 但缺少專屬 Bot Token");
                if (!notifier.isMentionable(role)) throw new IllegalStateException(role + " 無法核實 bot user ID，拒絕啟用直接對話");
                App roleApp = new App(AppConfig.builder().singleTeamBotToken(props.roleBotToken(role.name())).build());
                roleApp.event(AppMentionEvent.class, (payload, ctx) -> {
                    enqueueMention(payload.getEventId(), payload.getEvent(), role);
                    return ctx.ack();
                });
                registerPlanActions(roleApp);
                SocketModeApp socket = new SocketModeApp(token, roleApp);
                roleSockets.add(socket);
                socket.startAsync();
                log.info("{} 直接對話 Socket Mode 已啟動（仍需實測事件送達）", role);
            }
            socketApp.start();   // 主連線阻塞
        } catch (Exception e) {
            stop();
            throw e;
        }
    }

    // ------------------------------------------------------------------

    private void handleMention(String eventId, AppMentionEvent e) {
        handleMention(eventId, e, SlackNotifier.Identity.SYSTEM);
    }

    void handleMention(String eventId, AppMentionEvent e, SlackNotifier.Identity role) {

        // 即使有人誤將 bot 加進白名單，agent 交接也不能反向触發任務。
        if (e.getUser() == null || e.getBotId() != null || e.getAppId() != null
                || e.getBotProfile() != null || Boolean.TRUE.equals(e.getDisplayAsBot())
                || "bot_message".equals(e.getSubtype()) || notifier.isAgentUser(e.getUser())) return;
        if (eventId == null || eventId.isBlank()) return; // 缺去重鍵時拒絕執行
        if (e.getEdited() != null) return;
        if (role != SlackNotifier.Identity.SYSTEM) {
            String target = notifier.mention(role);
            if (target == null || !target.startsWith("<@") || e.getText() == null || !e.getText().contains(target)) return;
        }

        // 去重表已持久化，服務重啟後 Slack 的重送仍會被正確擋下
        if (!store.markEventSeen(role == SlackNotifier.Identity.SYSTEM ? eventId : role.name() + ":" + eventId)) {
            log.info("忽略重複事件 {}", eventId);
            return;
        }

        String threadTs = e.getThreadTs() != null ? e.getThreadTs() : e.getTs();

        if (!props.allowedUsers().contains(e.getUser())) {
            log.warn("拒絕未授權使用者 {}", e.getUser());
            audit.record(null, e.getUser(), AuditLog.ACTION_REJECTED, "不在觸發者白名單中");
            notifier.post(SlackNotifier.Identity.SYSTEM, e.getChannel(), threadTs,
                    "🚫 <@" + e.getUser() + "> 目前不在允許清單中，無法觸發任務。\n"
                  + "這個 bot 具備修改程式碼的能力，因此採白名單管控。請聯絡管理者。");
            return;
        }

        String allowedChannel = props.channelId();
        if (allowedChannel != null && !allowedChannel.isBlank()
                && !allowedChannel.equals(e.getChannel())) {
            log.warn("忽略非指定頻道 {} 的訊息（設定值為 {}）", e.getChannel(), allowedChannel);
            return;
        }

        String text = unwrapCommand(MENTION.matcher(e.getText() == null ? "" : e.getText()).replaceAll(""));
        // 一則訊息只允許一個團隊目標，避免多個 App 各自建立實作。
        long targets = (props.identities().isEmpty() ? java.util.List.of(SlackNotifier.Identity.values()) : props.identities()).stream()
                .map(notifier::mention).filter(java.util.Objects::nonNull)
                .filter(m -> m.startsWith("<@") && (e.getText() == null ? "" : e.getText()).contains(m)).count();
        if (targets > 1) {
            notifier.post(role, e.getChannel(), threadTs, "請一次只 @一個團隊角色，未建立任務。");
            return;
        }
        if (text.isEmpty()) {
            notifier.post(SlackNotifier.Identity.SYSTEM, e.getChannel(), threadTs,
                    "請在提及我的時候一併說明需求，例如：\n"
                  + notifier.mention(SlackNotifier.Identity.SYSTEM) + " 列出所有 API");
            return;
        }

        // 與按鈕同一條路：只停這一輪、任務保留。沒有按鈕可按時（例如進度訊息還沒貼出）用打字的。
        var interrupt = Pattern.compile("^中斷\\s+#(\\d+)\\s*$").matcher(text);
        if (interrupt.matches()) {
            Task existing = orchestrator.lookup(interrupt.group(1)).orElse(null);
            if (existing == null || !existing.slackChannel().equals(e.getChannel()) || !existing.slackThreadTs().equals(threadTs)) {
                notifier.post(SlackNotifier.Identity.SYSTEM, e.getChannel(), threadTs, "請在該任務原討論串送出中斷指令，未變更任何任務。");
                return;
            }
            orchestrator.interrupt(existing, e.getUser());
            return;
        }
        // 驗收負責人命令開 PR：與停止訊息的「開 PR」按鈕送同一串指令，權限與證據檢查都在流程引擎。
        var publish = Pattern.compile("^開\\s*PR\\s+#(\\d+)\\s*$", Pattern.CASE_INSENSITIVE).matcher(text);
        if (publish.matches()) {
            Task existing = orchestrator.lookup(publish.group(1)).orElse(null);
            if (existing == null || !existing.slackChannel().equals(e.getChannel()) || !existing.slackThreadTs().equals(threadTs)) {
                notifier.post(SlackNotifier.Identity.SYSTEM, e.getChannel(), threadTs, "請在該任務原討論串送出開 PR 指令，未變更任何任務。");
                return;
            }
            orchestrator.requestTriage(existing, com.zipe.aiteam.flow.HumanOptions.PUBLISH_COMMAND, e.getUser());
            return;
        }
        // 管理者確認配置的驗證指令在基準分支上能不能過；不建立任務，結果另開一則訊息。
        if (BASELINE.matcher(text).matches() && role == SlackNotifier.Identity.SYSTEM) {
            orchestrator.requestBaseline(e.getChannel(), e.getUser(), "由 <@" + e.getUser() + "> 手動要求");
            return;
        }
        boolean topLevel = e.getThreadTs() == null;
        var triage = Pattern.compile("^(處理|補充)\\s+#(\\d+)(?:\\s+([\\s\\S]+))?$").matcher(text);
        if (triage.matches()) {
            Task existing = orchestrator.lookup(triage.group(2)).orElse(null);
            if (topLevel && triage.group(3) != null && routeToTask(existing, e, threadTs, triage.group(3))) return;
            if (existing == null || !existing.slackChannel().equals(e.getChannel()) || !existing.slackThreadTs().equals(threadTs)) {
                notifier.post(SlackNotifier.Identity.SYSTEM, e.getChannel(), threadTs, "請在該任務原討論串送出處理／補充指令，未建立或變更任務。");
                return;
            }
            if ("補充".equals(triage.group(1)) && triage.group(3) == null) {
                notifier.post(SlackNotifier.Identity.SYSTEM, e.getChannel(), threadTs, "補充指令需要實際答案，未新增派工。");
                return;
            }
            orchestrator.requestTriage(existing, triage.group(3) == null ? "請 PM 分析並處理 QA 的阻礙。" : triage.group(3), e.getUser());
            return;
        }
        var bare = BARE_COMMAND.matcher(text);
        if (topLevel && role == SlackNotifier.Identity.SYSTEM && bare.matches() && routable(bare.group(2))
                && routeToTask(orchestrator.lookup(bare.group(1)).orElse(null), e, threadTs, bare.group(2).trim())) return;
        if (role == SlackNotifier.Identity.SYSTEM) orchestrator.createAndSubmit(e.getChannel(), threadTs, e.getUser(), text);
        else orchestrator.requestRole(role, e.getChannel(), threadTs, e.getUser(), text);
    }

    /**
     * project.autonomy.channelCommandRouting 開啟時，頻道頂層對既有任務下的指令轉回該任務處理（回覆仍貼在原討論串），
     * 不另開新任務。只限同頻道、白名單使用者（前面已檢查）；別的任務討論串裡的指令照舊拒絕。
     */
    private boolean routeToTask(Task existing, AppMentionEvent e, String threadTs, String command) {
        if (existing == null || !existing.slackChannel().equals(e.getChannel()) || !orchestrator.channelCommandRouting()) return false;
        audit.record(existing.id(), e.getUser(), AuditLog.ACTION_RECEIVED, "頻道頂層指令轉回原任務：" + command);
        notifier.post(SlackNotifier.Identity.SYSTEM, e.getChannel(), threadTs, "已把這則指令轉給任務 #" + existing.id()
                + "，結果會貼在該任務原本的討論串；沒有建立新任務。");
        orchestrator.requestTriage(existing, command, e.getUser());
        return true;
    }

    private void registerPlanActions(App app) {
        for (String action : new String[]{PlanReview.APPROVE, PlanReview.REVISE}) {
            app.blockAction(action, (req, ctx) -> {
                var p = req.getPayload();
                String user = p.getUser().getId(), value = p.getActions().getFirst().getValue();
                String channel = p.getChannel() == null ? null : p.getChannel().getId();
                String thread = p.getMessage() == null ? null : p.getMessage().getThreadTs();
                ingress.execute(() -> {
                    try { handlePlanButton(user, value, action, channel, thread); }
                    catch (Exception e) { log.error("處理計畫按鈕失敗：{}", com.zipe.aiteam.runtime.Diagnostics.describe(e)); }
                });
                return ctx.ack();
            });
        }
    }
    /**
     * 停止訊息上的選項按鈕。按鈕不是新的權限路徑：它送出的就是使用者本來要手動打的
     * 那串「補充」指令，走同一套白名單與同一套驗證，只是不必自己抄授權碼。
     */
    private void registerHumanGateActions(App app) {
        app.blockAction(HumanGate.ACTION_PATTERN, (req, ctx) -> {
            var p = req.getPayload();
            String user = p.getUser().getId(), value = p.getActions().getFirst().getValue();
            String channel = p.getChannel() == null ? null : p.getChannel().getId();
            String thread = p.getMessage() == null ? null : p.getMessage().getThreadTs();
            ingress.execute(() -> {
                try { handleHumanGateButton(user, value, channel, thread); }
                catch (Exception e) { log.error("處理停止選項按鈕失敗：{}", com.zipe.aiteam.runtime.Diagnostics.describe(e)); }
            });
            return ctx.ack();
        });
    }
    void handleHumanGateButton(String user, String value, String channel, String thread) {
        if (!props.allowedUsers().contains(user)) return;
        var command = HumanGate.Command.parse(value);
        orchestrator.lookup(command.task()).ifPresent(task -> {
            // 按鈕可能來自別的討論串或被轉貼；只認任務原本的討論串。
            if (!task.slackChannel().equals(channel) || !task.slackThreadTs().equals(thread)) return;
            audit.record(task.id(), user, AuditLog.ACTION_BUTTON, "以停止選項按鈕送出：" + command.command());
            orchestrator.requestTriage(task, command.command(), user);
        });
    }
    /**
     * 要填內容的選項：按鈕打開輸入視窗，送出後組成與打字相同的「補充」指令。
     * views.open 的 trigger_id 只有 3 秒效期，所以開窗在 handler 內同步做；送出後的處理才交給 ingress。
     */
    private void registerHumanInputActions(App app) {
        app.blockAction(HumanInput.ACTION_PATTERN, (req, ctx) -> {
            try {
                var p = req.getPayload();
                String channel = p.getChannel() == null ? null : p.getChannel().getId();
                String thread = p.getMessage() == null ? null : p.getMessage().getThreadTs();
                var view = inputView(p.getUser().getId(), p.getActions().getFirst().getValue(), channel, thread);
                if (view != null) {
                    var opened = ctx.client().viewsOpen(r -> r.triggerId(p.getTriggerId()).view(view));
                    if (!opened.isOk()) log.warn("打開輸入視窗失敗：{}", opened.getError());
                }
            } catch (Exception e) { log.error("處理輸入按鈕失敗：{}", com.zipe.aiteam.runtime.Diagnostics.describe(e)); }
            return ctx.ack();
        });
        app.viewSubmission(HumanInput.CALLBACK, (req, ctx) -> {
            var p = req.getPayload();
            String user = p.getUser().getId(), metadata = p.getView().getPrivateMetadata();
            String text = null;
            var values = p.getView().getState() == null ? null : p.getView().getState().getValues();
            if (values != null && values.get(HumanInput.BLOCK) != null && values.get(HumanInput.BLOCK).get(HumanInput.FIELD) != null)
                text = values.get(HumanInput.BLOCK).get(HumanInput.FIELD).getValue();
            try {
                var submission = HumanInput.Submission.parse(metadata);
                if (HumanInput.command(submission, text) == null)
                    return ctx.ack(r -> r.responseAction("errors").errors(java.util.Map.of(HumanInput.BLOCK, "請填寫內容")));
            } catch (Exception e) { return ctx.ack(); }
            String submitted = text;
            ingress.execute(() -> {
                try { handleHumanInputSubmit(user, metadata, submitted); }
                catch (Exception e) { log.error("處理輸入視窗送出失敗：{}", com.zipe.aiteam.runtime.Diagnostics.describe(e)); }
            });
            return ctx.ack();
        });
    }
    /** 開窗前的檢查：白名單、任務存在、按鈕來自原討論串。重驗由系統帶入當下的節點與 HEAD。 */
    com.slack.api.model.view.View inputView(String user, String value, String channel, String thread) {
        if (!props.allowedUsers().contains(user)) return null;
        var choice = HumanInput.Choice.parse(value);
        Task task = orchestrator.lookup(choice.task()).orElse(null);
        if (task == null || !task.slackChannel().equals(channel) || !task.slackThreadTs().equals(thread)) return null;
        String extra = "";
        if (com.zipe.aiteam.flow.HumanOptions.REMEDIATE.equals(choice.kind())) {
            extra = orchestrator.remediationTarget(task).orElse(null);
            if (extra == null) {
                notifier.post(SlackNotifier.Identity.SYSTEM, channel, thread,
                        "任務 #" + task.id() + " 目前沒有可以重驗的驗收步驟，或工作區查不到最新 commit；未授權重驗。");
                return null;
            }
        }
        return HumanInput.modal(new HumanInput.Submission(task.id(), choice.kind(), channel, thread, extra));
    }
    void handleHumanInputSubmit(String user, String metadata, String text) {
        if (!props.allowedUsers().contains(user)) return;
        var submission = HumanInput.Submission.parse(metadata);
        String command = HumanInput.command(submission, text);
        if (command == null) return;
        orchestrator.lookup(submission.task()).ifPresent(task -> {
            if (!task.slackChannel().equals(submission.channel()) || !task.slackThreadTs().equals(submission.thread())) return;
            audit.record(task.id(), user, AuditLog.ACTION_BUTTON, "以輸入視窗送出：" + command);
            orchestrator.requestTriage(task, command, user);
        });
    }
    private void registerSpinoffActions(App app) {
        app.blockAction(SpinoffButtons.ACTION_PATTERN, (req, ctx) -> {
            var p = req.getPayload();
            String user = p.getUser().getId(), value = p.getActions().getFirst().getValue();
            String channel = p.getChannel() == null ? null : p.getChannel().getId();
            String thread = p.getMessage() == null ? null : p.getMessage().getThreadTs();
            ingress.execute(() -> {
                try { handleSpinoffButton(user, value, channel, thread); }
                catch (Exception e) { log.error("處理開成新需求按鈕失敗：{}", com.zipe.aiteam.runtime.Diagnostics.describe(e)); }
            });
            return ctx.ack();
        });
    }
    void handleSpinoffButton(String user, String value, String channel, String thread) {
        if (!props.allowedUsers().contains(user)) return;
        var choice = SpinoffButtons.Choice.parse(value);
        orchestrator.lookup(choice.task()).ifPresent(task -> {
            // 按鈕可能被轉貼；只認任務原本的討論串。
            if (task.slackChannel().equals(channel) && task.slackThreadTs().equals(thread))
                orchestrator.spinoff(task, choice.index(), user);
        });
    }
    void handlePlanButton(String user, String value, String action, String channel, String thread) {
        if (!props.allowedUsers().contains(user) || !java.util.Set.of(PlanReview.APPROVE, PlanReview.REVISE).contains(action)) return;
        var decision = PlanReview.Decision.parse(value);
        orchestrator.lookup(decision.task()).ifPresent(task -> {
            if (task.slackChannel().equals(channel) && task.slackThreadTs().equals(thread))
                orchestrator.planDecision(task, user, decision, PlanReview.APPROVE.equals(action), channel, thread);
        });
    }
    private void handleButton(String userId, String taskId, String action) {
        Optional<Task> found = orchestrator.lookup(taskId);
        if (found.isEmpty()) {
            log.warn("按鈕指向不存在的任務 {}", taskId);
            return;
        }
        Task task = found.get();

        // 按鈕權限與觸發權限用同一份白名單，否則前面的權限設計形同虛設
        if (!props.allowedUsers().contains(userId)) {
            audit.record(taskId, userId, AuditLog.ACTION_REJECTED, "非白名單使用者嘗試操作按鈕 " + action);
            notifier.post(SlackNotifier.Identity.SYSTEM,
                    task.slackChannel(), task.slackThreadTs(),
                    "🚫 <@" + userId + "> 不在允許清單中，無法審批任務 #" + taskId + "。");
            return;
        }

        if (task.state().isTerminal()) {
            notifier.post(SlackNotifier.Identity.SYSTEM,
                    task.slackChannel(), task.slackThreadTs(),
                    "ℹ️ 任務 #" + taskId + " 已經是 " + task.state() + " 狀態，這個按鈕不再有作用。");
            return;
        }

        switch (action) {
            case SlackBlocks.ACTION_CONTINUE -> orchestrator.continueTask(task, userId);
            case SlackBlocks.ACTION_APPROVE -> orchestrator.approvePr(task, userId);
            case SlackBlocks.ACTION_REDO -> {
                notifier.post(SlackNotifier.Identity.SYSTEM,
                        task.slackChannel(), task.slackThreadTs(),
                        "🔄 <@" + userId + "> 要求重做任務 #" + taskId + "（第 "
                      + (task.redoCount() + 1) + " 次）。");
                orchestrator.redo(task,
                        "請重新檢視先前的分析，特別注意人類審核者在討論串中提出的疑慮。",
                        userId);
            }
            case SlackBlocks.ACTION_ABORT -> orchestrator.abort(task, userId);
            case SlackBlocks.ACTION_INTERRUPT -> orchestrator.interrupt(task, userId);
            default -> log.warn("未知的 action：{}", action);
        }
    }

    @jakarta.annotation.PreDestroy
    public void stop() {
        ingress.shutdown();
        for (SocketModeApp socket : roleSockets) {
            try { socket.stop(); } catch (Exception e) { log.warn("關閉角色連線失敗"); }
        }
        try {
            if (socketApp != null) socketApp.stop();
        } catch (Exception e) {
            log.warn("關閉 Socket Mode 時發生例外", e);
        }
    }
}
