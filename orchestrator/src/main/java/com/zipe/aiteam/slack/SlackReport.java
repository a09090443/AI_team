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

    /** 摘要模式下 summary 的上限；超過就截斷，完整內容仍在任務紀錄裡。 */
    private static final int SUMMARY_LIMIT = 500;

    /**
     * 只回報「要不要處理、處理什麼」所需的最少內容。
     *
     * 每個節點結束就把整份結構化報告貼進討論串，會讓真正需要人看的東西
     * （受阻、待確認、下一步）淹沒在情境清單與證據裡。這裡只留四項：
     * 判定／執行狀態、摘要、阻礙、建議後續，其餘一律不顯示 ——
     * 不是刪掉，`stage_result` 仍保存完整報告，隨時可以叫出來。
     *
     * 刻意不做「智慧摘要」：摘要由 agent 自己在 summary 欄位給，
     * 這裡再壓一次只會多一層失真，而且壓縮的是唯一能追責的那份文字。
     */
    public static String brief(JsonNode report) {
        if (report == null || report.isNull()) return "沒有有效報告";
        StringBuilder out = new StringBuilder();

        String status = report.path("verdict").asText(report.path("execution_status").asText(""));
        if (!status.isBlank()) out.append(statusLabel(status)).append('\n');

        String summary = report.path("summary").asText("");
        if (!summary.isBlank()) {
            out.append(plain(summary.length() <= SUMMARY_LIMIT
                    ? summary : summary.substring(0, SUMMARY_LIMIT) + "…")).append('\n');
        }

        // 阻礙一定要看得到：這是唯一會要求人立刻做事的欄位。
        JsonNode blockers = report.get("blockers");
        if (blockers != null && blockers.isArray() && !blockers.isEmpty()) {
            out.append("\n阻礙 ").append(blockers.size()).append(" 項，第一項：\n");
            StringBuilder first = new StringBuilder();
            value(first, blockers.get(0), 0);
            out.append(Diagnostics.clip(first.toString().strip(), 300)).append('\n');
        }

        String route = report.path("route").asText("");
        if (!route.isBlank()) out.append("\n建議後續階段：").append(plain(route)).append('\n');

        int omitted = 0;
        var shown = java.util.Set.of("verdict", "execution_status", "summary", "blockers", "route");
        for (var it = report.fieldNames(); it.hasNext(); ) if (!shown.contains(it.next())) omitted++;
        if (omitted > 0) out.append("\n（另有 ").append(omitted).append(" 個欄位未顯示）");

        String text = Diagnostics.safe(out.toString().strip());
        return text.isBlank() ? "報告未提供可顯示的內容" : text;
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "COMPLETED" -> "本階段已完成（不代表整體任務或 QA 驗收通過）";
            case "PARTIAL" -> "部分完成";
            case "BLOCKED" -> "受阻";
            case "FAILED" -> "失敗";
            case "PASS" -> "判定：通過";
            case "FAIL" -> "判定：不通過";
            case "SUCCESS" -> "成功";
            default -> plain(status);
        };
    }

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
