package com.zipe.aiteam.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.zipe.aiteam.runtime.Diagnostics;
import java.util.Map;

/** Display only: the structured receipt remains untouched for routing and audit. */
public final class SlackReport {
    private SlackReport() {}
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("summary", "摘要"), Map.entry("execution_status", "階段執行狀態"),
            Map.entry("route", "下一步"), Map.entry("blockers", "阻礙與待處理事項"),
            Map.entry("next_steps", "後續步驟"), Map.entry("reason", "原因"),
            Map.entry("evidence", "查核證據"), Map.entry("tests", "測試"),
            Map.entry("scenario_results", "情境驗證結果"), Map.entry("verification_status", "核實狀態"),
            Map.entry("verdict", "判定"), Map.entry("findings", "查核發現"),
            Map.entry("uncertainties", "待確認事項"), Map.entry("changes", "變更內容"),
            Map.entry("id", "情境編號"), Map.entry("requirement_ids", "對應需求"),
            Map.entry("acceptance_items", "原子驗收項目"), Map.entry("acceptance_ids", "對應驗收項目"),
            Map.entry("counterexample", "反例／弱實作"), Map.entry("dimension", "驗收維度"),
            Map.entry("criterion", "驗收條件"), Map.entry("preconditions", "前置條件"),
            Map.entry("test_data", "測試資料"), Map.entry("method", "測試方式"),
            Map.entry("steps", "執行步驟"), Map.entry("expected_result", "預期結果"),
            Map.entry("environment", "測試環境"));

    /** 摘要模式下 summary 的上限；超過就截斷，完整內容仍在任務紀錄裡。 */
    private static final int SUMMARY_LIMIT = 500;

    /**
     * route 是配置裡的節點鍵，對讀訊息的人沒有意義。
     * 這裡翻成白話但保留原鍵，讓人看得懂、也還能跟配置對得起來；未知鍵原樣顯示。
     */
    private static final Map<String, String> ROUTES = Map.ofEntries(
            Map.entry("human", "需要你決定，系統不會自己往下做"),
            Map.entry("implementation", "交回工程實作修正"),
            Map.entry("verification", "交回測試重新驗收"),
            Map.entry("review", "交回獨立審查"),
            Map.entry("plan", "重新規劃測試"),
            Map.entry("design", "重新設計"),
            Map.entry("requirements", "需求要重新確認"),
            Map.entry("intake", "需求要重新確認"),
            Map.entry("triage", "交給 PM 分流判斷"),
            Map.entry("ready", "可以往下一步"),
            Map.entry("question", "有問題要先問清楚"));

    /** 顯示用：白話說明 + 原鍵，方便對照配置。 */
    public static String routeLabel(String route) {
        String plain = ROUTES.get(route);
        return plain == null ? plain(route) : plain(plain) + "（" + plain(route) + "）";
    }

    /** 報告是否帶有可顯示的真人決定事項。 */
    public static boolean hasDecision(JsonNode report) {
        JsonNode d = report == null ? null : report.get("human_decision");
        return d != null && d.isObject() && !d.path("question").asText("").isBlank();
    }

    /** 最多幾個選項做成按鈕；Slack 一排放太多按鈕也難讀。 */
    public static final int MAX_DECISION_OPTIONS = 4;

    /**
     * agent 需要真人決定時的完整白話說明，不做截斷摘要。
     * 這一段是驗收負責人唯一會讀的東西：摘要模式把 summary 切在 500 字，
     * #1014 三件待決事項因此只露出第一件的一小段，最後變成要人另外去翻完整報告。
     */
    public static String decision(JsonNode report) {
        if (!hasDecision(report)) return "";
        JsonNode d = report.get("human_decision");
        StringBuilder out = new StringBuilder();
        String headline = d.path("headline").asText("");
        if (!headline.isBlank()) out.append("*為什麼停下來*\n").append(plain(clip(headline, 400))).append("\n\n");
        out.append("*需要你決定*\n").append(plain(clip(d.path("question").asText(), 600))).append('\n');
        int i = 0;
        for (JsonNode option : d.path("options")) {
            if (i >= MAX_DECISION_OPTIONS) break;
            out.append("\n").append((char) ('A' + i++)).append("．")
               .append(plain(clip(option.path("label").asText(), 40))).append("\n　")
               .append(plain(clip(option.path("consequence").asText(), 500))).append('\n');
        }
        String recommendation = d.path("recommendation").asText("");
        if (!recommendation.isBlank())
            out.append("\n*建議*\n").append(plain(clip(recommendation, 500))).append('\n');
        JsonNode also = d.path("also_needed");
        if (also.isArray() && !also.isEmpty()) {
            out.append("\n*另外也需要處理*\n");
            for (JsonNode item : also) out.append("・").append(plain(clip(item.asText(), 300))).append('\n');
        }
        return Diagnostics.safe(out.toString().strip());
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

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
        // 需要人決定時，把決定事項放最前面、完整顯示；原本的摘要降為補充。
        if (hasDecision(report)) {
            String rest = briefBody(report);
            return decision(report) + (rest.isBlank() ? "" : "\n\n———\n" + rest);
        }
        return briefBody(report);
    }

    private static String briefBody(JsonNode report) {
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
        if (!route.isBlank()) out.append("\n下一步：").append(routeLabel(route)).append('\n');

        int omitted = 0;
        var shown = java.util.Set.of("verdict", "execution_status", "summary", "blockers", "route", "human_decision");
        for (var it = report.fieldNames(); it.hasNext(); ) if (!shown.contains(it.next())) omitted++;
        if (omitted > 0) out.append("\n（還有 ").append(omitted).append(" 項細節沒列出來，需要時可以叫出完整報告）");

        String text = Diagnostics.safe(out.toString().strip());
        return text.isBlank() ? "報告未提供可顯示的內容" : text;
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "COMPLETED" -> "這個階段做完了（還不代表整個任務驗收通過）";
            case "PARTIAL" -> "只做完一部分";
            case "BLOCKED" -> "卡住了，需要處理";
            case "FAILED" -> "失敗了";
            case "PASS" -> "驗收通過";
            case "FAIL" -> "驗收沒過";
            case "SUCCESS" -> "完成";
            case "APPROVE" -> "審查通過";
            case "REQUEST_CHANGES" -> "審查要求修改";
            case "COMMENT" -> "審查有意見但不擋";
            case "PASS_WITH_CONCERNS" -> "驗收通過，但有疑慮";
            default -> plain(status);
        };
    }

    public static String render(JsonNode report) {
        if (report == null || report.isNull()) return "沒有有效報告";
        StringBuilder out = new StringBuilder();
        if (hasDecision(report)) out.append(decision(report)).append("\n\n———\n");
        if (report.isObject()) {
            for (String key : new String[]{"execution_status", "summary", "blockers", "route"}) {
                if (report.has(key)) field(out, key, report.get(key), 0);
            }
            report.fields().forEachRemaining(e -> {
                if (!java.util.Set.of("execution_status", "summary", "blockers", "route", "human_decision").contains(e.getKey()))
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
            out.append(statusLabel(node.asText())).append('\n');
        } else if (key.equals("route") && node.isTextual()) {
            out.append(routeLabel(node.asText())).append('\n');
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
