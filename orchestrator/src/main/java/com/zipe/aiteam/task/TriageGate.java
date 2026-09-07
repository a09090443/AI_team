package com.zipe.aiteam.task;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

final class TriageGate {
    private TriageGate() {}
    static String blockingReason(JsonNode r) {
        if (r == null || !Set.of("HUMAN", "BACKEND", "QA").contains(r.path("action").asText())) return "PM 分流缺少有效 action";
        for (String field : new String[]{"summary", "reason", "instructions"}) {
            if (!r.path(field).isTextual() || r.path(field).asText().isBlank()) return "PM 分流缺少 " + field;
        }
        if (!r.path("questions").isArray() || !r.path("requires_human").isBoolean()
                || !r.path("within_existing_scope").isBoolean()) return "PM 分流缺少決策／範圍欄位";
        if ("HUMAN".equals(r.path("action").asText())) {
            if (r.path("questions").isEmpty()) return "等待人工卻沒有具體問題";
            for (JsonNode q : r.path("questions")) if (!q.isTextual() || q.asText().isBlank()) return "人工問題不得空白";
        } else if (r.path("requires_human").asBoolean() || !r.path("within_existing_scope").asBoolean()
                || !r.path("questions").isEmpty()) return "需人工決策或超出既有範圍，不可自動派工";
        return null;
    }
}
