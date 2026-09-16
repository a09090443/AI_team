package com.zipe.aiteam.flow;

import static com.zipe.aiteam.flow.TeamDefinition.*;

/**
 * 防循環上限的唯一判斷。引擎擋人、停止訊息決定要不要提供按鈕，都必須呼叫這裡。
 *
 * 舊版停止訊息自己另寫一套「額度還剩幾次」的判斷，結果任務卡在需求節點時，
 * 雖然還剩一個事件額度，但那個節點根本不是可以使用額度的位置 —— 系統仍提供了
 * 「核准需求變更」按鈕，使用者按下去，下一步立刻被擋，而且沒有任何選項。
 */
final class FlowLimits {
    private FlowLimits() {}

    static boolean overLimit(FlowRun flow, Node node) {
        return flow.transitions >= flow.snapshot.definition().maxTransitions()
                || flow.visits.getOrDefault(flow.node, 0) >= node.maxVisits();
    }

    /** 下一次啟動目前節點時，能不能通過防循環檢查（不含計畫核准等其他閘門）。 */
    static boolean canStart(FlowRun flow, Node node) {
        if (!overLimit(flow, node)) return true;
        if (VerificationRecovery.permitted(flow, flow.node)) return true;
        return AutomaticHandoff.wouldClaim(flow);
    }

    /**
     * 任務在設計上已無法再自動往下：目前節點擋在上限，延長額度幫不上忙，
     * 而且流程總次數已用盡（兩種受控恢復授權在總次數用盡時一律拒絕）。
     */
    static boolean exhausted(FlowRun flow) {
        if (flow.snapshot == null || flow.dialogue) return false;
        Node node = flow.snapshot.definition().nodes().get(flow.node);
        if (node == null || node.kind() != Kind.AGENT) return false;
        if (canStart(flow, node)) return false;
        if (AutomaticHandoff.extensionWouldHelp(flow)) return false;
        return flow.transitions >= flow.snapshot.definition().maxTransitions();
    }
}
