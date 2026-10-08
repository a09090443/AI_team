package com.zipe.aiteam.flow;

import com.zipe.aiteam.task.Task;
import java.util.ArrayList;
import java.util.List;
import static com.zipe.aiteam.flow.TeamDefinition.*;

/**
 * 停止時把「現在可以做什麼」講清楚。
 *
 * 舊版只說「請補充具體答案」，但系統其實知道自己在等哪一種決定，也知道有哪些指令可用。
 * 少了這段，任務會停在 AWAIT_HUMAN 而沒有人知道下一步該打什麼字 —— #1014 就是這樣卡了三天。
 * 這裡只列出目前狀態真的可用的選項，不列不適用的，避免變成一份人人跳過的指令表。
 *
 * 能用一個按鈕表達的選項會帶 command：按鈕按下去就送出同一串文字，
 * 走的是和手動輸入完全相同的驗證路徑，不另開權限。
 * 需要自由填寫內容的（補充說明、需求變更、人工修復重驗）沒有 command，改用按鈕打開 Slack 輸入視窗（HumanInput）；
 * 打字指令仍然有效，只在按鈕版面送不出去時才列在文字裡。
 */
public final class HumanOptions {
    private HumanOptions() {}

    /** button 為 true 表示這個選項可以用一顆按鈕完成；input 為 true 表示按鈕會打開輸入視窗讓你填內容。 */
    public record Option(String kind, String label, String hint, String command) {
        public boolean button() { return command != null; }
        public boolean input() { return command == null && INPUT_KINDS.contains(kind); }
    }
    public static final java.util.Set<String> INPUT_KINDS = java.util.Set.of("REQUIREMENT", "SUPPLEMENT", "REMEDIATE");

    /** 工作區現況；head 為 null 表示沒有工作區或取不到。 */
    /**
     * uncommitted／fingerprint 只在工作區不乾淨時有值：用來列出是哪些檔案，
     * 並讓「保存並承接」按鈕綁定使用者看到的那一版內容。
     */
    public record Workspace(String head, boolean clean, List<String> uncommitted, String fingerprint) {
        public static final Workspace NONE = new Workspace(null, true);
        public Workspace(String head, boolean clean) { this(head, clean, List.of(), null); }
        public Workspace {
            uncommitted = uncommitted == null ? List.of() : List.copyOf(uncommitted);
        }
        /** 列出未提交的檔案，最多 max 個，其餘以數量表示。 */
        public String listUncommitted(int max) {
            if (uncommitted.isEmpty()) return "";
            StringBuilder out = new StringBuilder();
            uncommitted.stream().limit(max).forEach(path -> out.append("\n　・").append(path));
            if (uncommitted.size() > max) out.append("\n　・…以及另外 ").append(uncommitted.size() - max).append(" 個檔案");
            return out.toString();
        }
        boolean canSaveAndContinue() { return !clean && fingerprint != null && !uncommitted.isEmpty(); }
    }

    public static final String RESTORE = "RESTORE";
    public static final String RESUME = "RESUME";
    public static final String EXTEND = "EXTEND";
    public static final String REPORT = "REPORT";
    public static final String PLAN = "PLAN";
    public static final String REMEDIATE = "REMEDIATE";
    public static final String REQUIREMENT = "REQUIREMENT";
    public static final String SUPPLEMENT = "SUPPLEMENT";
    public static final String SUCCESSOR = "SUCCESSOR";
    public static final String SUCCESSOR_COMMAND = "承接成新任務";
    /** 承接時一併保存未提交修改的指令前綴；後面接 fingerprint。 */
    public static final String SUCCESSOR_SAVE_COMMAND = SUCCESSOR_COMMAND + " 保存";
    public static final String RECOVER = "RECOVER";
    public static final String CONFIG_UPDATE = "CONFIG_UPDATE";
    public static final String CONFIG_APPROVE = "CONFIG_APPROVE";
    public static final String STATUS = "STATUS";
    public static final String STATUS_COMMAND = "狀態";
    public static final String PUBLISH = "PUBLISH";
    /** 驗收負責人命令系統開 PR；文字指令、按鈕與 agent 的 publish 選項都送這串。 */
    public static final String PUBLISH_COMMAND = "開 PR";

