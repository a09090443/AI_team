package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.zipe.aiteam.flow.TeamDefinition.*;

/**
 * 驗收環境驗不了的驗收項目，在測試計畫自動延後，並在 PR 逐項列出（project.autonomy.deferUnverifiable，2026-10-23）。
 *
 * #1053～#1055 的需求寫進「部署後網址能開」「比對專案外舊檔」這類 QA 環境做不到的驗收，計畫或驗收階段才發現，
 * 每次都停給真人改需求，前後 4 次、累計約 12 小時。開關打開時，計畫把這些項目列在 deferred_acceptance，
 * 驗收照計畫跑其餘情境；負責人在核准 PR 時看到延後清單，PR 仍需真人核准。未開啟的團隊 schema 與行為完全不變。
 */
public final class DeferredAcceptance {
    private DeferredAcceptance() {}
    static final String FIELD = "deferred_acceptance";
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String INSTRUCTIONS = """

        【驗收環境驗不了的項目：deferred_acceptance】
        本專案開放延後驗收：某個驗收項目必須用本專案驗收節點的操作契約沒有的能力才能驗證時
        （例如實際部署後探測網址、連線外部系統或真實資料庫、讀取專案以外的檔案），把它列進 deferred_acceptance，
        不要為它寫情境，也不要因此回報受阻。每項寫 acceptance_id、reason（為什麼這個環境驗不了）、needs（要什麼環境或能力才能驗）。
        能用已授權操作或讀檔（inspect-content）驗證的項目不得延後；延後的項目會在 PR 逐項列出，由負責人核准 PR 時決定。
        沒有要延後的項目時給空陣列。
        """;

    public static boolean enabled(FlowRun flow) {
        if (flow == null || flow.snapshot == null || flow.dialogue) return false;
        Autonomy autonomy = flow.snapshot.definition().project().autonomy();
        return autonomy != null && autonomy.deferUnverifiable() && flow.snapshot.definition().version() >= 2;
    }

    /** 測試計畫 schema 多一個必填陣列；未開啟時不呼叫。 */
    public static String extendSchema(String schema) throws Exception {
        ObjectNode root = (ObjectNode) JSON.readTree(schema);
        if (!(root.path("properties") instanceof ObjectNode properties)) throw new IllegalArgumentException("測試計畫 schema 缺少 properties");
        properties.set(FIELD, JSON.readTree("""
            {"type":"array","items":{"type":"object","additionalProperties":false,
              "properties":{"acceptance_id":{"type":"string","minLength":1},"reason":{"type":"string","minLength":1},
                            "needs":{"type":"string","minLength":1}},
              "required":["acceptance_id","reason","needs"]}}
            """));
        boolean required = false;
        for (JsonNode key : root.withArray("required")) if (FIELD.equals(key.asText())) required = true;
        if (!required) root.withArray("required").add(FIELD);
        return root.toString();
    }

    /**
     * 計畫中延後的驗收項目 ID；格式錯誤時以 IllegalArgumentException 說明原因。
     * 未開啟時一律回空集合：舊快照或沒開的團隊即使模型多寫了這個欄位也不算涵蓋。
     */
    static Set<String> deferred(JsonNode plan, FlowRun flow, Map<String, String> acceptanceOwners) {
        if (!enabled(flow) || plan == null || !plan.path(FIELD).isArray()) return Set.of();
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode item : plan.path(FIELD)) {
            String id = item.path("acceptance_id").asText();
            if (!acceptanceOwners.containsKey(id) || !ids.add(id)) throw new IllegalArgumentException("延後驗收引用未知或重複的驗收項目 ID：" + id);
            for (String field : List.of("reason", "needs"))
                if (item.path(field).asText().isBlank()) throw new IllegalArgumentException("延後驗收 " + id + " 缺少 " + field);
        }
        for (JsonNode scenario : plan.path("scenarios"))
            for (JsonNode ref : scenario.path("acceptance_ids"))
                if (ids.contains(ref.asText())) throw new IllegalArgumentException("驗收項目 " + ref.asText() + " 已延後，不能同時寫情境");
        return ids;
    }

    /** 給核准 PR 訊息與 PR 說明：目前有效計畫裡延後的項目，一項一行；沒有就回空清單。 */
    public static List<String> lines(FlowRun flow) {
        if (!enabled(flow)) return List.of();
        Map<String, String> criteria = new HashMap<>();
        JsonNode baseline = Traceability.baseline(flow);
        if (baseline != null) baseline.path("requirements").forEach(r -> r.path("acceptance_items")
                .forEach(i -> criteria.put(i.path("id").asText(), i.path("criterion").asText())));
        List<String> lines = new ArrayList<>();
        flow.snapshot.definition().nodes().forEach((id, node) -> {
            if (node.gate() != Gate.TEST_PLAN) return;
            JsonNode plan = flow.receipts.get(id);
            if (plan == null) return;
            for (JsonNode item : plan.path(FIELD)) {
                String acceptance = item.path("acceptance_id").asText();
                lines.add(acceptance + "（" + criteria.getOrDefault(acceptance, "") + "）：" + item.path("reason").asText()
                        + "；需要：" + item.path("needs").asText());
            }
        });
        return lines;
    }

    /** Slack 用的段落；沒有延後項目時為空字串。 */
    public static String summary(FlowRun flow) {
        List<String> lines = lines(flow);
        if (lines.isEmpty()) return "";
        return "\n\n*延後驗收（本環境無法驗證，需另行確認）*\n・" + String.join("\n・", lines);
    }
}
