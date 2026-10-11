package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.zipe.aiteam.flow.TeamDefinition.*;

/** 計畫宣告路徑，系統比較 Git 物件；模型不能指定命令或比較基準。 */
public final class FileComparison {
    private FileComparison() {}
    public static final String OPERATION = "compare-unchanged-files";
    public static final String FIELD = "unchanged_paths";

    public static String extendSchema(String schema) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(schema);
        JsonNode item = root.path("properties").path("scenarios").path("items");
        if (!(item instanceof ObjectNode scenario)) throw new IllegalArgumentException("測試計畫 schema 缺少 scenarios");
        ((ObjectNode) scenario.path("properties")).set(FIELD, mapper.readTree(
                "{\"type\":\"array\",\"items\":{\"type\":\"string\"}}"));
        boolean required = false;
        for (JsonNode key : scenario.withArray("required")) if (FIELD.equals(key.asText())) required = true;
        if (!required) scenario.withArray("required").add(FIELD);
        return root.toString();
    }
    public static boolean selected(JsonNode scenario) {
        for (JsonNode op : scenario.path("operation_ids")) if (OPERATION.equals(op.asText())) return true;
        return false;
    }
    public static boolean safePath(String path) {
        return path != null && !path.isBlank() && path.length() <= 500 && !path.startsWith("/")
                && path.chars().noneMatch(c -> Character.isISOControl(c) || "\\:*?[]".indexOf(c) >= 0)
                && Arrays.stream(path.split("/", -1)).noneMatch(p -> p.isEmpty() || p.equals(".")
                    || p.equals("..") || p.equalsIgnoreCase(".git"));
    }
    public static String planReason(JsonNode plan) {
        Set<String> all = new HashSet<>();
        for (JsonNode scenario : plan.path("scenarios")) {
            JsonNode paths = scenario.path(FIELD);
            if (!selected(scenario)) {
                if (!paths.isMissingNode() && (!paths.isArray() || !paths.isEmpty())) return "unchanged_paths 必須搭配 " + OPERATION;
                continue;
            }
            if (!paths.isArray() || paths.isEmpty()) return "檔案比對缺少 unchanged_paths：" + scenario.path("id").asText();
            Set<String> unique = new HashSet<>();
            for (JsonNode path : paths) {
                if (!path.isTextual() || !safePath(path.asText()) || !unique.add(path.asText())) return "檔案比對路徑無效或重複：" + path;
                all.add(path.asText());
            }
            if (all.size() > 100) return "檔案比對最多 100 個明確檔案";
        }
        return null;
    }
    public static List<String> paths(FlowRun flow) {
        Set<String> paths = new TreeSet<>();
        flow.snapshot.definition().nodes().forEach((id, node) -> {
            JsonNode plan = flow.receipts.get(id);
            if (node.gate() != Gate.TEST_PLAN || plan == null) return;
            String invalid = planReason(plan);
            if (invalid != null) throw new IllegalArgumentException(invalid);
            for (JsonNode scenario : plan.path("scenarios"))
                if (selected(scenario)) scenario.path(FIELD).forEach(p -> paths.add(p.asText()));
        });
        if (paths.size() > 100) throw new IllegalArgumentException("檔案比對最多 100 個明確檔案");
        return List.copyOf(paths);
    }
    public static String planDigest(FlowRun flow) {
        ObjectNode plans = new ObjectMapper().createObjectNode();
        new TreeMap<>(flow.snapshot.definition().nodes()).forEach((id, node) -> {
            if (node.gate() == Gate.TEST_PLAN && flow.receipts.containsKey(id)) plans.set(id, flow.receipts.get(id));
        });
        return Traceability.digest(plans);
    }
    public static String notice() {
        return "\n系統內建 " + OPERATION + "：驗證指定舊檔案與本任務實作前的 Git 版本完全相同。"
                + "測試計畫在 operation_ids 引用它，並在情境的 unchanged_paths 列出完整相對檔案路徑（最多100個，不能用目錄或萬用字元）；"
                + "其他情境 unchanged_paths 填空陣列。系統會比較已提交檔案的內容與模式，QA 直接核對獨立驗證收據中的 fileComparison，無需自行執行 Git。"
                + "缺檔、刪除、改名、內容或模式改動都不算相同；目前不支援 symlink 或子模組。"
                + "baseline_revision 是需求內容的雜湊，絕不是 Git commit；檔案比較基準由系統保存，模型不得自行指定或改成目前 HEAD。"
                + "舊檔保持不變可用此方式驗證，不要因只有 inspect-content 就提議刪掉要求。"
                + "缺其他操作時回報具體能力缺口與處理者，不把工具不足當成刪除驗收要求的理由。";
    }
}