    /** 證據有效、可以直接開 PR 時的說明；不能開時回 null（按鈕不出現）。 */
    static String publishHint(FlowRun flow, Workspace ws) {
        var check = OwnerPublish.evaluate(flow, ws.head(), ws.clean());
        if (!check.allowed()) return null;
        return "只有驗收負責人可以按。"
                + (check.needsReview() ? "審查還沒跑過，會先執行審查；審查通過（或只剩等級寫成提醒的項目）就推分支、開 PR，不必再按核准，審查有問題時停下來。"
                        : "系統會再做一次發布前檢查（缺少系統獨立驗證收據時先重新執行），通過就推分支、開 PR，不必再按核准。")
                + (check.waivesUncertainties() ? "驗收有 " + check.uncertainties().size() + " 項未核實事項原本擋下發布，"
                        + "按下去等於由你放行，PR 說明會列出這些項目，合併前需另外實測。" : "")
                + (check.needsWaiver() ? "審查有 " + check.waived().size() + " 項等級寫成提醒（例如 INFO）原本擋下發布，"
                        + "按下去等於由你放行，PR 說明會列出這些項目。" : "");
    }

    public static List<Option> options(Task task, FlowRun flow, boolean canSupplement,
                                       boolean briefReports, String extensionToken, Workspace workspace) {
        return options(task, flow, canSupplement, briefReports, extensionToken, workspace, false);
    }

    /**
     * 目前這一步卡在重試上限：引擎一定會在啟動前擋下，而且延長額度也幫不上忙。
     * 這時選擇題、補充說明、「繼續」都不會讓任何東西重新執行 —— #1016 的測試計畫節點
     * 就是這樣，使用者連按三次選項，每次都立刻停回來。
     */
    static boolean stuckAtLimit(FlowRun flow) {
        if (flow.snapshot == null || flow.dialogue) return false;
        Node node = flow.snapshot.definition().nodes().get(flow.node);
        if (node == null || node.kind() != Kind.AGENT) return false;
        return !FlowLimits.canStart(flow, node) && !AutomaticHandoff.extensionWouldHelp(flow);
    }

    /** 配置修正並核准後，可以授權目前這一步再跑一次；與授權本身同一套規則。 */
    static String recoverableNode(FlowRun flow) {
        if (flow.snapshot == null) return null;
        return VerificationRecovery.blockingReason(flow, flow.node, flow.snapshot.revision(), flow.acceptanceOwner) == null
                ? flow.node : null;
    }

