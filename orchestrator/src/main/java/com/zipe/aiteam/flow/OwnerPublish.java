package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zipe.aiteam.task.ReviewGate;
import java.time.Instant;
import java.util.*;
import static com.zipe.aiteam.flow.TeamDefinition.*;

/**
 * 驗收負責人在 Slack 命令「開 PR」時，判斷能不能直接走到發布。
 *
 * 負責人可以放行的閘門失敗只有兩種，其餘證據照舊必須有效（判斷用發布核准節點同一套 FlowGates.publicationReason）：
 * 1. 審查其他條件全過、沒有 HIGH，只因 finding 等級寫成 INFO 之類的提醒而被擋（ReviewGate.waivable）。
 *    #1022 的審查 APPROVE、沒有 blockers，卻因一項 INFO 擋下，流程內沒有任何路徑能開 PR。
 * 2. 驗收其他條件全過，只剩 uncertainties（未核實事項）。有些專案的測試規範要求外部 API 金鑰、資料庫的情境一律標為
 *    「無法核實」，QaGate 又把任何未核實事項當成禁止發布，#1029 驗收 7 個情境全數 PASS 仍永遠開不了 PR。
 * 審查還沒執行（沒有收據也沒有被擋的報告）時不能跳過：回傳 pendingReview，由 FlowEngine 先跑審查，通過後再依負責人指令發布。
 * 系統獨立驗證收據不在這裡判斷：缺少時由 FlowEngine 在發布前（或審查前）重跑。
 */
public final class OwnerPublish {
    private OwnerPublish() {}

    /**
     * reason 為 null 表示可以進行。reviewNode／waived：要放行的審查提醒；verificationNode／uncertainties：要放行的未核實事項；
     * pendingReview：還沒跑、要先補跑的審查節點。
     */
    public record Result(String reason, String approvalNode, String reviewNode, List<JsonNode> waived,
                         String verificationNode, List<String> uncertainties, String pendingReview) {
        public boolean allowed() { return reason == null; }
        public boolean needsWaiver() { return reviewNode != null; }
        public boolean waivesUncertainties() { return verificationNode != null; }
        public boolean needsReview() { return pendingReview != null; }
    }

    private static Result refuse(String reason) { return new Result(reason, null, null, List.of(), null, List.of(), null); }

    public static Result evaluate(FlowRun flow, String head, boolean clean) {
        if (flow == null || flow.snapshot == null || flow.dialogue) return refuse("這不是配置式流程的工作任務");
        if (flow.successor != null) return refuse("已由任務 #" + flow.successor + " 承接");
        List<String> approvals = flow.snapshot.definition().nodes().entrySet().stream()
                .filter(e -> e.getValue().kind() == Kind.APPROVAL).map(Map.Entry::getKey).toList();
        if (approvals.size() != 1) return refuse("流程沒有唯一的發布核准節點");
        String approvalNode = approvals.getFirst();
        if (head == null) return refuse("找不到任務工作區");
        if (!clean) return refuse("工作區有還沒 commit 的修改");

        String reviewNode = null, verificationNode = null, pendingReview = null;
        List<JsonNode> waived = List.of();
        List<String> uncertainties = List.of();
        for (String id : flow.snapshot.definition().nodes().get(approvalNode).requires()) {
            Node node = flow.snapshot.definition().nodes().get(id);
            if (node == null) continue;
            if (node.gate() == Gate.VERIFICATION) {
                if (FlowGates.blockingReason(node, flow.receipts.get(id), flow) == null) continue;
                JsonNode report = flow.blockedFindings.get(id);
                List<String> items = waivableUncertainties(node, report, flow);
                if (items == null) return refuse("驗收沒有通過，而且不是可以由你放行的情況（只有驗收其他條件全過、"
                        + "只剩未核實事項才能放行）：" + firstLine(report == null ? "缺少驗收報告" : FlowGates.blockingReason(node, report, flow)));
                if (!head.equals(flow.attemptHeads.get(id)))
                    return refuse("目前的 commit 和驗收當時看到的不同，驗收結論不適用，需要重新驗收");
                verificationNode = id;
                uncertainties = items;
            } else if (node.gate() == Gate.REVIEW) {
                if (ReviewGate.blockingReason(flow.receipts.get(id)) == null) continue;
                JsonNode report = flow.blockedFindings.get(id);
                if (flow.receipts.get(id) == null && report == null) {
                    // 還沒審查過：不能跳過，先補跑。
                    if (pendingReview != null) return refuse("流程有多個尚未執行的審查節點，不能由指令補跑");
                    pendingReview = id;
                    continue;
                }
                List<JsonNode> items = ReviewGate.waivable(report);
                if (items == null) return refuse("審查沒有通過，而且不是可以由你放行的情況（只有審查已核准、沒有 HIGH、"
                        + "只因等級寫成 INFO 之類的提醒才能放行）：" + firstLine(ReviewGate.blockingReason(report)));
                if (!head.equals(flow.attemptHeads.get(id)))
                    return refuse("目前的 commit 和審查當時看到的不同，審查結論不適用，需要重新審查");
                reviewNode = id;
                waived = items;
            }
        }

        // 用發布核准節點同一套檢查試算；放行只是試算，不論結果都還原。
        String node = flow.node, approvalHead = flow.approvalHead;
        Map<String, JsonNode> receipts = new LinkedHashMap<>(flow.receipts), blocked = new LinkedHashMap<>(flow.blockedFindings);
        Map<String, String> heads = new LinkedHashMap<>(flow.heads);
        try {
            if (reviewNode != null) applyWaiver(flow, reviewNode, head, "trial");
            if (verificationNode != null) applyUncertaintyWaiver(flow, verificationNode, head, "trial");
            flow.node = approvalNode;
            String reason = FlowGates.publicationReason(flow, head, pendingReview == null ? Set.of() : Set.of(pendingReview));
            if (reason != null) return refuse(reason);
        } finally {
            flow.node = node; flow.approvalHead = approvalHead;
            flow.receipts.clear(); flow.receipts.putAll(receipts);
            flow.blockedFindings.clear(); flow.blockedFindings.putAll(blocked);
            flow.heads.clear(); flow.heads.putAll(heads);
        }
        return new Result(null, approvalNode, reviewNode, waived, verificationNode, uncertainties, pendingReview);
    }

