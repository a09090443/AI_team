package com.zipe.aiteam.task;

import com.fasterxml.jackson.databind.JsonNode;

/** 發布前的硬性條件；舊報告缺少核實狀態也必須重新 review。 */
public final class ReviewGate {
    private ReviewGate() {}

    public static String blockingReason(JsonNode r) {
        if (r == null || !r.isObject()) return "缺少有效的 Code Review 報告，需重新審查。";
        String reason = null;
        if (!"VERIFIED".equals(r.path("verification_status").asText())) {
            reason = "Code Reviewer 尚未完成獨立核實";
        } else if (!java.util.Set.of("HIGH", "MEDIUM").contains(r.path("confidence").asText())) {
            reason = "Code Reviewer 核實把握度不足";
        } else if (!java.util.Set.of("APPROVE", "COMMENT").contains(r.path("verdict").asText())) {
            reason = "Code Reviewer 要求修正或未提供有效結論";
        } else if (!r.path("blockers").isArray() || !r.path("blockers").isEmpty()) {
            reason = "Code Review 有未解除的核實阻礙";
        } else if (!r.path("checked").isArray() || r.path("checked").isEmpty()
                || !r.path("findings").isArray()) {
            reason = "Code Review 缺少檢查紀錄或 findings";
        } else {
            for (JsonNode f : r.path("findings")) {
                if (!java.util.Set.of("MEDIUM", "LOW").contains(f.path("severity").asText())) {
                    reason = "Code Review 有 HIGH 或無法判定等級的問題";
                    break;
                }
            }
        }
        if (reason == null) return null;
        return reason + "。\n" + r.path("summary").asText("")
                + "\n核實阻礙：" + r.path("blockers").toString();
    }
}
