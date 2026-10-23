package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.zipe.aiteam.flow.TeamDefinition.*;

/**
 * 流程重走時沿用前次仍然有效的設計、計畫與實作結果，不重新呼叫 agent（2026-10-23，#1053～#1055）。
 *
 * #1055 每次真人核准需求變更或分流退回計畫，都依序重跑設計（唯讀「確認沒有變化」）、計畫與實作；
 * 實作的 HEAD 一直沒變，builder_codex 加 builder 每輪仍花約 15 分鐘「核對」。#1053～#1055 合計需求 13 次、設計 9 次、計畫 11 次、實作 19 次。
 *
 * 收據被清掉時（需求重訂、上游重跑使下游失效）先存到 {@link FlowRun#priorReceipts}，節點要啟動時判斷能否原樣沿用：
 * - 只看配置 entry 沿 next 走到的主線節點，分流等 REPORT 節點不沿用（它負責判斷路線）；
 * - 配置版本、工作區 HEAD 相同且乾淨，前置結果（需求以外）與當時完全相同，報告重新通過節點閘門；
 * - 設計與實作：需求基準相同，或只有刪減（沒有新增或修改需求、驗收項目）；沒有新增待處理問題；
 * - 實作另外要求：目前 HEAD 上沒有任何 REPAIR 問題被驗收或審查判為 OPEN；
 * - 計畫：需求基準完全相同（計畫引用驗收項目 ID，有刪減就要重訂）。
 * 有疑慮一律照常執行。沿用後仍要經過系統獨立驗證、驗收與審查，實作其實需要修改時會在那裡被抓到並直接退回實作。
 */
final class ReceiptReuse {
    private ReceiptReuse() {}
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 收據被清掉前呼叫：保存報告與當時的 HEAD、需求基準、配置、待處理問題。 */
    static void retire(FlowRun flow, String nodeId) {
        JsonNode report = flow.receipts.get(nodeId);
        if (report == null || flow.snapshot == null) return;
        ObjectNode prior = JSON.createObjectNode();
        prior.set("report", report.deepCopy());
        prior.put("head", flow.heads.get(nodeId));
        prior.put("baseline", flow.baselineRevision);
        prior.put("configuration", flow.snapshot.revision());
        var issues = prior.putArray("actionable_issues");
        flow.issueInventory.forEach((id, item) -> {
            if (Set.of("REPAIR", "DECISION").contains(item.path("scope").asText())) issues.add(id);
        });
        var inputs = prior.putObject("inputs");
        Node node = flow.snapshot.definition().nodes().get(nodeId);
        if (node != null && node.requires() != null) for (String required : node.requires()) {
            JsonNode input = flow.receipts.get(required);
            inputs.put(required, input == null ? "" : Traceability.digest(input));
        }
        flow.priorReceipts.put(nodeId, prior);
    }

    /** 前置結果與當時相同；需求節點另以「相同或只刪減」判斷，因為基準重訂後摘要必然不同。 */
    private static boolean sameInputs(FlowRun flow, Node node, JsonNode prior) {
        if (node.requires() == null) return true;
        for (String required : node.requires()) {
            Node dependency = flow.snapshot.definition().nodes().get(required);
            if (dependency != null && dependency.gate() == Gate.REQUIREMENTS) continue;
            JsonNode current = flow.receipts.get(required);
            if (current == null || !Traceability.digest(current).equals(prior.path("inputs").path(required).asText())) return false;
        }
        return true;
    }

    /** 可以沿用時回傳要恢復的報告；不行回 null。 */
    static JsonNode reusable(FlowRun flow, String nodeId, Node node, String head, boolean clean) {
        if (flow.snapshot == null || flow.dialogue || flow.changeTarget != null || node.kind() != Kind.AGENT) return null;
        if (!mainLine(flow.snapshot.definition()).contains(nodeId)) return null;
        JsonNode prior = flow.priorReceipts.get(nodeId);
        if (prior == null || !flow.snapshot.revision().equals(prior.path("configuration").asText())) return null;
        if (flow.baselineRevision == null || head == null || !clean) return null;
        // 只有在工作區執行的節點會讀到程式碼；它們的結果綁定當時的 HEAD。
        if (node.workspace() && !head.equals(prior.path("head").asText(null))) return null;
        if (!sameInputs(flow, node, prior)) return null;
        boolean sameBaseline = flow.baselineRevision.equals(prior.path("baseline").asText(null));
        switch (node.gate()) {
            case REPORT -> {
                if (node.scope() != Scope.READ_ONLY || !(sameBaseline || removalOnly(flow, prior))) return null;
                if (newActionableIssues(flow, prior)) return null;
            }
            case TEST_PLAN -> { if (!sameBaseline || newActionableIssues(flow, prior)) return null; }
            case IMPLEMENTATION -> {
                if (!(sameBaseline || removalOnly(flow, prior)) || newActionableIssues(flow, prior)) return null;
                if (repairOpenAt(flow, head)) return null;
            }
            default -> { return null; }
        }
        JsonNode report = prior.path("report");
        return FlowGates.blockingReason(node, report, flow) == null ? report : null;
    }

