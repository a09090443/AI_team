package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.zipe.aiteam.flow.TeamDefinition.*;

/** 建置既有服務與開發產品分流；不把設定檔產出當成服務已啟動。 */
public final class ServiceDelivery {
    private ServiceDelivery() {}

    static void validate(TeamDefinition definition) {
        var nodes = definition.nodes().entrySet().stream().filter(e -> e.getValue().gate() == Gate.SERVICE).toList();
        for (var entry : nodes) {
            Node node = entry.getValue();
            TeamCatalog.require(definition.version() >= 2 && node.scope() == Scope.LIMITED && node.workspace()
                    && node.plan() == null && "end".equals(node.next()) && (node.routes() == null || node.routes().isEmpty()),
                    "服務建置須限寫工作區、無 QA 計畫且成功後直接結束：" + entry.getKey());
            TeamCatalog.require(node.requires() != null && node.requires().stream()
                    .anyMatch(id -> definition.nodes().get(id).gate() == Gate.REQUIREMENTS), "服務建置缺需求基準");
        }
        ServiceTarget target = definition.project().serviceTarget();
        if (target == null) return; // 未提供目標時，可載入配置，但禁止執行／宣稱完成。
        TeamCatalog.require(!nodes.isEmpty() && target.name() != null && !target.name().isBlank()
                && target.name().length() <= 200 && target.checks() != null && target.checks().size() <= 12
                && target.checks().keySet().containsAll(Set.of("start", "health")), "服務目標須有名稱、start 與 health 操作");
        for (var entry : nodes) for (var check : target.checks().entrySet()) {
            TeamCatalog.require(check.getKey().matches("[a-z][a-z0-9_-]*")
                    && OperationContract.forNode(definition, entry.getKey()).containsKey(check.getValue()),
                    "服務檢查引用未配置給該節點的操作：" + check.getKey());
        }
    }

    static String readiness(FlowRun flow) {
        return flow.snapshot.definition().project().serviceTarget() == null
                ? "系統尚未配置部署目標與建置工具，這不代表使用者未提供主機。請先核對原始需求及補充，由部署工具維護者完成目標登錄；不要要求使用者自行安裝 SSH、Docker 或重填已提供資料。目前尚未啟動服務。" : null;
    }

    static boolean sensitivePath(String path) {
        String normalized = path.replace('\\', '/');
        return Arrays.stream(normalized.split("/")).anyMatch(part -> part.equals(".secrets") || part.equals(".ssh")
                || part.equals(".env") || (part.startsWith(".env.") && !part.endsWith(".example") && !part.endsWith(".template")))
                || normalized.endsWith(".pem") || normalized.endsWith(".key");
    }

    static String blockingReason(JsonNode report, FlowRun flow) {
        String missing = readiness(flow);
        if (missing != null) return missing;
        var target = flow.snapshot.definition().project().serviceTarget();
        if (!"RUNNING".equals(report.path("service_status").asText())
                || !target.name().equals(report.path("target").asText())) return "服務尚未在核准目標啟動，或回報目標不符";
        if (!report.path("product_code_changed").isBoolean() || report.path("product_code_changed").asBoolean(true)) return "修改產品程式須走開發流程，不能以服務建置略過驗收";
        JsonNode checks = report.path("checks");
        if (!checks.isArray()) return "缺少服務啟動與可用性證據";
        JsonNode baseline = Traceability.baseline(flow);
        if (baseline == null) return "服務建置缺少需求基準";
        Set<String> expected = new HashSet<>(), covered = new HashSet<>();
        baseline.path("requirements").forEach(r -> r.path("acceptance_items").forEach(a -> expected.add(a.path("id").asText())));
        if (expected.isEmpty()) return "服務需求缺少可核對的驗收項目";
        Set<String> seen = new HashSet<>();
        for (JsonNode check : checks) {
            String name = check.path("name").asText();
            if (!seen.add(name) || !target.checks().containsKey(name)
                    || !target.checks().get(name).equals(check.path("operation_id").asText())) return "服務檢查未對應核准操作或重複";
            if (!"PASS".equals(check.path("status").asText()) || !check.path("exit_code").isIntegralNumber()
                    || check.path("exit_code").asInt(-1) != 0
                    || !check.path("evidence").isTextual() || check.path("evidence").asText().isBlank()) return "服務檢查失敗或缺少執行證據：" + name;
            if (!check.path("acceptance_ids").isArray() || check.path("acceptance_ids").isEmpty()) return "服務檢查缺少驗收項目對應：" + name;
            for (JsonNode id : check.path("acceptance_ids")) {
                if (!id.isTextual() || !expected.contains(id.asText())) return "服務檢查引用未知驗收項目";
                covered.add(id.asText());
            }
        }
        if (!seen.equals(target.checks().keySet())) return "服務檢查未涵蓋全部核准項目";
        if (!covered.equals(expected)) return "服務檢查未涵蓋全部需求驗收項目";
        if (!report.path("uncertainties").isArray() || !report.path("uncertainties").isEmpty()) return "服務仍有未核實事項";
        return null;
    }

    static String notice(FlowRun flow, Node node) {
        if (flow.snapshot.definition().nodes().values().stream().noneMatch(n -> n.gate() == Gate.SERVICE)) return "";
        String text = "\n【服務建置與開發分流】建立／部署既有服務的目標是服務啟動並可用，走配置的 service 路線，"
                + "由 Infrastructure 角色建置、檢查、修復與重驗，不要求先寫 QA 計畫或跑無關的完整前後端回歸。"
                + "需要修改產品程式或新增功能則維持開發流程；不可把使用者要的啟動排除在 out_of_scope。"
                + "缺主機／權限時只詢問實際缺少的資訊，不自行改成交付文件。"
                + "\n管理者配置的服務目標與檢查操作：" + flow.snapshot.definition().project().serviceTarget();
        if (node.gate() == Gate.SERVICE) text += "\n依核准操作建置並啟動，再逐項執行檢查。失敗先在本階段修正後重驗；"
                + "無法修正則 BLOCKED 交分流。checks 只填本次實際操作的結果與 log／呼叫 ID，不能引用舊任務成功報告。"
                + "只產出設定檔不能填 RUNNING；不得修改產品程式、建立未核准外部資源、刪除既有資料或輸出憑證。"
                + "允許路徑內的檔案由系統核對後代為提交，不需要自行 commit。";
        return text;
    }

    static boolean serviceStage(FlowRun flow, Node node) {
        return node.gate() == Gate.SERVICE || (node.gate() == Gate.REPORT && node.routes() != null
                && node.routes().values().stream().anyMatch(id -> flow.snapshot.definition().nodes().containsKey(id)
                    && flow.snapshot.definition().nodes().get(id).gate() == Gate.SERVICE));
    }
}
