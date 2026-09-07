package com.zipe.aiteam.task;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;

/** 核對情境完整性；證據內容仍需 QA 執行與 Reviewer 獨立查核。 */
public final class QaGate {
    private QaGate() {}
    public static String planReason(JsonNode plan) {
        if (plan == null || !"READY".equals(plan.path("status").asText())) return "QA 測試計畫缺少或尚未就緒，須先完成規劃。";
        JsonNode scenarios = plan.path("scenarios");
        if (!scenarios.isArray() || scenarios.isEmpty()) return "QA 測試計畫沒有情境。";
        Set<String> ids = new HashSet<>();
        for (JsonNode s : scenarios) {
            for (String field : new String[]{"id", "criterion", "preconditions", "test_data", "method", "steps", "expected_result", "environment"}) {
                if (!s.path(field).isTextual() || s.path(field).asText().isBlank()) return "QA 情境缺少非空欄位：" + field;
            }
            if (!ids.add(s.path("id").asText())) return "QA 情境 ID 重複。";
        }
        return null;
    }

    public static String executionReason(JsonNode plan, JsonNode report) {
        String invalid = planReason(plan);
        if (invalid != null) return invalid;
        if (report == null || !report.path("scenario_results").isArray()) return "QA 缺少逐情境執行結果，禁止發布。";
        Set<String> expected = new HashSet<>();
        plan.path("scenarios").forEach(s -> expected.add(s.path("id").asText()));
        Set<String> actual = new HashSet<>();
        for (JsonNode result : report.path("scenario_results")) {
            String id = result.path("scenario_id").asText();
            if (!expected.contains(id) || !actual.add(id)) return "QA 結果包含未知或重複情境 ID：" + id;
            if (!"PASS".equals(result.path("status").asText())) return "QA 情境 " + id + " 未通過或無法驗證；須回報操作、錯誤、證據與處理者。";
            for (String field : new String[]{"actual_result", "evidence", "command"}) {
                if (!result.path(field).isTextual() || result.path(field).asText().isBlank()) return "QA 情境 " + id + " 缺少 " + field;
            }
        }
        if (!actual.equals(expected)) return "QA 執行結果遺漏測試計畫情境，禁止發布。";
        for (JsonNode f : report.path("findings")) {
            if ("HIGH".equals(f.path("severity").asText())) return "QA 仍有 HIGH 問題，禁止發布。";
        }
        if (report.path("uncertainties").isArray() && !report.path("uncertainties").isEmpty()) return "QA 仍有未核實事項，禁止發布。";
        return null;
    }
}
