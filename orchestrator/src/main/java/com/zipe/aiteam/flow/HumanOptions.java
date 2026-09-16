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
 * 需要自由填寫內容的（補充說明、需求變更）沒有 command，只能用打字的。
 */
public final class HumanOptions {
    private HumanOptions() {}

    /** button 為 true 表示這個選項可以用一顆按鈕完成。 */
    public record Option(String kind, String label, String hint, String command) {
        public boolean button() { return command != null; }
    }

    /** 工作區現況；head 為 null 表示沒有工作區或取不到。 */
    public record Workspace(String head, boolean clean) {
        public static final Workspace NONE = new Workspace(null, true);
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

    public static List<Option> options(Task task, FlowRun flow, boolean canSupplement,
                                       boolean briefReports, String extensionToken, Workspace workspace) {
        List<Option> options = new ArrayList<>();
        Workspace ws = workspace == null ? Workspace.NONE : workspace;

        // 已由新任務承接：這裡不再有任何動作。
        if (flow.successor != null) return List.of();

        // 自動處理次數全部用完時，任何選項都不會讓任務往下跑；只提供承接成新任務。
        if (exhausted(flow)) {
            options.add(new Option(SUCCESSOR, "承接成新任務",
                    "開一個新任務，從這個任務的最新 commit 接著做，並帶入已做過的決定；次數重新計算，"
                            + "需求、測試計畫與 PR 仍要重新核准。", SUCCESSOR_COMMAND));
            if (briefReports) options.add(new Option(REPORT, "看完整報告",
                    "只是把完整內容叫出來看，不會改變任務狀態。", "檢視完整報告"));
            return List.copyOf(options);
        }

        // agent 提出的選擇題放最前面：這是對方停下來最主要要回答的事。
        options.addAll(decisionOptions(flow));

        String restorable = flow.snapshot == null ? null : ContractRecovery.candidate(flow, ws.head(), ws.clean());
        if (restorable != null)
            options.add(new Option(RESTORE, "恢復「" + restorable + "」的結果",
                    "那份結果先前被系統規則否決，但重新檢查已經通過。恢復後不會重跑、不會重設次數。",
                    "核准恢復前置契約 " + restorable + (ws.head() == null ? "" : " " + ws.head())));

        boolean resume = restorable == null && resumeWillProceed(flow);
        if (resume)
            options.add(new Option(RESUME, "繼續執行",
                    "現在直接繼續就會開始處理，不需要其他授權，也不會多用掉一次自動處理的機會。",
                    "續跑"));

        // 直接續跑就能前進時，不再推延長額度 —— 那會讓人多做一步、白用一次授權。
        if (!resume && extensionToken != null && !extensionToken.isBlank())
            options.add(new Option(EXTEND, "再給系統兩次機會",
                    "系統自己試過的次數已經用完。按下去只增加 " + AutomaticHandoff.EXTENSION_SIZE
                            + " 次，不會重設已經累積的次數，也不等於核准 PR。",
                    "核准延長自動交接 " + extensionToken));

        if (flow.changeTarget != null)
            options.add(new Option(REQUIREMENT, "需求要改",
                    "只有需求負責人可以核准；核准後會回到需求節點重做，既有驗收證據會失效。",
                    null));

        Node current = flow.snapshot == null ? null : flow.snapshot.definition().nodes().get(flow.node);
        String planNode = current == null ? null : current.plan();
        if (planNode != null && PlanApproval.blockingReason(flow, planNode) != null)
            options.add(new Option(PLAN, "核准測試計畫",
                    "計畫卡會另外貼出來，上面有專用按鈕。核准計畫不等於核准 PR。", null));

        if (verificationAwaitingRemediation(flow))
            options.add(new Option(REMEDIATE, "你自己修好了，請它重驗",
                    "工作區必須乾淨，而且 commit 要等於目前的 HEAD；重驗之後仍須重新核准測試計畫。",
                    null));

        if (canSupplement) options.add(new Option(SUPPLEMENT, "告訴它還缺什麼",
                "你寫的內容會進入流程脈絡，交由分流重新判斷。"
                        + "如果你知道還有沒修好的問題，直接把問題寫進來，系統才派得出去；"
                        + "只寫「續跑」不會改變上一輪的判斷依據。", null));

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
    static List<Option> decisionOptions(FlowRun flow) {
        com.fasterxml.jackson.databind.JsonNode report = latestReport(flow);
        if (!com.zipe.aiteam.slack.SlackReport.hasDecision(report)) return List.of();
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
            result.add(approvesChange
                    ? new Option(DECISION, "核准：" + label, consequence, "核准需求變更 " + choice)
                    : new Option(DECISION, label, consequence, "負責人決定：" + choice));
        }
        return result;
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
        if (flow.successor != null)
            return "\n\n這個任務已由 *任務 #" + flow.successor + "* 承接，之後請到頻道裡「任務 #" + flow.successor
                    + "」的討論串操作；這裡不會再有任何動作。";
        if (exhausted(flow)) return exhaustedNotice(task, flow, workspace, briefReports);
        List<Option> options = options(task, flow, canSupplement, briefReports, extensionToken, workspace);
        if (options.isEmpty()) return "";
        StringBuilder out = new StringBuilder("\n\n你現在可以做的事：");
        List<String> decisions = options.stream().filter(o -> DECISION.equals(o.kind())).map(Option::label).toList();
        if (!decisions.isEmpty())
            out.append("\n・直接選一個：").append(String.join("／", decisions))
               .append(flow.changeTarget != null
                       ? "（按下去就是核准需求變更，會回到需求階段重做，之後的測試計畫仍要你再核准；都不合適就打字說明）"
                       : "（按下方按鈕；都不合適就用下面的方式打字說明）");
        for (Option option : options) {
            if (DECISION.equals(option.kind())) continue;
            out.append("\n・").append(option.label()).append("：").append(option.hint());
            if (option.button()) out.append("\n　按下方按鈕，或輸入：補充 #").append(task.id())
                    .append(' ').append(option.command());
            else out.append("\n　輸入：補充 #").append(task.id()).append(' ').append(commandHint(option.kind()));
        }
        out.append("\n停止不等於完成。只有走到發布核准並由你核准，任務才算結束；目前尚未到達。");
        return out.toString();
    }

