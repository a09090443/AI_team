package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.zipe.aiteam.task.*;
import static com.zipe.aiteam.flow.TeamDefinition.*;

/** 驗證的是工作契約，不是角色 ID；新增角色不用新增 case。 */
public final class FlowGates {
    private FlowGates() {}
    public static String blockingReason(Node node, JsonNode result, FlowRun flow) {
        String common = FailureReport.blockingReason("generic", result);
        if (common != null) return common;
        String issues = IssueTracker.reportReason(node, result, flow);
        if (issues != null) return issues;
        return switch (node.gate()) {
            case REPORT -> null;
            case SERVICE -> ServiceDelivery.blockingReason(result, flow);
            case REQUIREMENTS -> java.util.Set.of("question", "human").contains(result.path("route").asText()) ? null : Traceability.requirementsReason(result);
            case IMPLEMENTATION -> FailureReport.implementationIncomplete(result) ? "實作品質契約未通過或有範圍衝突" : null;
            case TEST_PLAN -> flow.snapshot.definition().version() >= 2 ? Traceability.planReason(result, flow) : QaGate.planReason(result);
            case VERIFICATION -> {
                String approval = PlanApproval.blockingReason(flow, node.plan());
                if (approval != null) yield approval;
                yield FailureReport.verificationIncomplete(result) ? "驗收狀態未通過" : flow.snapshot.definition().version() >= 2
                        ? Traceability.executionReason(node, result, flow) : QaGate.executionReason(flow.receipts.get(node.plan()), result);
            }
            case REVIEW -> ReviewGate.blockingReason(result);
        };
    }
    public static String publicationReason(FlowRun flow, String head) {
        return publicationReason(flow, head, java.util.Set.of());
    }
    /** pending：還沒執行、之後會補跑的節點（負責人「開 PR」時尚未審查），試算時不檢查。 */
    static String publicationReason(FlowRun flow, String head, java.util.Set<String> pending) {
        Node approval = flow.snapshot.definition().nodes().get(flow.node);
        if (flow.dialogue || approval == null || approval.kind() != Kind.APPROVAL) return "不是發布核准節點";
        String issues = IssueTracker.completionReason(flow);
        if (issues != null) return issues;
        if (flow.snapshot.definition().version() >= 2) {
            String invalid = Traceability.requirementsReason(Traceability.baseline(flow));
            if (invalid != null) return invalid;
            if (flow.acceptanceOwner == null || flow.acceptanceOwner.isBlank()) return "缺少真人驗收負責人";
            if (!Traceability.digest(Traceability.baseline(flow)).equals(flow.baselineRevision)) return "需求基準版本不一致";
        }
        for (String id : approval.requires()) {
            if (pending.contains(id)) continue;
            Node node = flow.snapshot.definition().nodes().get(id);
            String block = blockingReason(node, flow.receipts.get(id), flow);
            if (block != null) return id + "：" + block;
            if ((node.gate() == Gate.REVIEW || node.gate() == Gate.VERIFICATION) && !head.equals(flow.heads.get(id)))
                return "工作区版本與驗收／審查證據不一致：" + id;
        }
        return null;
    }
}
