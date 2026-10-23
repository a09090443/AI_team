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

    /**
     * 節點層級的上限只看「輸入完全相同的連續重跑」；輸入變了代表有新的依據，不算原地打轉。
     * 整輪 maxTransitions 仍是硬上限，只有負責人裁定後往 PR 核准走的有界步數例外。
     */
    static boolean overLimit(FlowRun flow, Node node) {
        return transitionsExhausted(flow)
                || flow.repeats.getOrDefault(flow.node, 0) >= node.maxVisits();
    }

    /**
     * 整輪換手已用完；負責人裁定待決問題後沿 next 往 PR 核准走的固定步數（{@link OwnerIssueDecision}），
     * 以及已開 PR 衝突同步每輪的固定額度（{@link PullRequestSync}）除外。
     */
    static boolean transitionsExhausted(FlowRun flow) {
        return flow.transitions >= flow.snapshot.definition().maxTransitions() && !OwnerIssueDecision.forwardAllowed(flow)
                && !PullRequestSync.forwardAllowed(flow);
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
        return transitionsExhausted(flow);
    }
}
