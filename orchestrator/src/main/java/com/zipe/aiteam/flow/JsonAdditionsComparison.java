package com.zipe.aiteam.flow;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zipe.aiteam.store.TaskStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** 驗收計畫只宣告允許新增的陣列項目，系統選擇 Git 基準並比較完整 JSON。 */
final class JsonAdditionsComparison {
    static final String OPERATION = "compare-json-additions";
    static final String FIELD = "json_additions";
    static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_CHECKS = 10;
    private static final int MAX_ADDITIONS = 100;
    private static final int MAX_LINEAGE = 32;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private JsonAdditionsComparison() {}

    record Check(String path, String arrayPointer, String identityKey, List<String> additions) {}
    record Base(String taskId, String commit) {}
    record Evidence(Base base, String head, String planDigest, List<Check> checks, String log, String sha256) {}

    static boolean enabled(TeamDefinition definition) {
        return Boolean.TRUE.equals(definition.project().jsonAdditionsComparison());
    }

    static String extendSchema(String schema) throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(schema);
        ObjectNode scenario = (ObjectNode) root.path("properties").path("scenarios").path("items");
        ((ObjectNode) scenario.path("properties")).set(FIELD, MAPPER.readTree("""
                {"type":"array","items":{"type":"object","properties":{
                  "path":{"type":"string"},"array_pointer":{"type":"string"},
                  "identity_key":{"type":"string"},"additions":{"type":"array","items":{"type":"string"}}
                },"required":["path","array_pointer","identity_key","additions"],"additionalProperties":false}}
                """));
        scenario.withArray("required").add(FIELD);
        return root.toString();
    }

    static String planReason(JsonNode plan, TeamDefinition definition) {
        try {
            checks(plan, enabled(definition));
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    static List<Check> checks(FlowRun flow) {
        var checks = new LinkedHashSet<Check>();
        var definition = flow.snapshot.definition();
        definition.nodes().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .filter(e -> e.getValue().gate() == TeamDefinition.Gate.TEST_PLAN)
                .map(e -> flow.receipts.get(e.getKey())).filter(Objects::nonNull)
                .forEach(plan -> checks.addAll(checks(plan, enabled(definition))));
        if (checks.size() > MAX_CHECKS) throw new IllegalArgumentException("JSON 比對最多 10 項");
        return List.copyOf(checks);
    }

    private static List<Check> checks(JsonNode plan, boolean enabled) {
        var checks = new LinkedHashSet<Check>();
        for (JsonNode scenario : plan.path("scenarios")) {
            boolean selected = false;
            for (JsonNode op : scenario.path("operation_ids")) selected |= OPERATION.equals(op.asText());
            JsonNode items = scenario.path(FIELD);
            if (!selected && (items.isMissingNode() || (items.isArray() && items.isEmpty()))) continue;
            if (!enabled || !selected || !items.isArray() || items.isEmpty() || items.size() > MAX_CHECKS)
                throw new IllegalArgumentException(FIELD + " 須搭配已啟用的 " + OPERATION + "，每個情境限 1 到 10 項");
            for (JsonNode item : items) {
                checks.add(check(item));
                if (checks.size() > MAX_CHECKS) throw new IllegalArgumentException("JSON 比對最多 10 項");
            }
        }
        return List.copyOf(checks);
    }

    private static Check check(JsonNode item) {
        String path = item.path("path").textValue();
        String pointer = item.path("array_pointer").textValue();
        String key = item.path("identity_key").textValue();
        JsonNode additions = item.path("additions");
        if (!item.isObject() || item.size() != 4 || !FileComparison.safePath(path) || !path.endsWith(".json")
                || pointer == null || pointer.length() > 500 || (!pointer.isEmpty() && !pointer.startsWith("/"))
                || pointer.matches("(?s).*~(?![01]).*") || pointer.chars().anyMatch(Character::isISOControl)
                || !validText(key) || !additions.isArray() || additions.isEmpty() || additions.size() > MAX_ADDITIONS)
            throw new IllegalArgumentException("JSON 比對須指定相對 .json 路徑、JSON Pointer、識別欄位與 1 到 100 個新增值");
        var values = new LinkedHashSet<String>();
        for (JsonNode addition : additions) {
            if (!validText(addition.textValue()) || !values.add(addition.textValue()))
                throw new IllegalArgumentException("JSON 比對的新增值必須是唯一的非空字串");
        }
        return new Check(path, pointer, key, List.copyOf(values));
    }

    private static boolean validText(String value) {
        return value != null && !value.isBlank() && value.length() <= 500
                && value.chars().noneMatch(Character::isISOControl);
    }

    static Base originalBase(String taskId, FlowRun flow, TaskStore store) {
        var visited = new HashSet<String>();
        String repository = flow.snapshot.repository();
        while (flow.continuationOf != null) {
            String parentId = flow.continuationOf;
            if (store == null || !visited.add(taskId) || visited.size() >= MAX_LINEAGE || !parentId.matches("[0-9]+"))
                throw new IllegalStateException("無法核實 JSON 比對的任務承接鏈");
            FlowRun parent = store.flow(parentId).orElseThrow(() -> new IllegalStateException("找不到 JSON 比對的原任務"));
            if (!taskId.equals(parent.successor) || !repository.equals(parent.snapshot.repository()))
                throw new IllegalStateException("JSON 比對的任務承接關係或儲存庫不符");
            taskId = parentId;
            flow = parent;
        }
        if (flow.comparisonBase == null || !flow.comparisonBase.matches("[0-9a-f]{40}|[0-9a-f]{64}"))
            throw new IllegalStateException("缺少 JSON 比對的原始 Git 基準");
        return new Base(taskId, flow.comparisonBase);
    }

    static boolean matches(Check check, String beforeJson, String afterJson) throws Exception {
        JsonNode before = MAPPER.readTree(beforeJson);
        JsonNode after = MAPPER.readTree(afterJson);
        JsonPointer pointer = JsonPointer.compile(check.arrayPointer());
        if (before == null || after == null || !before.at(pointer).isArray() || !(after.at(pointer) instanceof ArrayNode array)) return false;
        var remaining = new HashSet<>(check.additions());
        var remove = new ArrayList<Integer>();
        for (int i = 0; i < array.size(); i++) {
            String value = array.get(i).path(check.identityKey()).textValue();
            if (value == null || !check.additions().contains(value)) continue;
            if (!remaining.remove(value)) return false;
            remove.add(i);
        }
        if (!remaining.isEmpty()) return false;
        for (int i = remove.size() - 1; i >= 0; i--) array.remove(remove.get(i).intValue());
        return before.equals(after);
    }

    static String notice() {
        return "\n系統內建 " + OPERATION + "：核對 JSON 檔案只新增指定陣列項目，既有內容與陣列順序不變。"
                + "測試情境的 operation_ids 引用它，" + FIELD
                + " 填 [{path,array_pointer,identity_key,additions}]。例如 array_pointer=/items、identity_key=name、additions=[\"新增項目\"]。"
                + "只列需求明確允許的新增識別值，每個值須恰好出現一次；其他情境填空陣列。"
                + "系統使用原任務的實作前 Git 基準，承接時沿承接鏈查找，模型不可提供 commit 或指令。"
                + "驗收直接核對系統獨立驗證收據的 jsonAdditionsComparison，不需自行執行 Git。"
                + "新增項目的內容另以計畫原有操作驗收。";
    }
}