    public static List<Option> options(Task task, FlowRun flow, boolean canSupplement, boolean briefReports,
                                       String extensionToken, Workspace workspace, boolean configUpdate) {
        List<Option> options = new ArrayList<>();
        Workspace ws = workspace == null ? Workspace.NONE : workspace;

        // 已由新任務承接：這裡不再有任何動作。
        if (flow.successor != null) return List.of();

        // 自動處理次數全部用完時，任何選項都不會讓任務往下跑；只提供承接成新任務。
        if (exhausted(flow)) {
            if (ws.canSaveAndContinue())
                options.add(new Option(SUCCESSOR, "保存未提交的修改並承接",
                        "工作區有 " + ws.uncommitted().size() + " 個檔案改了但還沒 commit。按下去系統會先把它們 commit 到這個任務，"
                                + "再開新任務接著做；新任務仍會重新驗收與審查，不會把這些修改當成已通過。",
                        SUCCESSOR_SAVE_COMMAND + " " + ws.fingerprint()));
            else options.add(new Option(SUCCESSOR, "承接成新任務",
                    "開一個新任務，從這個任務的最新 commit 接著做，並帶入已做過的決定；次數重新計算，"
                            + "需求、測試計畫與 PR 仍要重新核准。", SUCCESSOR_COMMAND));
            // 開 PR 不經過任何 agent 節點，換手次數用完也不受影響。
            String publish = publishHint(flow, ws);
            if (publish != null) options.add(new Option(PUBLISH, "開 PR", publish, PUBLISH_COMMAND));
            if (briefReports) options.add(new Option(REPORT, "看完整報告",
                    "只是把完整內容叫出來看，不會改變任務狀態。", "檢視完整報告"));
            return List.copyOf(options);
        }

        boolean stuck = stuckAtLimit(flow);

        String recoverable = recoverableNode(flow);
        if (recoverable != null)
            options.add(new Option(RECOVER, "重新執行這一步",
                    "新配置已經套用。按下去會依新配置讓「" + recoverable + "」再跑一次，不會重設任何歷史次數。",
                    "核准恢復驗收 " + recoverable + " " + flow.snapshot.revision()));
        if (configUpdate)
            options.add(new Option(CONFIG_UPDATE, "檢視配置更新",
                    "管理者已經部署新的配置。按下去會列出差異，確認後才會套用。", "檢視配置更新"));
        // 卡在上限又沒有恢復方式：唯一的出路是管理者部署新配置，部署後要有地方重新檢查。
        if (stuck && recoverable == null && !configUpdate)
            options.add(new Option(STATUS, "重新整理選項",
                    "管理者部署新配置後按這個，系統會重新檢查並列出可用的選項（例如「檢視配置更新」）；不會啟動 agent。",
                    STATUS_COMMAND));

        // agent 提出的選擇題放最前面：這是對方停下來最主要要回答的事。卡在上限時選了也不會重新執行。
        if (!stuck) options.addAll(decisionOptions(flow, ws));

        // 開 PR 不重跑任何 agent 節點，卡在上限也能用。agent 的 publish 選項已經是同一個指令時不重複。
        String publish = publishHint(flow, ws);
        if (publish != null && options.stream().noneMatch(o -> PUBLISH_COMMAND.equals(o.command())))
            options.add(new Option(PUBLISH, "開 PR", publish, PUBLISH_COMMAND));

        String restorable = flow.snapshot == null ? null : ContractRecovery.candidate(flow, ws.head(), ws.clean());
        if (restorable != null)
            options.add(new Option(RESTORE, "恢復「" + restorable + "」的結果",
                    "那份結果先前被系統規則否決，但重新檢查已經通過。恢復後不會重跑、不會重設次數。",
                    "核准恢復前置契約 " + restorable + (ws.head() == null ? "" : " " + ws.head())));

        boolean resume = !stuck && restorable == null && resumeWillProceed(flow);
        Node atNode = flow.snapshot == null ? null : flow.snapshot.definition().nodes().get(flow.node);
        if (resume && atNode != null && atNode.kind() == Kind.APPROVAL)
            options.add(new Option(RESUME, "重新進入核准 PR",
                    "系統會重新檢查驗收證據、commit 與工作區，通過後重新貼出「核准 PR」按鈕；這一步不會推送，也不會呼叫 agent。",
                    "續跑"));
        else if (resume)
            options.add(new Option(RESUME, "繼續執行",
                    "現在直接繼續就會開始處理，不需要其他授權，也不會多用掉一次自動處理的機會。",
                    "續跑"));

        // 直接續跑就能前進時，不再推延長額度 —— 那會讓人多做一步、白用一次授權。
        if (!stuck && !resume && extensionToken != null && !extensionToken.isBlank())
            options.add(new Option(EXTEND, "再給系統兩次機會",
                    "系統自己試過的次數已經用完。按下去只增加 " + AutomaticHandoff.EXTENSION_SIZE
                            + " 次，不會重設已經累積的次數，也不等於核准 PR。",
                    "核准延長自動交接 " + extensionToken));

        Node current = flow.snapshot == null ? null : flow.snapshot.definition().nodes().get(flow.node);
        if (flow.changeTarget != null)
            options.add(new Option(REQUIREMENT, "需求要改",
                    "只有需求負責人可以核准；核准後會回到需求節點重做，既有驗收證據會失效。",
                    null));
        // 停在測試計畫：需求本身有問題（例如有一條根本測不了）最常在這裡浮現，而此時沒有人會替你提出修改。
        else if (current != null && current.gate() == Gate.TEST_PLAN && flow.baselineRevision != null)
            options.add(new Option(REQUIREMENT, "需求要改",
                    "如果是需求清單本身有問題（例如有一條需求根本無法測試），需求負責人可以直接核准修改。"
                            + "之後會重新整理需求，再重跑設計、測試計畫、實作、驗收與審查；PR 仍需要你核准。",
                    null));

        String planNode = current == null ? null : current.plan();
        if (planNode != null && PlanApproval.blockingReason(flow, planNode) != null)
            options.add(new Option(PLAN, "核准測試計畫",
                    "計畫卡會另外貼出來，上面有專用按鈕。核准計畫不等於核准 PR。", null));

        if (verificationAwaitingRemediation(flow))
            options.add(new Option(REMEDIATE, "你自己修好了，請它重驗",
                    "工作區必須乾淨，而且 commit 要等於目前的 HEAD；重驗之後仍須重新核准測試計畫。",
                    null));

        if (canSupplement && !stuck) options.add(new Option(SUPPLEMENT, "告訴它還缺什麼",
                "你寫的內容會進入流程脈絡，交由分流重新判斷。"
                        + "如果你知道還有沒修好的問題，直接把問題寫進來，系統才派得出去；"
                        + "只寫「續跑」不會改變上一輪的判斷依據。", null));

        // 任務還能繼續時也允許承接：舊任務永遠用建立時的配置，改進後的團隊規則只能透過新任務套用。
        if (canContinueAsNewTask(flow)) {
            if (ws.canSaveAndContinue())
                options.add(new Option(SUCCESSOR, "保存修改並改用新任務繼續",
                        "工作區有 " + ws.uncommitted().size() + " 個檔案改了但還沒 commit，系統會先代為提交；之後同「改用新任務繼續」。",
                        SUCCESSOR_SAVE_COMMAND + " " + ws.fingerprint()));
            else options.add(new Option(SUCCESSOR, "改用新任務繼續",
                    "開一個新任務，從目前最新的成果接著做，並改用最新的團隊規則與配置；這個任務會停在這裡不再處理。"
                            + "次數重新計算，需求、測試計畫與 PR 仍要重新核准。", SUCCESSOR_COMMAND));
        }

        if (briefReports) options.add(new Option(REPORT, "看完整報告",
                "只是把完整內容叫出來看，不會改變任務狀態。", "檢視完整報告"));

        return List.copyOf(options);
    }