    /** 恢復收據，回傳下一個節點（與正常通過時同一套路由）。 */
    static String restore(FlowRun flow, String nodeId, Node node, JsonNode report, String head) {
        flow.receipts.put(nodeId, report.deepCopy());
        flow.blockedFindings.remove(nodeId);
        if (node.workspace()) flow.heads.put(nodeId, head);
        flow.priorReceipts.remove(nodeId);
        String route = report.path("route").asText("");
        if (!route.isBlank() && node.routes() != null && node.routes().containsKey(route)) return node.routes().get(route);
        return node.next();
    }

    /** 配置 entry 沿 next 走到的節點；分流、服務等只由 route 進入的節點不在其中。 */
    static Set<String> mainLine(TeamDefinition definition) {
        Set<String> line = new LinkedHashSet<>();
        for (String id = definition.entry(); id != null && line.add(id); ) {
            Node node = definition.nodes().get(id);
            if (node == null) break;
            id = node.next();
        }
        return line;
    }

    /** 目前需求基準相對前次只刪減：每條需求與驗收項目都在前次基準裡且內容相同。 */
    static boolean removalOnly(FlowRun flow, JsonNode prior) {
        JsonNode current = Traceability.baseline(flow);
        String priorDigest = prior.path("baseline").asText(null);
        if (current == null || priorDigest == null) return false;
        JsonNode previous = flow.baselineHistory.stream()
                .filter(b -> priorDigest.equals(Traceability.digest(b))).reduce((a, b) -> b).orElse(null);
        if (previous == null) return false;
        Map<String, JsonNode> before = new HashMap<>();
        previous.path("requirements").forEach(r -> before.put(r.path("id").asText(), r));
        for (JsonNode requirement : current.path("requirements")) {
            JsonNode old = before.get(requirement.path("id").asText());
            if (old == null) return false;
            for (String field : List.of("kind", "description", "acceptance", "source"))
                if (!old.path(field).equals(requirement.path(field))) return false;
            Map<String, JsonNode> items = new HashMap<>();
            old.path("acceptance_items").forEach(i -> items.put(i.path("id").asText(), i));
            for (JsonNode item : requirement.path("acceptance_items"))
                if (!item.equals(items.get(item.path("id").asText()))) return false;
        }
        return true;
    }

    private static boolean newActionableIssues(FlowRun flow, JsonNode prior) {
        Set<String> before = new HashSet<>();
        prior.path("actionable_issues").forEach(id -> before.add(id.asText()));
        return flow.issueInventory.entrySet().stream()
                .anyMatch(e -> Set.of("REPAIR", "DECISION").contains(e.getValue().path("scope").asText()) && !before.contains(e.getKey()));
    }

    /** 驗收或審查在這個 commit 判定某個 REPAIR 問題仍未解決：程式要改，實作不能沿用。 */
    private static boolean repairOpenAt(FlowRun flow, String head) {
        for (var entry : flow.issueInventory.entrySet()) {
            if (!"REPAIR".equals(entry.getValue().path("scope").asText())) continue;
            JsonNode evidence = flow.issueEvidence.get(entry.getKey());
            if (evidence != null && head.equals(evidence.path("head").asText()) && !"PASS".equals(evidence.path("status").asText()))
                return true;
        }
        for (var entry : flow.blockedFindings.entrySet()) {
            Node node = flow.snapshot.definition().nodes().get(entry.getKey());
            if (node == null || (node.gate() != Gate.VERIFICATION && node.gate() != Gate.REVIEW)) continue;
            if (!head.equals(flow.attemptHeads.get(entry.getKey()))) continue;
            for (JsonNode check : entry.getValue().path("issue_checks")) {
                JsonNode item = flow.issueInventory.get(check.path("id").asText());
                if (item != null && "REPAIR".equals(item.path("scope").asText()) && !"PASS".equals(check.path("status").asText()))
                    return true;
            }
        }
        return false;
    }
}
