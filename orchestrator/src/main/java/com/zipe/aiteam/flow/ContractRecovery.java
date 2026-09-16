package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import static com.zipe.aiteam.flow.TeamDefinition.*;

/**
 * 把「被系統規則錯誤否決、但本身合格」的前置報告恢復成有效契約。
 *
 * 起因是 #1014：implementation 已經 SUCCESS、verify 通過、commit 乾淨，卻因為中途一個
 * `for ... mvn` 迴圈不符合白名單，被 ToolTrust 改判受阻。它的報告只進了 blockedFindings，
 * 沒進 receipts；而 verification 啟動前要求 implementation 的 receipt，於是每次續跑都在
 * 任何 agent 啟動前報錯，現有的人工指令也都排在那個檢查之後，沒有任何一條救得回來。
 *
 * 這裡不重跑、不偽造：只有同時滿足下列條件才恢復，而且一定要驗收負責人明確授權。
 * - 節點是目前節點的前置依賴，而且目前確實缺少它的 receipt
 * - blockedFindings 裡有它的原始報告，並且現在重新通過該節點真正的閘門
 * - 節點不是 READ_ONLY：唯讀節點的產出是主張，工具遭拒會直接動搖它；
 *   產物型節點的成果可以由 Git 與下游驗收獨立核實
 * - 產物型節點的工作區乾淨，且 HEAD 等於授權時指定的 commit
 */
final class ContractRecovery {
    private ContractRecovery() {}

    /** 回傳 null 表示可以恢復；否則說明為什麼不行。 */
    static String blockingReason(FlowRun flow, String dependency, String head, boolean clean) {
        Node current = flow.snapshot.definition().nodes().get(flow.node);
        if (current == null || current.requires() == null || !current.requires().contains(dependency))
            return "節點 " + dependency + " 不是目前節點 " + flow.node + " 的前置依賴";
        Node node = flow.snapshot.definition().nodes().get(dependency);
        if (node == null) return "快照中沒有節點 " + dependency;
        if (FlowGates.blockingReason(node, flow.receipts.get(dependency), flow) == null)
            return "節點 " + dependency + " 已經有有效的前置契約，不需要恢復";
        if (node.scope() == Scope.READ_ONLY)
            return "唯讀節點的產出是主張而非產物，工具遭拒會直接動搖其結論，不提供恢復；請重跑該節點";
        JsonNode report = flow.blockedFindings == null ? null : flow.blockedFindings.get(dependency);
        if (report == null) return "沒有節點 " + dependency + " 被否決的原始報告可以恢復；請重跑該節點";
        String gate = FlowGates.blockingReason(node, report, flow);
        if (gate != null) return "原始報告本身沒有通過節點閘門，不是被誤判：" + gate;
        if (node.workspace()) {
            if (!clean) return "工作區有未提交修改，不能綁定到指定 commit";
            if (head == null || head.isBlank()) return "無法取得工作區 HEAD";
        }
        return null;
    }

    /** 目前卡住、而且可以恢復的前置依賴；沒有就回傳 null。 */
    static String candidate(FlowRun flow, String head, boolean clean) {
        Node current = flow.snapshot.definition().nodes().get(flow.node);
        if (current == null || current.requires() == null) return null;
        for (String dependency : current.requires())
            if (blockingReason(flow, dependency, head, clean) == null) return dependency;
        return null;
    }

    static void restore(FlowRun flow, String dependency, String head) {
        Node node = flow.snapshot.definition().nodes().get(dependency);
        flow.receipts.put(dependency, flow.blockedFindings.remove(dependency));
        if (node.workspace()) flow.heads.put(dependency, head);
    }
}
