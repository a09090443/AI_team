package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.zipe.aiteam.task.QaGate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.zipe.aiteam.flow.TeamDefinition.*;

/** 對照已保存基準，不讓角色自行宣稱覆蓋完整。 */
public final class Traceability {
    private Traceability() {}
    public static String requirementsReason(JsonNode report) {
        if (report == null) return "缺少需求基準";
        if (!text(report, "summary")) return "需求基準缺少摘要";
        if (!report.path("out_of_scope").isArray() || !report.path("open_questions").isArray()) return "缺少需求範圍／待確認問題";
        for (JsonNode item : report.path("out_of_scope"))
            if (!item.isTextual() || item.asText().isBlank()) return "排除範圍須為具體文字";
        if (!report.path("open_questions").isEmpty()) return "需求尚有待確認問題，必須由真人釐清";
        JsonNode requirements = report.path("requirements");
        if (!requirements.isArray() || requirements.isEmpty()) return "沒有可驗收需求";
        Set<String> ids = new HashSet<>();
        for (JsonNode requirement : requirements) {
            String id = requirement.path("id").asText();
            if (!id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || !ids.add(id)) return "需求 ID 無效或重複：" + id;
            for (String field : List.of("description", "acceptance", "source"))
                if (!text(requirement, field)) return "需求 " + id + " 缺少 " + field;
            if (!Set.of("FUNCTIONAL", "NON_FUNCTIONAL").contains(requirement.path("kind").asText())) return "需求類型無效：" + id;
        }
        return null;
    }
    public static JsonNode baseline(FlowRun flow) {
        return flow.snapshot.definition().nodes().entrySet().stream().filter(e -> e.getValue().gate() == Gate.REQUIREMENTS)
                .map(Map.Entry::getKey).map(flow.receipts::get).filter(Objects::nonNull).findFirst().orElse(null);
    }
    public static String planReason(JsonNode plan, FlowRun flow) {
        String invalid = QaGate.planReason(plan);
        if (invalid != null) return invalid;
        invalid = requirementsReason(baseline(flow));
        if (invalid != null) return invalid;
        if (flow.baselineRevision == null || !flow.baselineRevision.equals(plan.path("baseline_revision").asText()))
            return "測試計畫引用的需求基準版本不符";
        Set<String> expected = new HashSet<>(), covered = new HashSet<>();
        baseline(flow).path("requirements").forEach(r -> expected.add(r.path("id").asText()));
        for (JsonNode scenario : plan.path("scenarios")) {
            JsonNode refs = scenario.path("requirement_ids");
            if (!refs.isArray() || refs.isEmpty()) return "情境 " + scenario.path("id").asText() + " 未連結需求 ID";
            Set<String> unique = new HashSet<>();
            for (JsonNode ref : refs) {
                if (!ref.isTextual() || !expected.contains(ref.asText()) || !unique.add(ref.asText())) return "情境引用未知或重複需求 ID：" + ref;
                covered.add(ref.asText());
            }
        }
        expected.removeAll(covered);
        return expected.isEmpty() ? null : "測試計畫遺漏需求：" + new TreeSet<>(expected);
    }
    public static String executionReason(Node node, JsonNode report, FlowRun flow) {
        JsonNode plan = flow.receipts.get(node.plan());
        String invalid = planReason(plan, flow);
        if (invalid != null) return invalid;
        if (!Objects.equals(flow.baselineRevision, report.path("baseline_revision").asText())
                || !digest(plan).equals(report.path("plan_revision").asText())) return "驗收引用舊需求或舊測試計畫";
        return QaGate.executionReason(plan, report);
    }
    public static String digest(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("不能計算契約版本", e); }
    }
    private static boolean text(JsonNode node, String field) { return node.path(field).isTextual() && !node.path(field).asText().isBlank(); }

    /** 任何被重做的節點，其相依報告都失效；歷史 stage/audit 不刪除。 */
    public static void invalidate(FlowRun flow, String id) {
        Set<String> removed = new HashSet<>(); removed.add(id);
        boolean changed;
        do {
            changed = false;
            for (var entry : flow.snapshot.definition().nodes().entrySet()) {
                Node n = entry.getValue();
                if ((n.requires() != null && n.requires().stream().anyMatch(removed::contains)) || removed.contains(n.plan()))
                    changed |= removed.add(entry.getKey());
            }
        } while (changed);
        for (String key : removed) { flow.receipts.remove(key); flow.heads.remove(key); }
        flow.independentEvidence = null; flow.approvalHead = null;
    }
}
