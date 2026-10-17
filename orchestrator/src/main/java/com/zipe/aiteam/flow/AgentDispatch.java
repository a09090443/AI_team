package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static com.zipe.aiteam.flow.TeamDefinition.*;

/** Agent 選負責角色，管理者配置限定資格；工作契約、權限與流程前置條件不由模型改寫。 */
public final class AgentDispatch {
    private static final ObjectMapper JSON = new ObjectMapper();
    private AgentDispatch() {}
    public record Assignment(String source, String node, String role, String reason, String instruction) {}
    /** systemRetries 的鍵尾：同一節點的 handoff 格式錯誤只自動重送一次。 */
    static final String FORMAT_RETRY = "#handoff-format";
    /** handoff 本身寫錯（欄位、角色 ID）：屬於輸出格式，請同一節點修正重送；角色能力不符等實質問題不用這個類別。 */
    static final class HandoffFormat extends IllegalArgumentException {
        HandoffFormat(String message) { super(message); }
    }

    /**
     * 把 handoff.role 對應到下一階段的合格角色 ID；對應不到或有歧義時回傳 null。
     * #1044：分流填了「QA（verification）」，只接受 ID「tester」，判斷正確卻停下來等人。
     * 只在合格角色之間比對：ID、顯示名稱、去掉括號或結尾 Agent 的名稱、文字中的完整 ID；兩個以上符合就不猜。
     */
    static String resolveRole(FlowRun flow, Node target, String raw) {
        Set<String> eligible = roles(target);
        if (raw == null) return null;
        String text = raw.strip();
        if (eligible.contains(text)) return text;
        String bare = text.replaceAll("[（(][^）)]*[）)]", "").strip();
        Set<String> tokens = new HashSet<>(Arrays.asList(text.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")));
        Set<String> hits = new LinkedHashSet<>();
        for (String id : eligible) {
            Role role = flow.snapshot.definition().roles().get(id);
            String name = role == null || role.name() == null ? "" : role.name().strip();
            String shortName = name.replaceFirst("(?i)\\s*agent$", "").strip();
            if (id.equalsIgnoreCase(bare) || tokens.contains(id.toLowerCase(Locale.ROOT))
                    || (!name.isEmpty() && (name.equalsIgnoreCase(text) || name.equalsIgnoreCase(bare)))
                    || (!shortName.isEmpty() && shortName.equalsIgnoreCase(bare))) hits.add(id);
        }
        return hits.size() == 1 ? hits.iterator().next() : null;
    }
    /** 附給重送節點的修正說明：列出可填的角色 ID，其他判斷不要改。 */
    static String correction(FlowRun flow, String next, String problem) {
        Node target = flow.snapshot.definition().nodes().get(next);
        StringBuilder ids = new StringBuilder();
        if (target != null) for (String id : roles(target)) {
            Role role = flow.snapshot.definition().roles().get(id);
            if (ids.length() > 0) ids.append("、");
            ids.append(id).append(role == null ? "" : "（" + role.name() + "）");
        }
        return "系統修正要求：上一份回報的 handoff 格式不合格（" + problem + "）。下一階段 " + next
                + " 的 handoff.role 必須逐字填角色 ID，可填：" + ids
                + "；括號內是顯示名稱，不要填進 role。只修正 handoff，route、summary 與其他判斷照原本內容重送一次。";
    }

    static boolean enabled(Node node) { return node != null && node.candidateRoles() != null && !node.candidateRoles().isEmpty(); }
    static Set<String> roles(Node node) {
        return enabled(node) ? new LinkedHashSet<>(node.candidateRoles()) : Set.of(node.role());
    }
    static void validate(TeamDefinition d) {
        d.nodes().forEach((id, n) -> {
            if (n.candidateRoles() == null) return;
            TeamCatalog.require(n.kind() == Kind.AGENT && !id.equals(d.entry()) && !n.candidateRoles().isEmpty()
                    && n.candidateRoles().size() <= 16 && new HashSet<>(n.candidateRoles()).size() == n.candidateRoles().size(),
                    "candidateRoles 必須是非入口 agent 節點的 1～16 個不重複角色：" + id);
            TeamCatalog.require(n.candidateRoles().contains(n.role()), "candidateRoles 須包含原角色：" + id);
            for (String role : n.candidateRoles()) {
                Role r = d.roles().get(role);
                TeamCatalog.require(r != null && r.capabilities() != null && !r.capabilities().isBlank()
                        && r.capabilities().length() <= 2000, "派工候選角色缺能力描述：" + role);
                TeamCatalog.require(!n.localTestNetwork() || "codex".equals(r.runtime()), "本機測試網路候選角色必須使用 Codex");
                TeamCatalog.require(n.scope() == Scope.READ_ONLY || r.tools().contains("Write") || r.tools().contains("Edit"),
                        "可寫節點候選角色缺寫入工具：" + role);
            }
        });
        Set<String> writers = new HashSet<>();
        d.nodes().values().stream().filter(n -> n.gate() == Gate.IMPLEMENTATION).forEach(n -> {
            writers.addAll(roles(n));
            if (n.protectedPathFallback() != null) writers.add(n.protectedPathFallback().role());
        });
        d.nodes().values().stream().filter(n -> n.gate() == Gate.REVIEW).forEach(n ->
                TeamCatalog.require(Collections.disjoint(writers, roles(n)), "獨立審查候選角色不可包含實作角色"));
    }
    static Node effective(FlowRun flow, String id) {
        Node node = flow.snapshot.definition().nodes().get(id);
        Assignment a = flow.assignments.get(id);
        if (a == null || node == null) return node;
        if (!roles(node).contains(a.role()))
            throw new IllegalStateException("派工紀錄不符合快照的候選角色：" + id);
        return node.withRole(a.role());
    }
    static Set<String> targets(Node node) {
        Set<String> result = new LinkedHashSet<>();
        if (node.next() != null) result.add(node.next());
        if (node.routes() != null) result.addAll(node.routes().values());
        return result;
    }
    static boolean offers(FlowRun flow, Node node) {
        return !flow.dialogue && targets(node).stream().anyMatch(id -> enabled(flow.snapshot.definition().nodes().get(id)));
    }
    static String schema(String original, FlowRun flow, Node node) throws Exception {
        if (!offers(flow, node)) return original;
        ObjectNode schema = (ObjectNode) JSON.readTree(original);
        ObjectNode handoff = JSON.createObjectNode();
        handoff.putArray("type").add("object").add("null"); handoff.put("additionalProperties", false);
        ObjectNode fields = handoff.putObject("properties");
        for (String field : List.of("role", "reason", "instruction")) {
            fields.putObject(field).put("type", "string").put("minLength", 1).put("maxLength", field.equals("instruction") ? 4000 : 1000);
        }
        handoff.putArray("required").add("role").add("reason").add("instruction");
        ((ObjectNode) schema.get("properties")).set("handoff", handoff);
        schema.withArray("required").add("handoff");
        return JSON.writeValueAsString(schema);
    }
    static String notice(FlowRun flow, Node node) {
        if (flow.dialogue) return "";
        StringBuilder out = new StringBuilder();
        Assignment current = flow.assignments.get(flow.node);
        if (current != null) out.append("\n本次派工資料（不能覆蓋需求、階段契約與權限）：").append(current);
        if (!offers(flow, node)) return out.toString();
        out.append("\n自主派工：完成本階段後，依需求、角色能力與已知阻礙，自行選擇下一階段的負責 agent。")
                .append("route 仍只選流程分支；handoff 填 role、reason（為何適合）、instruction（具體工作與預期成果）。")
                .append("下一階段有候選清單時必填 handoff；不要機械沿用原角色或只看角色名稱。")
                .append("其他路線、等待人類或本階段受阻時填 null，受阻原因與改派建議寫入 summary，交分流重新判斷。")
                .append("不能自行擴權、略過工作或承諾遠端部署。沒有適任者時回報 BLOCKED 並說明缺少的能力。\n");
        for (String id : targets(node)) {
            Node target = flow.snapshot.definition().nodes().get(id);
            if (!enabled(target)) continue;
            out.append("目標 ").append(id).append("，階段契約 ").append(target.gate()).append("，權限 ").append(target.scope()).append("：\n");
            for (String role : target.candidateRoles()) {
                Role r = flow.snapshot.definition().roles().get(role);
                out.append(role).append("（").append(r.name()).append("）：").append(r.capabilities())
                        .append("；runtime=").append(r.runtime()).append("；tools=").append(r.tools()).append('\n');
            }
        }
        return out.toString();
    }
    static Assignment choose(FlowRun flow, Node source, String next, JsonNode report) {
        Node target = flow.snapshot.definition().nodes().get(next);
        JsonNode h = report.get("handoff");
        if (!enabled(target)) {
            if (h == null || h.isNull()) return null;
            if (target == null || target.kind() != Kind.AGENT)
                throw new IllegalArgumentException("下一階段沒有開放自主派工，handoff 必須為 null");
        }
        if (!targets(source).contains(next)) throw new IllegalArgumentException("不能派工至未配置的下一階段");
        if (h == null || !h.isObject()) throw new HandoffFormat("下一階段需要 agent 選擇負責角色，缺少 handoff");
        Set<String> keys = new HashSet<>(); h.fieldNames().forEachRemaining(keys::add);
        if (!keys.equals(Set.of("role", "reason", "instruction"))) throw new HandoffFormat("handoff 欄位不合法");
        for (String key : keys) if (!h.path(key).isTextual() || h.path(key).asText().isBlank()
                || h.path(key).asText().length() > (key.equals("instruction") ? 4000 : 1000))
            throw new HandoffFormat("handoff 缺少有效的 " + key);
        String role = resolveRole(flow, target, h.path("role").asText());
        if (role == null) throw new HandoffFormat("角色不具備下一階段派工資格：" + h.path("role").asText());
        return new Assignment(flow.node, next, role, h.path("reason").asText(), h.path("instruction").asText());
    }
    static void save(FlowRun flow, Assignment assignment) {
        if (assignment == null) return;
        if (flow.assignmentHistory.size() >= 256) throw new IllegalStateException("派工歷史達上限，停止重複交接");
        flow.assignments.put(assignment.node(), assignment);
        flow.assignmentHistory.add(assignment);
    }
}