    public static String render(Task task, FlowRun flow, boolean canSupplement,
                                boolean briefReports, String extensionToken) {
        return render(task, flow, canSupplement, briefReports, extensionToken, Workspace.NONE);
    }

    private static String exhaustedNotice(Task task, FlowRun flow, Workspace workspace, boolean briefReports) {
        Workspace ws = workspace == null ? Workspace.NONE : workspace;
        StringBuilder out = new StringBuilder("\n\n*這個任務已經無法再自動往下跑*\n")
                .append("整個任務已經換手 ").append(flow.transitions)
                .append(" 次（上限 ").append(flow.snapshot.definition().maxTransitions()).append("），")
                .append("而目前停住的這一步，沒有任何可用的延長或恢復授權能讓它通過。")
                .append("\n這是防止任務無限循環的保護，不是故障。在這個任務裡不論按什麼、補充什麼，")
                .append("下一步都會在開始前被擋下。")
                .append("\n\n*目前的成果都還在*")
                .append(ws.head() == null ? "\n任務工作區保留原樣。"
                        : "\n任務工作區保留原樣，最新 commit=" + ws.head() + (ws.clean() ? "（已全部提交）" : "（有未提交的修改，承接前請先處理）"))
                .append("\n\n*怎麼繼續*")
                .append("\n按「承接成新任務」：開一個新任務，工作區從上面這個 commit 接著做，")
                .append("並帶入這個任務的交接紀錄與你做過的決定（包含已核准的需求變更）。次數重新計算；")
                .append("需求、測試計畫與 PR 仍會請你重新核准。新任務會在頻道開新的討論串。")
                .append("\n或輸入：補充 #").append(task.id()).append(' ').append(SUCCESSOR_COMMAND);
        if (briefReports) out.append("\n想先看細節：補充 #").append(task.id()).append(" 檢視完整報告");
        return out.toString();
    }

    private static String commandHint(String kind) {
        return switch (kind) {
            case REQUIREMENT -> "核准需求變更 <具體變更內容>";
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
        if (node == null || node.kind() != Kind.AGENT) return false;
        // 這個節點已經在本輪做出判斷（成功或受阻）時，原樣重跑只會得到同樣的結論 ——
        // 那正是 #1014 反覆「續跑」卻原地踏步的原因。只有還沒開始的節點才建議直接繼續。
        if (flow.receipts.containsKey(flow.node)
                || (flow.blockedFindings != null && flow.blockedFindings.containsKey(flow.node))) return false;
        if (node.gate() == Gate.VERIFICATION && PlanApproval.blockingReason(flow, node.plan()) != null) return false;
        if (node.requires() != null) for (String dependency : node.requires()) {
            Node needed = flow.snapshot.definition().nodes().get(dependency);
            if (needed == null || FlowGates.blockingReason(needed, flow.receipts.get(dependency), flow) != null) return false;
        }
        // 與引擎共用同一套上限判斷；只接受「不必再消耗新事件額度」的情況。
        if (!FlowLimits.overLimit(flow, node) || VerificationRecovery.permitted(flow, flow.node)) return true;
        String evidence = AutomaticHandoff.pendingEvidence(flow);
        return evidence != null && flow.automaticHandoffKeys.contains("incident:" + evidence)
                && flow.automaticHandoffHops < AutomaticHandoff.HOP_LIMIT;
    }

    /** 驗收節點已用完重試次數、工作區又有可用 HEAD 時，人工修復重驗才是有意義的選項。 */
    private static boolean verificationAwaitingRemediation(FlowRun flow) {
        if (!flow.workspaceReady || flow.snapshot == null) return false;
        return flow.snapshot.definition().nodes().entrySet().stream().anyMatch(entry -> {
            Node node = entry.getValue();
            return (node.gate() == Gate.VERIFICATION || node.gate() == Gate.REVIEW)
                    && flow.visits.getOrDefault(entry.getKey(), 0) >= node.maxVisits();
        });
    }
}