    public static final String DECISION = "DECISION";

    /**
     * 目前節點最新報告裡的 human_decision 選項。
     * 按下去送出的是一段「負責人決定」的補充文字（含選項後果），交由流程重新判斷；
     * 以「負責人決定：」開頭，不會被當成任何「核准…」指令。
     */
    /**
     * 同一個問題已經回答過（交接紀錄裡有對應的決定），agent 卻又原封不動地問一次。
     * 再給同樣的按鈕只會讓人重複按、流程原地重跑；這時不提供選項，改說明原因。
     */
    static boolean repeatedDecision(FlowRun flow) {
        com.fasterxml.jackson.databind.JsonNode report = latestReport(flow);
        if (!com.zipe.aiteam.slack.SlackReport.hasDecision(report) || flow.context == null) return false;
        String question = clip(report.get("human_decision").path("question").asText(), 300);
        return flow.context.contains("負責人決定：" + question) || flow.context.contains("核准需求變更 " + question);
    }

    static List<Option> decisionOptions(FlowRun flow) { return decisionOptions(flow, Workspace.NONE); }

    /**
     * 選項的 action 決定按下去由誰處理：decide 交回流程重新判斷；close、successor 由系統直接執行。
     * #1022 的「確認結案」以前只能送回分流，按了五次每次都重跑後又停下 —— 流程本身沒有結案路線。
     * 舊報告沒有 action，一律視為 decide。
     */
    static List<Option> decisionOptions(FlowRun flow, Workspace workspace) {
        com.fasterxml.jackson.databind.JsonNode report = latestReport(flow);
        if (!com.zipe.aiteam.slack.SlackReport.hasDecision(report)) return List.of();
        Workspace ws = workspace == null ? Workspace.NONE : workspace;
        // 同一題再問一次時，交回流程的選項只會原地重跑；系統直接執行的動作不受影響，照樣提供。
        boolean repeated = repeatedDecision(flow);
        var d = report.get("human_decision");
        String question = clip(d.path("question").asText(), 300);
        // 等待需求變更核准時，系統只接受「核准需求變更 <內容>」，其他補充一律擋下。
        // 這時對方的選擇本身就是變更內容，按鈕必須直接送核准指令，按鈕文字也要明講是核准 ——
        // #1014 的「全部一起修」按了兩次都被這個閘門擋掉，就是因為送成了一般補充。
        boolean approvesChange = flow.changeTarget != null;
        List<Option> result = new ArrayList<>();
        for (var option : d.path("options")) {
            if (result.size() >= com.zipe.aiteam.slack.SlackReport.MAX_DECISION_OPTIONS) break;
            String label = clip(option.path("label").asText().strip(), 40);
            if (label.isBlank()) continue;
            String consequence = clip(option.path("consequence").asText(), 300);
            String choice = question + "　選擇：" + label + "（" + consequence + "）";
            switch (option.path("action").asText(ACTION_DECIDE)) {
                case ACTION_CLOSE -> result.add(new Option(DECISION, label, consequence + CLOSE_NOTICE,
                        CLOSE_COMMAND + " " + clip(question + "　選擇：" + label, 300)));
                case ACTION_SUCCESSOR -> {
                    if (!canContinueAsNewTask(flow)) continue;
                    result.add(new Option(DECISION, label, consequence + SUCCESSOR_NOTICE, ws.canSaveAndContinue()
                            ? SUCCESSOR_SAVE_COMMAND + " " + ws.fingerprint() : SUCCESSOR_COMMAND));
                }
                case ACTION_PUBLISH -> {
                    // 證據不符合時按了也開不出來，選項直接不出現。
                    String hint = publishHint(flow, ws);
                    if (hint == null) continue;
                    result.add(new Option(DECISION, label, consequence + "（" + hint + "）", PUBLISH_COMMAND));
                }
                default -> {
                    if (repeated) continue;
                    result.add(approvesChange
                            ? new Option(DECISION, "核准：" + label, consequence, "核准需求變更 " + choice)
                            : new Option(DECISION, label, consequence, "負責人決定：" + choice));
                }
            }
        }
        return result;
    }

