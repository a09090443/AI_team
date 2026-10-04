package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.zipe.aiteam.runtime.Diagnostics;
import com.zipe.aiteam.flow.TeamDefinition.Gate;
import com.zipe.aiteam.task.TaipeiTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PR 內文的驗收紀錄與系統獨立驗證，轉成 GitHub Markdown 條列。
 *
 * 報告欄位由各團隊配置的 schema 決定，程式不能假設固定結構；這裡只做通用的巢狀條列，
 * 並把常見欄位（摘要、判定、編號、狀態）提前、翻成中文標籤。原始 JSON 仍保存在任務紀錄。
 * 不沿用 SlackReport：Slack 需要 HTML 跳脫，貼到 GitHub 會變成 &amp;lt; 之類的字面值。
 */
final class PrReport {
    private PrReport() {}

    /** GitHub PR 內文上限 65536 字；留空間給需求原文與審查建議。 */
    static final int RECEIPTS_LIMIT = 40_000;
    /** 單一節點上限；需求與計畫節點常有數十項，不讓它擠掉驗收與審查。 */
    static final int NODE_LIMIT = 12_000;
    private static final int TEXT_LIMIT = 800;

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("summary", "摘要"), Map.entry("status", "狀態"), Map.entry("verdict", "判定"),
            Map.entry("execution_status", "執行狀態"), Map.entry("route", "下一步"),
            Map.entry("verify_passed", "品質檢查通過"), Map.entry("quality_gate", "品質檢查"),
            Map.entry("boundary_conflict", "範圍衝突"), Map.entry("scenario_results", "情境驗證結果"),
            Map.entry("scenario_id", "情境"), Map.entry("actual_result", "實際結果"),
            Map.entry("evidence", "證據"), Map.entry("command", "指令"), Map.entry("findings", "發現"),
            Map.entry("severity", "嚴重度"), Map.entry("detail", "說明"), Map.entry("uncertainties", "待確認事項"),
            Map.entry("baseline_revision", "需求基準"), Map.entry("plan_revision", "測試計畫版本"),
            Map.entry("verification_status", "核實狀態"), Map.entry("confidence", "信心"),
            Map.entry("blockers", "阻礙"), Map.entry("checked", "已檢查"), Map.entry("requirements", "需求"),
            Map.entry("kind", "類型"), Map.entry("description", "描述"), Map.entry("acceptance", "驗收標準"),
            Map.entry("acceptance_items", "驗收項目"), Map.entry("source", "來源"), Map.entry("dimension", "維度"),
            Map.entry("out_of_scope", "不在範圍內"), Map.entry("open_questions", "未決問題"),
            Map.entry("scenarios", "測試情境"), Map.entry("criterion", "驗收條件"),
            Map.entry("preconditions", "前置條件"), Map.entry("test_data", "測試資料"), Map.entry("method", "測試方式"),
            Map.entry("steps", "執行步驟"), Map.entry("expected_result", "預期結果"), Map.entry("environment", "測試環境"),
            Map.entry("requirement_ids", "對應需求"), Map.entry("acceptance_ids", "對應驗收項目"),
            Map.entry("operation_ids", "對應操作"), Map.entry("counterexample", "反例／弱實作"),
            Map.entry("changes", "變更內容"), Map.entry("tests", "測試"), Map.entry("next_steps", "後續步驟"),
            Map.entry("reason", "原因"));

    /** 排在最前面的欄位；判定與摘要是讀者最先要看的。 */
    private static final List<String> FIRST = List.of("verdict", "status", "execution_status", "summary");
    /** 陣列元素的標題欄位與附在標題後的狀態欄位。 */
    private static final List<String> TITLE_KEYS = List.of("scenario_id", "id");
    private static final List<String> BADGE_KEYS = List.of("status", "severity", "kind");
    /** out_of_scope_titles 只是 Slack 按鈕上的短標題，內容與 out_of_scope 重複。 */
    private static final Set<String> SKIP = Set.of("human_decision", "out_of_scope_titles");

    /** 依流程節點順序列出每個節點的報告；未在配置中的節點（舊快照）排在最後。 */
    static String receipts(FlowRun flow) {
        StringBuilder out = new StringBuilder();
        var definition = flow.snapshot.definition();
        var order = new java.util.LinkedHashSet<>(definition.nodes().keySet());
        order.addAll(flow.receipts.keySet());
        List<String> omitted = new java.util.ArrayList<>();
        for (String nodeId : order) {
            JsonNode report = flow.receipts.get(nodeId);
            if (report == null) continue;
            var node = definition.nodes().get(nodeId);
            var role = node == null || node.role() == null ? null : definition.roles().get(node.role());
            String title = role == null ? nodeId : role.name() + "（" + nodeId + "）";
            if (!omitted.isEmpty() || out.length() >= RECEIPTS_LIMIT) { omitted.add(title); continue; }
            StringBuilder body = new StringBuilder();
            if (report.isObject()) object(body, report, 0);
            else body.append("- ").append(text(report.asText(), 0)).append('\n');
            String status = FIRST.stream().filter(k -> report.path(k).isValueNode() && !"summary".equals(k))
                    .map(k -> " — " + badge(report.get(k).asText())).findFirst().orElse("");
            // 驗收與審查是 PR 讀者最需要的證據，預設展開；需求、計畫等較長的節點預設收合。
            boolean open = node != null && (node.gate() == Gate.VERIFICATION || node.gate() == Gate.REVIEW);
            out.append(open ? "<details open>" : "<details>").append("<summary><b>").append(escape(title)).append("</b>")
               .append(status.replace("`", "")).append("</summary>\n\n")
               .append(cut(body.toString(), Math.min(NODE_LIMIT, Math.max(2_000, RECEIPTS_LIMIT - out.length()))))
               .append("\n</details>\n\n");
        }
        if (!omitted.isEmpty())
            out.append("_…內容較長，未列出的節點報告保留於任務紀錄：").append(escape(String.join("、", omitted))).append("_\n");
        return out.isEmpty() ? "_（沒有節點報告）_\n" : out.toString();
    }

    /** 在行邊界截斷，避免切斷清單或行內程式碼。 */
    private static String cut(String markdown, int max) {
        if (markdown.length() <= max) return markdown;
        int end = markdown.lastIndexOf('\n', max);
        return markdown.substring(0, end < 0 ? max : end + 1) + "- _…此節點內容較長，其餘保留於任務紀錄。_\n";
    }

    private static String escape(String html) {
        return html.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String independent(IndependentVerifier.Receipt receipt) {
        if (receipt == null) return "_（沒有系統獨立驗證收據）_\n";
        StringBuilder out = new StringBuilder();
        out.append("- 驗證 commit：").append(code(receipt.head())).append('\n');
        out.append("- 需求基準：").append(code(receipt.baseline())).append('\n');
        out.append("- 配置版本：").append(code(receipt.configuration())).append('\n');
        out.append("- 開始：").append(time(receipt.startedAt())).append('\n');
        out.append("- 完成：").append(time(receipt.completedAt())).append('\n');
        if (receipt.docOnly()) {
            out.append("- **純文件變更，依配置略過建置與測試**（系統依 git diff 判定，").append(receipt.docOnlyChanges().size())
               .append(" 個檔案）：\n");
            receipt.docOnlyChanges().stream().limit(30).forEach(path -> out.append("  - ").append(code(path)).append('\n'));
            if (receipt.docOnlyChanges().size() > 30) out.append("  - …等共 ").append(receipt.docOnlyChanges().size()).append(" 個\n");
            return out.toString();
        }
        List<IndependentVerifier.CommandResult> commands = receipt.commands() == null ? List.of() : receipt.commands();
        out.append("- 指令（").append(commands.size()).append(" 項）：\n");
        for (var command : commands) {
            out.append("  - ").append(command.exitCode() == 0 ? "✅ " : "❌ ")
               .append(code(String.join(" ", command.argv()))).append("（exit ").append(command.exitCode()).append("）\n");
            out.append("    - 紀錄雜湊：").append(code(command.sha256())).append('\n');
        }
        return out.toString();
    }

    private static void object(StringBuilder out, JsonNode node, int depth) {
        for (String key : FIRST) if (node.has(key)) field(out, key, node.get(key), depth);
        node.fields().forEachRemaining(e -> {
            if (!FIRST.contains(e.getKey()) && !SKIP.contains(e.getKey())) field(out, e.getKey(), e.getValue(), depth);
        });
    }

    private static void field(StringBuilder out, String key, JsonNode value, int depth) {
        if (value == null || value.isNull() || value.isContainerNode() && value.isEmpty()) return;
        String indent = "  ".repeat(depth);
        String label = "**" + LABELS.getOrDefault(key, key) + "**";
        if (value.isObject()) {
            out.append(indent).append("- ").append(label).append('\n');
            object(out, value, depth + 1);
        } else if (value.isArray()) {
            out.append(indent).append("- ").append(label).append("（").append(value.size()).append(" 項）\n");
            for (JsonNode item : value) item(out, item, depth + 1);
        } else {
            out.append(indent).append("- ").append(label).append("：").append(scalar(key, value, depth)).append('\n');
        }
    }

    private static void item(StringBuilder out, JsonNode item, int depth) {
        String indent = "  ".repeat(depth);
        if (!item.isObject()) {
            if (item.isArray()) { for (JsonNode inner : item) item(out, inner, depth); return; }
            out.append(indent).append("- ").append(text(item.asText(), depth)).append('\n');
            return;
        }
        String title = TITLE_KEYS.stream().filter(k -> item.path(k).isValueNode()).findFirst().orElse(null);
        String badge = BADGE_KEYS.stream().filter(k -> item.path(k).isValueNode()).findFirst().orElse(null);
        if (title == null && badge == null) {
            // 沒有標題欄位時，第一個欄位直接接在項目符號後，避免出現空白項目。
            out.append(indent).append("-\n");
            object(out, item, depth + 1);
            return;
        }
        out.append(indent).append("- ");
        if (title != null) out.append(code(item.get(title).asText()));
        if (badge != null) out.append(title == null ? "" : " ").append(badge(item.get(badge).asText()));
        out.append('\n');
        var rest = item.deepCopy();
        if (rest instanceof com.fasterxml.jackson.databind.node.ObjectNode o) {
            if (title != null) o.remove(title);
            if (badge != null) o.remove(badge);
            object(out, o, depth + 1);
        }
    }

    private static String scalar(String key, JsonNode value, int depth) {
        if (value.isBoolean()) return value.asBoolean() ? "✅ 是" : "❌ 否";
        if (Set.of("verdict", "status", "execution_status", "verification_status").contains(key)) return badge(value.asText());
        if (key.endsWith("_revision") || key.equals("command")) return code(value.asText());
        return text(value.asText(), depth);
    }

    private static String badge(String status) {
        String icon = switch (status.toUpperCase(java.util.Locale.ROOT)) {
            case "PASS", "SUCCESS", "COMPLETED", "APPROVE", "APPROVED", "READY", "VERIFIED" -> "✅ ";
            case "FAIL", "FAILED", "BLOCKED", "REQUEST_CHANGES", "HIGH", "CRITICAL", "BLOCKER" -> "❌ ";
            case "PARTIAL", "PASS_WITH_CONCERNS", "COMMENT", "MEDIUM", "SKIPPED", "NOT_RUN" -> "⚠️ ";
            default -> "";
        };
        return icon + "`" + Diagnostics.safe(status).replace("`", "'") + "`";
    }

    /** 多行文字接續縮排，才不會跳出所屬的清單項目。 */
    private static String text(String raw, int depth) {
        String clipped = Diagnostics.safe(raw).strip();
        if (clipped.length() > TEXT_LIMIT) clipped = clipped.substring(0, TEXT_LIMIT) + "…";
        return clipped.replace("\r\n", "\n").replace("\n", "\n" + "  ".repeat(depth + 1));
    }

    private static String code(String value) {
        if (value == null || value.isBlank()) return "（未提供）";
        String safe = Diagnostics.safe(value).replace("\n", " ");
        return safe.contains("`") ? "`` " + safe + " ``" : "`" + safe + "`";
    }

    private static String time(String iso) {
        try { return TaipeiTime.seconds(Instant.parse(iso)); }
        catch (Exception e) { return iso == null ? "（未提供）" : Diagnostics.safe(iso); }
    }
}
