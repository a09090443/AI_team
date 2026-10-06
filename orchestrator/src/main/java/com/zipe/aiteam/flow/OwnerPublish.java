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
 * 唯一能由負責人放行的閘門失敗：審查其他條件全過、沒有 HIGH，只因 finding 等級寫成 INFO 之類的提醒而被擋
 * （ReviewGate.waivable）。其餘證據照舊必須有效，判斷用的就是發布核准節點同一套 FlowGates.publicationReason。
 * #1022 的審查 APPROVE、沒有 blockers，卻因一項 INFO 擋下，舊快照套不到新規則，流程內沒有任何路徑能開 PR。
 * 系統獨立驗證收據不在這裡判斷：缺少時由 FlowEngine 在發布前重跑。
 */
public final class OwnerPublish {
    private OwnerPublish() {}

    /** reason 為 null 表示可以開；reviewNode／waived 只有需要放行時才有值。 */
    public record Result(String reason, String approvalNode, String reviewNode, List<JsonNode> waived) {
        public boolean allowed() { return reason == null; }
        public boolean needsWaiver() { return reviewNode != null; }
    }

    private static Result refuse(String reason) { return new Result(reason, null, null, List.of()); }

    public static Result evaluate(FlowRun flow, String head, boolean clean) {
        if (flow == null || flow.snapshot == null || flow.dialogue) return refuse("這不是配置式流程的工作任務");
        if (flow.successor != null) return refuse("已由任務 #" + flow.successor + " 承接");
        List<String> approvals = flow.snapshot.definition().nodes().entrySet().stream()
                .filter(e -> e.getValue().kind() == Kind.APPROVAL).map(Map.Entry::getKey).toList();
        if (approvals.size() != 1) return refuse("流程沒有唯一的發布核准節點");
        String approvalNode = approvals.getFirst();
        if (head == null) return refuse("找不到任務工作區");
        if (!clean) return refuse("工作區有還沒 commit 的修改");

        String reviewNode = null;
        List<JsonNode> waived = List.of();
        for (String id : flow.snapshot.definition().nodes().get(approvalNode).requires()) {
            Node node = flow.snapshot.definition().nodes().get(id);
            if (node == null || node.gate() != Gate.REVIEW) continue;
            if (ReviewGate.blockingReason(flow.receipts.get(id)) == null) continue;
            JsonNode report = flow.blockedFindings.get(id);
            List<JsonNode> items = ReviewGate.waivable(report);
            if (items == null) return refuse("審查沒有通過，而且不是可以由你放行的情況（只有審查已核准、沒有 HIGH、"
                    + "只因等級寫成 INFO 之類的提醒才能放行）：" + firstLine(ReviewGate.blockingReason(report)));
            if (!head.equals(flow.attemptHeads.get(id)))
                return refuse("目前的 commit 和審查當時看到的不同，審查結論不適用，需要重新審查");
            reviewNode = id;
            waived = items;
        }

        // 用發布核准節點同一套檢查試算；放行只是試算，不論結果都還原。
        String node = flow.node, approvalHead = flow.approvalHead;
        JsonNode receipt = reviewNode == null ? null : flow.receipts.get(reviewNode);
        String reviewHead = reviewNode == null ? null : flow.heads.get(reviewNode);
        JsonNode blocked = reviewNode == null ? null : flow.blockedFindings.get(reviewNode);
        try {
            if (reviewNode != null) applyWaiver(flow, reviewNode, head, "trial");
            flow.node = approvalNode;
            String reason = FlowGates.publicationReason(flow, head);
            if (reason != null) return refuse(reason);
        } finally {
            flow.node = node; flow.approvalHead = approvalHead;
            if (reviewNode != null) {
                restore(flow.receipts, reviewNode, receipt);
                restore(flow.heads, reviewNode, reviewHead);
                restore(flow.blockedFindings, reviewNode, blocked);
            }
        }
        return new Result(null, approvalNode, reviewNode, waived);
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

    /** 已放行的項目，給 Slack 與 PR 說明列出；沒有放行時為空。 */
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

    private static <T> void restore(Map<String, T> map, String key, T value) {
        if (value == null) map.remove(key); else map.put(key, value);
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        int end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end);
    }
}