    /** 驗收只因 uncertainties 而被擋時回傳那些項目；不符合（或根本沒有未核實事項）時回 null。 */
    static List<String> waivableUncertainties(Node node, JsonNode report, FlowRun flow) {
        if (report == null || !report.isObject() || !report.path("uncertainties").isArray() || report.path("uncertainties").isEmpty()) return null;
        ObjectNode without = report.deepCopy();
        without.putArray("uncertainties");
        if (FlowGates.blockingReason(node, without, flow) != null) return null;
        List<String> items = new ArrayList<>();
        report.path("uncertainties").forEach(u -> items.add(u.asText()));
        return items;
    }

    /** 放行後的審查 receipt：提醒類 finding 改列 LOW 並留下原等級，報告記錄由誰放行；原始報告仍在 stage 紀錄。 */
    public static void applyWaiver(FlowRun flow, String reviewNode, String head, String user) {
        ObjectNode report = flow.blockedFindings.get(reviewNode).deepCopy();
        for (JsonNode f : report.withArray("findings")) {
            String severity = f.path("severity").asText();
            if (Set.of("MEDIUM", "LOW").contains(severity)) continue;
            ((ObjectNode) f).put("waived_from", severity).put("severity", "LOW");
        }
        report.putObject("owner_waiver").put("by", user).put("at", Instant.now().toString())
                .put("reason", "驗收負責人以「開 PR」放行等級寫成提醒的審查項目");
        flow.receipts.put(reviewNode, report);
        flow.heads.put(reviewNode, head);
        flow.blockedFindings.remove(reviewNode);
    }

    /** 放行後的驗收 receipt：未核實事項移到 waived_uncertainties，報告記錄由誰放行；原始報告仍在 stage 紀錄。 */
    public static void applyUncertaintyWaiver(FlowRun flow, String verificationNode, String head, String user) {
        ObjectNode report = flow.blockedFindings.get(verificationNode).deepCopy();
        report.set("waived_uncertainties", report.path("uncertainties").deepCopy());
        report.putArray("uncertainties");
        report.putObject("owner_waiver").put("by", user).put("at", Instant.now().toString())
                .put("reason", "驗收負責人以「開 PR」放行未核實事項");
        flow.receipts.put(verificationNode, report);
        flow.heads.put(verificationNode, head);
        flow.blockedFindings.remove(verificationNode);
    }

    /** 已放行的審查項目，給 Slack 與 PR 說明列出；沒有放行時為空。 */
    public static List<String> waivedItems(FlowRun flow) {
        List<String> lines = new ArrayList<>();
        flow.snapshot.definition().nodes().forEach((id, node) -> {
            JsonNode receipt = flow.receipts.get(id);
            if (node.gate() != Gate.REVIEW || receipt == null || !receipt.has("owner_waiver")) return;
            String by = receipt.path("owner_waiver").path("by").asText();
            for (JsonNode f : receipt.path("findings"))
                if (f.has("waived_from"))
                    lines.add("原等級 " + f.path("waived_from").asText() + "：" + f.path("detail").asText().replace("\n", " ")
                            + "（" + by + " 放行）");
        });
        return lines;
    }

    /** 已放行的未核實事項，給 Slack 與 PR 說明列出；沒有放行時為空。 */
    public static List<String> waivedUncertainties(FlowRun flow) {
        List<String> lines = new ArrayList<>();
        flow.snapshot.definition().nodes().forEach((id, node) -> {
            JsonNode receipt = flow.receipts.get(id);
            if (node.gate() != Gate.VERIFICATION || receipt == null || !receipt.has("owner_waiver")) return;
            String by = receipt.path("owner_waiver").path("by").asText();
            for (JsonNode u : receipt.path("waived_uncertainties"))
                lines.add(u.asText().replace("\n", " ") + "（" + by + " 放行）");
        });
        return lines;
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        int end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end);
    }
}
