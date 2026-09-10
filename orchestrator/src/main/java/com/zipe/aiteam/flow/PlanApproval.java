package com.zipe.aiteam.flow;

import java.time.Instant;
import java.util.Objects;

/** 只由已授權真人指令建立；不接受 agent 報告中的 approved 欄位。 */
public record PlanApproval(String plan, String planRevision, String baseline, String configuration, String user, String at) {
    public static PlanApproval approve(FlowRun flow, String plan, String digest, String user) {
        var node = flow.snapshot.definition().nodes().get(plan);
        if (!Objects.equals(user, flow.acceptanceOwner) || node == null || node.gate() != TeamDefinition.Gate.TEST_PLAN
                || flow.receipts.get(plan) == null || !Traceability.digest(flow.receipts.get(plan)).equals(digest)
                || flow.baselineRevision == null)
            throw new IllegalArgumentException("測試計畫核准者或版本不符，未建立核准收據");
        return new PlanApproval(plan, digest, flow.baselineRevision, flow.snapshot.revision(), user, Instant.now().toString());
    }
    public static String blockingReason(FlowRun flow, String plan) {
        if (!flow.snapshot.definition().project().requirePlanApproval()) return null;
        var report = flow.receipts.get(plan);
        if (report == null) return "尚無可核准的測試計畫：" + plan;
        var approval = flow.planApprovals.get(plan);
        String digest = Traceability.digest(report);
        if (approval != null && digest.equals(approval.planRevision())
                && Objects.equals(flow.baselineRevision, approval.baseline())
                && Objects.equals(flow.snapshot.revision(), approval.configuration())
                && Objects.equals(flow.acceptanceOwner, approval.user())) return null;
        return "測試計畫尚未取得本版本的真人核准；請需求負責人審閱後補充「核准測試計畫 " + plan + " " + digest + "」。系統測試結果不能代替核准。";
    }
}
