package com.zipe.aiteam.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zipe.aiteam.runtime.Diagnostics;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/** 所有 runtime 與角色共用的失敗回報契約。格式完整不等於證據已被獨立核實。 */
public final class FailureReport {
    private FailureReport() {}
    private static final List<String> FIELDS = List.of("operation", "observed_error", "evidence",
            "impact", "cause_status", "cause", "next_action", "owner");

    public static final String INSTRUCTIONS = """

        【所有角色共用的執行回報契約】
        execution_status 必填：COMPLETED 或 BLOCKED；failure_report 必填。
        正常完成時 failure_report 為 null。無法完成、核實不足、LOW confidence、
        FAILED/PARTIAL/FAIL/REQUEST_CHANGES、建置未通過或遇到邊界衝突時，必須 BLOCKED。
        failure_report 必須逐欄填寫非空內容：
        operation：實際失敗的指令、讀取路徑或待完成的判定。
        observed_error：原始錯誤與結束碼；未取得則明寫未取得，不得杜撰。
        evidence：可追查的工具呼叫 ID、log 路徑或需求原文；只有自陳時必須明示。
        impact：哪些工作未完成、哪些結果未核實。
        cause_status：CONFIRMED 或 UNKNOWN。只知道錯誤症狀不代表知道根因。
        cause：只寫已由證據證實的原因；UNKNOWN 時寫尚未確認及缺少的證據。
        next_action：具體的查核或排除步驟；owner：負責處理的角色或需求提出者。
        不得只寫「可能環境有問題」。假說不能當作已確認原因，需求不足要引用缺少的資訊。
        不要在回報中輸出 token、密碼、Authorization 或其他憑證值。
        有非致命工具失敗但已用替代方式完成時可以 COMPLETED，需在 summary 說明
        替代操作與驗證結果；不可將尚未解除的阻礙放進 uncertainties 後宣稱完成。
        BLOCKED 會停止下游與 PR 發布、保留現場並向 PM 與需求提出者回報。
        證據只能寫你本次實際執行成功的工具輸出。工具被拒時要如實說明，不得改寫成已完成的查核，
        也不得把其他報告裡的指令輸出當成自己執行的結果。
        提示中的「尚未解除的受阻發現」是系統保存的開放缺陷，優先於其他報告的通過結論；
        要判定已解除，必須指出是哪一次變更修掉的，或說明原判定何處有誤。
        「系統獨立驗證收據」在不產生收據的階段顯示為無，是流程設計，不代表建置未通過或無人核准。

        【需要真人決定或處理時：human_decision】
        human_decision 必填：不需要真人時為 null。只要 route=human，或阻礙必須由真人／維運處理，
        就必須填寫，因為系統會把它原樣貼給驗收負責人並做成按鈕，這是對方唯一會讀的說明。
        一律用白話寫給不看程式與配置的人：不要用節點代號、欄位名、SC／REQ 編號當主詞，
        必要時放在括號補充。
        headline：一句話說為什麼停下來。
        question：一次只問一件最需要對方決定的事，具體到對方能直接選。
        options：2 到 4 個選項；label 是按鈕文字（15 字內），consequence 說選了之後會發生什麼、
        要多花什麼、有什麼風險。
        recommendation：你建議選哪個、為什麼。
        also_needed：不是選擇題、但也需要真人或維運去做的事（例如補環境、給權限），每項一句；沒有就空陣列。
        其他需要決定的事放進 also_needed，不要硬塞進同一題。
        """;

