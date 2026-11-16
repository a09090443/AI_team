package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.zipe.aiteam.task.*;
import static com.zipe.aiteam.flow.TeamDefinition.*;

/** 驗證的是工作契約，不是角色 ID；新增角色不用新增 case。 */
public final class FlowGates {
    private FlowGates() {}
    public static String blockingReason(Node node, JsonNode result, FlowRun flow) { return reason(node, result, flow, false); }
    /** 剛執行完的節點（flow.node）的判定：分流已選定交回路線時依它派工，見 dispatchesBlockedWork。 */
    static String currentNodeReason(Node node, JsonNode result, FlowRun flow) { return reason(node, result, flow, true); }
    private static String reason(Node node, JsonNode result, FlowRun flow, boolean current) {
        String common = FailureReport.blockingReason("generic", result);
        if (common != null && current && dispatchesBlockedWork(node, result, flow)) common = null;
        if (common != null) return common;
        String issues = IssueTracker.reportReason(node, result, flow);
        if (issues != null) return issues;
        return switch (node.gate()) {
            case REPORT -> null;
            case SERVICE -> ServiceDelivery.blockingReason(result, flow, node);
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
    /**
     * 分流節點（其他節點受阻時的去處）已選定交回某個非真人路線，只是把「被分流的工作仍受阻」寫成 BLOCKED：
     * 分流本身已完成，依它的 route 派工。#1056 service_triage 寫 route=service、摘要說不需使用者，
     * 卻因 BLOCKED 被一律交真人。契約要求分流填 COMPLETED 並把被分流工作的受阻寫進 failure_report，
     * 共用判定會把「COMPLETED＋failure_report」也當受阻（#1057），所以兩種狀態都依 route 派工。
     * 格式不完整、要真人決定（human_decision）或選 human 時照舊停下；次數與總轉換上限不受影響。
     */
    static boolean dispatchesBlockedWork(Node node, JsonNode result, FlowRun flow) {
        if (result == null || !result.isObject()) return false;
        String route = result.path("route").asText("");
        if (node.gate() != Gate.REPORT || node.routes() == null || route.isBlank() || "human".equals(route)) return false;
        String target = node.routes().get(route);
        if (target == null || "human".equals(target) || result.path("human_decision").isObject()) return false;
        boolean triage = flow.snapshot.definition().nodes().values().stream().anyMatch(n -> flow.node.equals(n.blocked()));
        return triage && result.path("failure_report").isObject() && FailureReport.wellFormed(result);
    }
    /**
     * 建議性報告（REPORT，例如設計）在本流程從未執行、也沒有結果或受阻報告：是需求節點選了跳過它的路線（精簡路線），
     * 不是缺漏，前置檢查不必退回補做。#1002 一行文件修改也被補跑設計。執行過但無效時照舊重做；
     * 需求、測試計畫、實作、驗收、審查等契約階段永遠不能略過。
     */
    static boolean bypassed(FlowRun flow, String id) {
        Node node = flow.snapshot.definition().nodes().get(id);
        return node != null && node.kind() == Kind.AGENT && node.gate() == Gate.REPORT
                && !flow.visits.containsKey(id) && flow.receipts.get(id) == null
                && (flow.blockedFindings == null || !flow.blockedFindings.containsKey(id));
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
            if (pending.contains(id) || bypassed(flow, id)) continue;
            Node node = flow.snapshot.definition().nodes().get(id);
            String block = blockingReason(node, flow.receipts.get(id), flow);
            if (block != null) return id + "：" + block;
            if ((node.gate() == Gate.REVIEW || node.gate() == Gate.VERIFICATION) && !head.equals(flow.heads.get(id)))
                return "工作区版本與驗收／審查證據不一致：" + id;
            if (node.gate() == Gate.VERIFICATION) {
                String deploy = IssueTracker.deployAfter(flow, id);
                Node deployment = deploy == null ? null : flow.snapshot.definition().nodes().get(deploy);
                if (deployment != null && !IssueTracker.deploymentItems(flow, deployment).isEmpty()
                        && (!head.equals(flow.heads.get(deploy)) || blockingReason(deployment, flow.receipts.get(deploy), flow) != null))
                    return "缺少目前 commit 的延後驗收部署證據：" + deploy;
            }
        }
        return null;
    }
}
