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

    public static List<Option> options(Task task, FlowRun flow, boolean canSupplement,
                                       boolean briefReports, String extensionToken, Workspace workspace) {
        List<Option> options = new ArrayList<>();
        Workspace ws = workspace == null ? Workspace.NONE : workspace;

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

    /** 相容舊呼叫端：沒有工作區資訊時視為沒有工作區。 */
    public static List<Option> options(Task task, FlowRun flow, boolean canSupplement,
                                       boolean briefReports, String extensionToken) {
        return options(task, flow, canSupplement, briefReports, extensionToken, Workspace.NONE);
    }

    /** 純文字版本，給沒有按鈕的情境（或按鈕送不出去時）仍然看得到指令。 */
    public static String render(Task task, FlowRun flow, boolean canSupplement,
                                boolean briefReports, String extensionToken, Workspace workspace) {
        List<Option> options = options(task, flow, canSupplement, briefReports, extensionToken, workspace);
        if (options.isEmpty()) return "";
        StringBuilder out = new StringBuilder("\n\n你現在可以做的事：");
        for (Option option : options) {
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
        boolean overLimit = flow.transitions >= flow.snapshot.definition().maxTransitions()
                || flow.visits.getOrDefault(flow.node, 0) >= node.maxVisits();
        if (!overLimit || VerificationRecovery.permitted(flow, flow.node)) return true;
        // 同一受阻事件已經登記過，續跑只會用掉一站轉手，不需要新的事件額度。
        return flow.automaticHandoff != null
                && flow.automaticHandoffKeys.contains("incident:" + flow.automaticHandoff)
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