    public static final String ACTION_DECIDE = "decide";
    public static final String ACTION_CLOSE = "close";
    public static final String ACTION_SUCCESSOR = "successor";
    public static final String ACTION_PUBLISH = "publish";
    /** 負責人結案：任務中止但保留工作區；後面接所選的問題與選項，只作紀錄。 */
    public static final String CLOSE_COMMAND = "負責人結案";
    static final String CLOSE_NOTICE = "（系統直接結案：任務中止、不再派送任何節點，工作區保留供你確認；"
            + "PR 與合併由你在系統外處理。）";
    static final String SUCCESSOR_NOTICE = "（系統直接開新任務、以最新配置接著做；需求、測試計畫與 PR 仍要重新核准。）";

    /** 與停止訊息固定的「改用新任務繼續」同一個條件。 */
    static boolean canContinueAsNewTask(FlowRun flow) {
        return flow.snapshot != null && !flow.dialogue && flow.successor == null;
    }

    /** 受阻發現比 receipt 新：節點受阻時不會清掉舊的成功 receipt。 */
    private static com.fasterxml.jackson.databind.JsonNode latestReport(FlowRun flow) {
        if (flow.blockedFindings != null && flow.blockedFindings.containsKey(flow.node))
            return flow.blockedFindings.get(flow.node);
        return flow.receipts.get(flow.node);
    }

