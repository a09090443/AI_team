package com.zipe.aiteam.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.zipe.aiteam.runtime.Diagnostics;
import java.util.Map;

/** Display only: the structured receipt remains untouched for routing and audit. */
public final class SlackReport {
    private SlackReport() {}
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("summary", "摘要"), Map.entry("execution_status", "階段執行狀態"),
            Map.entry("route", "建議後續階段"), Map.entry("blockers", "阻礙與待處理事項"),
            Map.entry("next_steps", "後續步驟"), Map.entry("reason", "原因"),
            Map.entry("evidence", "查核證據"), Map.entry("tests", "測試"),
            Map.entry("scenario_results", "情境驗證結果"), Map.entry("verification_status", "核實狀態"),
            Map.entry("verdict", "判定"), Map.entry("findings", "查核發現"),
            Map.entry("uncertainties", "待確認事項"), Map.entry("changes", "變更內容"),
            Map.entry("id", "情境編號"), Map.entry("requirement_ids", "對應需求"),
            Map.entry("criterion", "驗收條件"), Map.entry("preconditions", "前置條件"),
            Map.entry("test_data", "測試資料"), Map.entry("method", "測試方式"),
            Map.entry("steps", "執行步驟"), Map.entry("expected_result", "預期結果"),
            Map.entry("environment", "測試環境"));

    public static String render(JsonNode report) {
        if (report == null || report.isNull()) return "沒有有效報告";
        StringBuilder out = new StringBuilder();
        if (report.isObject()) {
            for (String key : new String[]{"execution_status", "summary", "blockers", "route"}) {
                if (report.has(key)) field(out, key, report.get(key), 0);
            }
            report.fields().forEachRemaining(e -> {
                if (!java.util.Set.of("execution_status", "summary", "blockers", "route").contains(e.getKey()))
                    field(out, e.getKey(), e.getValue(), 0);
            });
        } else value(out, report, 0);
        String text = Diagnostics.safe(out.toString().strip());
        if (text.length() > 8500) text = text.substring(0, 8500) + "\n…內容較長，完整報告保留於任務紀錄。";
        return text.isBlank() ? "報告未提供可顯示的內容" : text;
    }

    private static void field(StringBuilder out, String key, JsonNode node, int depth) {
        if (out.length() > 9000 || node == null || node.isNull() || node.isContainerNode() && node.isEmpty()) return;
        out.append("\n").append("  ".repeat(depth)).append(plain(LABELS.getOrDefault(key, key))).append("：\n");
        // COMPLETED describes this stage, not acceptance of the whole task.
        if (key.equals("execution_status") && node.isTextual()) {
            out.append(switch (node.asText()) {
                case "COMPLETED" -> "本階段已完成（不代表整體任務或 QA 驗收通過）";
                case "PARTIAL" -> "部分完成";
                case "BLOCKED" -> "受阻";
                case "FAILED" -> "失敗";
                default -> plain(node.asText());
            }).append('\n');
        } else value(out, node, depth + 1);
    }

    private static void value(StringBuilder out, JsonNode node, int depth) {
        if (out.length() > 9000) return;
        if (depth > 6) { out.append("詳細內容請見完整任務紀錄。\n"); return; }
        if (node.isObject()) node.fields().forEachRemaining(e -> field(out, e.getKey(), e.getValue(), depth));
        else if (node.isArray()) {
            for (JsonNode item : node) {
                if (out.length() > 9000) break;
                out.append("  ".repeat(depth)).append("• ");
                value(out, item, depth + 1);
            }
        } else if (!node.isNull()) out.append(plain(node.asText())).append('\n');
    }

    private static String plain(String text) {
        // JSON parsing already decodes newlines. Do not replace literal backslash-n in paths/code.
        return Diagnostics.safe(text).replace("\\`", "`").replace("\\_", "_")
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