    /** 在送往 runtime 前擴充 schema，避免五份契約各自漂移。 */
    public static String extendSchema(String schema) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(schema);
        var properties = (ObjectNode) root.path("properties");
        properties.set("execution_status", mapper.readTree("""
            {"type":"string","enum":["COMPLETED","BLOCKED"]}
            """));
        ObjectNode report = mapper.createObjectNode();
        report.putArray("type").add("object").add("null");
        report.put("additionalProperties", false);
        ObjectNode fields = report.putObject("properties");
        for (String field : FIELDS) {
            fields.putObject(field).put("type", "string").put("minLength", 1);
            report.withArray("required").add(field);
        }
        ((ObjectNode) fields.get("cause_status")).putArray("enum").add("CONFIRMED").add("UNKNOWN");
        properties.set("failure_report", report);
        properties.set("human_decision", mapper.readTree("""
            {"type":["object","null"],"additionalProperties":false,
             "properties":{
               "headline":{"type":"string","minLength":1},
               "question":{"type":"string","minLength":1},
               "options":{"type":"array","items":{"type":"object","additionalProperties":false,
                  "properties":{"label":{"type":"string","minLength":1},"consequence":{"type":"string","minLength":1}},
                  "required":["label","consequence"]}},
               "recommendation":{"type":"string","minLength":1},
               "also_needed":{"type":"array","items":{"type":"string","minLength":1}}},
             "required":["headline","question","options","recommendation","also_needed"]}
            """));
        root.withArray("required").add("execution_status").add("failure_report").add("human_decision");
        return root.toString();
    }

    /** null 表示可繼續；其餘為可回報的阻擋理由。舊報告也必須重新核實。 */
    public static String blockingReason(String role, JsonNode r) {
        if (r == null || !r.isObject()) return invalid("沒有有效的結構化輸出");
        String status = r.path("execution_status").asText();
        if (!Set.of("COMPLETED", "BLOCKED").contains(status) || !r.has("failure_report")) {
            return invalid("缺少 execution_status 或 failure_report");
        }
        JsonNode report = r.path("failure_report");
        boolean blocked = "BLOCKED".equals(status) || requiresReport(role, r);
        if (!blocked && report.isNull()) return null;
        if (!report.isObject()) return invalid("角色未完成或無法核實，但缺少具體 failure_report");
        for (String field : FIELDS) {
            if (!report.path(field).isTextual() || report.path(field).asText().isBlank()) {
                return invalid("failure_report 缺少非空欄位 " + field);
            }
        }
        if (!Set.of("CONFIRMED", "UNKNOWN").contains(report.path("cause_status").asText())) {
            return invalid("cause_status 必須為 CONFIRMED 或 UNKNOWN");
        }
        return "角色回報（內容為 agent 自陳，證據尚待獨立核實）：\n"
                + "失敗操作：" + value(report, "operation")
                + "\n觀察錯誤：" + value(report, "observed_error")
                + "\n證據位置：" + value(report, "evidence")
                + "\n影響範圍：" + value(report, "impact")
                + "\n原因狀態：" + value(report, "cause_status") + "；" + value(report, "cause")
                + "\n下一步：" + value(report, "next_action")
                + "\n處理者：" + value(report, "owner");
    }

    private static String value(JsonNode r, String key) {
        return Diagnostics.clip(r.path(key).asText(), 400);
    }

    private static boolean requiresReport(String role, JsonNode r) {
        if ("LOW".equals(r.path("confidence").asText())) return true;
        if (r.path("blockers").isArray() && !r.path("blockers").isEmpty()) return true;
        return switch (role) {
            case "qa_plan" -> QaGate.planReason(r) != null;
            case "pm_triage" -> TriageGate.blockingReason(r) != null;
            case "backend" -> implementationIncomplete(r);
            case "qa" -> verificationIncomplete(r);
            case "code_review" -> ReviewGate.blockingReason(r) != null;
            default -> false;
        };
    }

    public static boolean implementationIncomplete(JsonNode r) {
        return !"SUCCESS".equals(r.path("status").asText()) || r.hasNonNull("boundary_conflict")
                || !r.path("quality_gate").path("verify_passed").asBoolean(false);
    }
    public static boolean verificationIncomplete(JsonNode r) {
        return !Set.of("PASS", "PASS_WITH_CONCERNS").contains(r.path("verdict").asText())
                || !r.path("verify_passed").asBoolean(false);
    }

    private static String invalid(String error) {
        return "已確認：回報契約不完整或矛盾：" + error
                + "。\n原任務的失敗根因：尚未確認，缺少可追查的回報證據。"
                + "\n下一步：由該角色補齊失敗操作、錯誤、證據、影響、原因狀態與處理者後重跑。";
    }

    public static final class Blocked extends Exception {
        public Blocked(String message) { super(message); }
    }
}
