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
 */
public final class HumanOptions {
    private HumanOptions() {}

    public static String render(Task task, FlowRun flow, boolean canSupplement, boolean briefReports) {
        List<String> options = new ArrayList<>();
        String id = task.id();

        if (flow.changeTarget != null) options.add("需求要改：補充 #" + id + " 核准需求變更 <具體變更內容>"
                + "（只有需求負責人可核准；核准後會回到需求節點重做，既有驗收收據失效）");

        Node current = flow.snapshot == null ? null : flow.snapshot.definition().nodes().get(flow.node);
        String planNode = current == null ? null : current.plan();
        if (planNode != null && PlanApproval.blockingReason(flow, planNode) != null)
            options.add("計畫要核准：補充 #" + id + " 核准測試計畫 " + planNode + " <計畫版本>"
                    + "（版本在上方計畫卡；核准計畫不等於核准 PR）");

        if (flow.pendingPlanRevision != null)
            options.add("計畫已退回重訂：待 " + flow.pendingPlanRevision + " 產出新版後仍須重新核准");

        if (verificationAwaitingRemediation(flow))
            options.add("你自己修好了：補充 #" + id + " 核准人工修復重驗 <驗收節點> <commit>"
                    + "（工作區必須乾淨且 commit 等於目前 HEAD；之後仍須重新核准測試計畫）");

        if (canSupplement) options.add("其他情況一律用：補充 #" + id + " <你的說明或決定>"
                + "。內容會進入流程脈絡並交由分流重新判斷 ——"
                + "若你知道還有未修的缺陷，直接把缺陷寫進來，系統才派得出去；只寫「續跑」不會改變上一輪的判斷依據。");

        if (briefReports) options.add("想先看細節：補充 #" + id + " 檢視完整報告 [節點]");

        if (options.isEmpty()) return "";
        StringBuilder out = new StringBuilder("\n\n你現在可以做的事：");
        for (String option : options) out.append("\n・").append(option);
        out.append("\n停止不等於完成。只有走到發布核准節點並由你核准，任務才算結束；目前尚未到達。");
        return out.toString();
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