    private static String clip(String text, int max) {
        String value = text == null ? "" : text;
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    /** 相容舊呼叫端：沒有工作區資訊時視為沒有工作區。 */
    public static List<Option> options(Task task, FlowRun flow, boolean canSupplement,
                                       boolean briefReports, String extensionToken) {
        return options(task, flow, canSupplement, briefReports, extensionToken, Workspace.NONE);
    }

    /**
     * 任務已經走到系統設計的硬上限：流程總次數超過上限、延長額度也已達硬上限，
     * 而且沒有任何已登記事件、恢復授權可以讓下一步通過。這時不論按什麼、補充什麼，
     * 下一個節點都會在啟動前被防循環保護擋下。
     *
     * #1014 在這個狀態下仍被提供「核准需求變更」等選項，按了卻完全不會前進 ——
     * 系統知道走不下去，就必須直接說，而不是讓人一直按。
     */
    static boolean exhausted(FlowRun flow) {
        return FlowLimits.exhausted(flow);
    }

    /** 純文字版本，給沒有按鈕的情境（或按鈕送不出去時）仍然看得到指令。 */
    public static String render(Task task, FlowRun flow, boolean canSupplement,
                                boolean briefReports, String extensionToken, Workspace workspace) {
        return render(task, flow, canSupplement, briefReports, extensionToken, workspace, false);
    }

    /** 預設是純文字版本（附打字指令），給按鈕版面送不出去時使用。 */
    public static String render(Task task, FlowRun flow, boolean canSupplement, boolean briefReports,
                                String extensionToken, Workspace workspace, boolean configUpdate) {
        return render(task, flow, canSupplement, briefReports, extensionToken, workspace, configUpdate, true);
    }

    /**
     * @param typed false 時是按鈕版面的文字：每個選項都有按鈕，不再教人打「補充 #N …」；
     *              true 時附上打字指令，給按鈕版面被 Slack 拒收時的純文字後備。
     */
    public static String render(Task task, FlowRun flow, boolean canSupplement, boolean briefReports,
                                String extensionToken, Workspace workspace, boolean configUpdate, boolean typed) {
        if (flow.successor != null)
            return "\n\n這個任務已由 *任務 #" + flow.successor + "* 承接，之後請到頻道裡「任務 #" + flow.successor
                    + "」的討論串操作；這裡不會再有任何動作。";
        if (exhausted(flow)) return exhaustedNotice(task, flow, workspace, briefReports, typed);
        List<Option> options = options(task, flow, canSupplement, briefReports, extensionToken, workspace, configUpdate);
        StringBuilder out = new StringBuilder();
        if (stuckAtLimit(flow)) {
            Node node = flow.snapshot.definition().nodes().get(flow.node);
            out.append("\n\n*這一步已經重試到上限*\n「").append(flow.node).append("」已經跑了 ")
               .append(flow.visits.getOrDefault(flow.node, 0)).append(" 次（上限 ").append(node.maxVisits())
               .append("），再按選項或補充都不會讓它重新執行。");
            if (recoverableNode(flow) != null)
                out.append("\n新配置已經套用，可以按「重新執行這一步」依新配置再跑一次。");
            else if (configUpdate)
                out.append("\n管理者已經部署新的配置，請先按「檢視配置更新」確認差異並核准，之後就能重新執行這一步。");
            else
                out.append("\n目前沒有可用的恢復方式。若 agent 回報需要調整配置、環境或權限，要由管理者修正並部署新配置；")
                   .append("部署後按「重新整理選項」，就會出現「檢視配置更新」。");
        } else if (repeatedDecision(flow) && options.stream().noneMatch(o -> DECISION.equals(o.kind()))) {
            // 還有結案／承接這類由系統直接執行的選項時，不說「光靠選擇解決不了」。
            out.append("\n\n*這個問題你已經回答過了*\nagent 收到你的選擇後仍停在同一個地方，代表光靠選擇解決不了。")
               .append("請看 agent 說明裡「另外也需要處理」的項目，那通常需要管理者或維運去做。");
        }
        if (options.isEmpty()) return out.toString();
        out.append("\n\n你現在可以做的事：");
        List<String> decisions = options.stream().filter(o -> DECISION.equals(o.kind())).map(Option::label).toList();
        String explain = options.stream().filter(Option::input).filter(o -> SUPPLEMENT.equals(o.kind()) || REQUIREMENT.equals(o.kind()))
                .map(Option::label).findFirst().orElse(null);
        if (!decisions.isEmpty())
            out.append("\n・直接選一個：").append(String.join("／", decisions))
               .append(flow.changeTarget != null
                       ? "（按下去就是核准需求變更，會回到需求階段重做，之後的測試計畫仍要你再核准；"
                       : "（按下方按鈕；")
               .append(typed ? "都不合適就用下面的方式打字說明）"
                       : explain == null ? "都不合適就等管理者處理）" : "都不合適就按「" + explain + "」寫下你的說明）");
        for (Option option : options) {
            if (DECISION.equals(option.kind())) continue;
            out.append("\n・").append(option.label()).append("：").append(option.hint());
            if (typed) {
                if (option.button()) out.append("\n　按下方按鈕，或輸入：補充 #").append(task.id())
                        .append(' ').append(option.command());
                else out.append("\n　輸入：補充 #").append(task.id()).append(' ').append(commandHint(option.kind()));
            } else if (option.input()) out.append("\n　按下方「").append(option.label()).append("」，在跳出的視窗填寫。");
            else if (PLAN.equals(option.kind())) out.append("\n　請用測試計畫卡上的按鈕。");
        }
        out.append("\n停止不等於完成。只有走到發布核准並由你核准，任務才算結束；目前尚未到達。");
        return out.toString();
    }

    public static String render(Task task, FlowRun flow, boolean canSupplement,
                                boolean briefReports, String extensionToken) {
        return render(task, flow, canSupplement, briefReports, extensionToken, Workspace.NONE);
    }

    private static String exhaustedNotice(Task task, FlowRun flow, Workspace workspace, boolean briefReports, boolean typed) {
        Workspace ws = workspace == null ? Workspace.NONE : workspace;
        StringBuilder out = new StringBuilder("\n\n*這個任務已經無法再自動往下跑*\n")
                .append("整個任務已經換手 ").append(flow.transitions)
                .append(" 次（上限 ").append(flow.snapshot.definition().maxTransitions()).append("），")
                .append("而目前停住的這一步，沒有任何可用的延長或恢復授權能讓它通過。")
                .append("\n這是防止任務無限循環的保護，不是故障。在這個任務裡不論按什麼、補充什麼，")
                .append("下一步都會在開始前被擋下。")
                .append("\n\n*目前的成果都還在*")
                .append(ws.head() == null ? "\n任務工作區保留原樣。"
                        : "\n任務工作區保留原樣，最新 commit=" + ws.head() + (ws.clean() ? "（已全部提交）" : ""));
        if (ws.canSaveAndContinue())
            out.append("\n另外有 ").append(ws.uncommitted().size()).append(" 個檔案改了但還沒 commit（還沒存進版本紀錄）：")
               .append(ws.listUncommitted(10))
               .append("\n這通常是某個角色改了檔案，但它沒有 commit 的權限。承接新任務只會帶走已 commit 的內容，")
               .append("所以要先把這些修改存起來。")
               .append("\n\n*怎麼繼續*")
               .append("\n按「保存未提交的修改並承接」：系統先把上面這些檔案 commit 到這個任務，再開一個新任務從那裡接著做，")
               .append("並帶入這個任務的交接紀錄與你做過的決定。次數重新計算；新任務會重新驗收與審查這些修改，")
               .append("需求、測試計畫與 PR 仍會請你重新核准。新任務會在頻道開新的討論串。")
               .append(typed ? "\n或輸入：補充 #" + task.id() + " " + SUCCESSOR_SAVE_COMMAND + " " + ws.fingerprint() : "");
        else out.append("\n\n*怎麼繼續*")
                .append("\n按「承接成新任務」：開一個新任務，工作區從上面這個 commit 接著做，")
                .append("並帶入這個任務的交接紀錄與你做過的決定（包含已核准的需求變更）。次數重新計算；")
                .append("需求、測試計畫與 PR 仍會請你重新核准。新任務會在頻道開新的討論串。")
                .append(typed ? "\n或輸入：補充 #" + task.id() + " " + SUCCESSOR_COMMAND : "");
        if (briefReports) out.append(typed ? "\n想先看細節：補充 #" + task.id() + " 檢視完整報告" : "\n想先看細節：按「看完整報告」。");
        return out.toString();
    }

    private static String commandHint(String kind) {
        return switch (kind) {
            case REQUIREMENT -> "核准需求變更 <要怎麼改與原因，例如：移除 REQ-016，因為版本追溯由系統負責、不是產品需求>";
            case PLAN -> "核准測試計畫 <計畫節點> <計畫版本>";
            case REMEDIATE -> "核准人工修復重驗 <驗收節點> <commit>";
            default -> "<你的說明或決定>";
        };
    }

    /**
     * 直接續跑是否真的會開始處理，而不是又撞上某個閘門停下來。
     * 只在確定會前進時才提供「繼續」按鈕 —— #1014 反覆送「續跑」卻原地踏步，
     * 正是因為沒有人知道什麼時候續跑有用、什麼時候沒用。
     */
    static boolean resumeWillProceed(FlowRun flow) {
        if (flow.snapshot == null || flow.dialogue || flow.changeTarget != null || flow.pendingPlanRevision != null)
            return false;
        Node node = flow.snapshot.definition().nodes().get(flow.node);
        // 停在發布核准（例如開 PR 失敗或證據檢查擋下）：續跑只會重新檢查證據並貼出核准按鈕，不呼叫 agent、不推送。
        // 以前這裡一律回 false，#1015 補好 GitHub token 後找不到任何能回到核准 PR 的按鈕。
        if (node != null && node.kind() == Kind.APPROVAL) return true;
        if (node == null || node.kind() != Kind.AGENT) return false;
        // 這個節點已經在本輪做出判斷（成功或受阻）時，原樣重跑只會得到同樣的結論 ——
        // 那正是 #1014 反覆「續跑」卻原地踏步的原因。只有還沒開始的節點才建議直接繼續。
        // 但 activeNode 仍是這個節點，代表它這一輪已經啟動、還沒交出報告就被系統層打斷
        // （例如系統獨立驗證失敗）；手上的結論是上一輪的，重跑會得到新的判斷。#1016 就是這樣沒有「繼續」可按。
        boolean interrupted = flow.node.equals(flow.activeNode);
        if (!interrupted && (flow.receipts.containsKey(flow.node)
                || (flow.blockedFindings != null && flow.blockedFindings.containsKey(flow.node)))) return false;
        if (node.gate() == Gate.VERIFICATION && PlanApproval.blockingReason(flow, node.plan()) != null) return false;
        if (node.requires() != null) for (String dependency : node.requires()) {
            Node needed = flow.snapshot.definition().nodes().get(dependency);
            // 前置結果無效但它是 agent 節點時，引擎會自動退回那一步重做，繼續就會前進。
            if (needed == null || (FlowGates.blockingReason(needed, flow.receipts.get(dependency), flow) != null
                    && needed.kind() != Kind.AGENT)) return false;
        }
        // 與引擎共用同一套上限判斷；只接受「不必再消耗新事件額度」的情況。
        if (!FlowLimits.overLimit(flow, node) || VerificationRecovery.permitted(flow, flow.node)) return true;
        String evidence = AutomaticHandoff.pendingEvidence(flow);
        return evidence != null && flow.automaticHandoffKeys.contains("incident:" + evidence)
                && flow.automaticHandoffHops < AutomaticHandoff.HOP_LIMIT;
    }

    /**
     * 「你自己修好了，請它重驗」要授權的驗收節點：優先取已用完次數的驗收節點，否則取第一個驗收節點。
     * 輸入視窗打開時由系統帶入，使用者不必自己抄節點與 commit。
     */
    public static String remediationNode(FlowRun flow) {
        if (!verificationAwaitingRemediation(flow)) return null;
        var nodes = flow.snapshot.definition().nodes().entrySet();
        return nodes.stream().filter(e -> e.getValue().gate() == Gate.VERIFICATION
                        && flow.repeats.getOrDefault(e.getKey(), 0) >= e.getValue().maxVisits())
                .map(java.util.Map.Entry::getKey).findFirst()
                .orElseGet(() -> nodes.stream().filter(e -> e.getValue().gate() == Gate.VERIFICATION)
                        .map(java.util.Map.Entry::getKey).findFirst().orElse(null));
    }

    /** 驗收節點已用完重試次數、工作區又有可用 HEAD 時，人工修復重驗才是有意義的選項。 */
    private static boolean verificationAwaitingRemediation(FlowRun flow) {
        if (!flow.workspaceReady || flow.snapshot == null) return false;
        return flow.snapshot.definition().nodes().entrySet().stream().anyMatch(entry -> {
            Node node = entry.getValue();
            return (node.gate() == Gate.VERIFICATION || node.gate() == Gate.REVIEW)
                    && flow.repeats.getOrDefault(entry.getKey(), 0) >= node.maxVisits();
        });
    }
}
