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

    /**
     * 驗收負責人可以放行的唯一情況：其他硬性條件全過、沒有 HIGH，只因 finding 等級寫法不標準（例如 INFO）被擋。
     * 回傳那些等級不標準的 finding；不符合或根本不需要放行時回 null。
     * #1022 的審查 APPROVE、沒有 blockers，卻因一項 INFO 擋下發布，舊快照又套不到新規則。
     */
    public static java.util.List<JsonNode> waivable(JsonNode r) {
        if (r == null || !r.isObject()
                || !"VERIFIED".equals(r.path("verification_status").asText())
                || !java.util.Set.of("HIGH", "MEDIUM").contains(r.path("confidence").asText())
                || !java.util.Set.of("APPROVE", "COMMENT").contains(r.path("verdict").asText())
                || !r.path("blockers").isArray() || !r.path("blockers").isEmpty()
                || !r.path("checked").isArray() || r.path("checked").isEmpty()
                || !r.path("findings").isArray()) return null;
        java.util.List<JsonNode> nonStandard = new java.util.ArrayList<>();
        for (JsonNode f : r.path("findings")) {
            String severity = f.path("severity").asText();
            if (java.util.Set.of("MEDIUM", "LOW").contains(severity)) continue;
            // CRITICAL、BLOCKER 或看不懂的寫法可能是實質問題，不能當提醒放行。
            if (!INFORMATIONAL.contains(severity.strip().toUpperCase(java.util.Locale.ROOT))) return null;
            nonStandard.add(f);
        }
        return nonStandard.isEmpty() ? null : java.util.List.copyOf(nonStandard);
    }

    /** 明確屬於「提醒」的等級寫法；只有這些可以由負責人放行。 */
    public static final java.util.Set<String> INFORMATIONAL = java.util.Set.of("INFO", "INFORMATIONAL", "NOTE", "NIT", "NONE");
}
