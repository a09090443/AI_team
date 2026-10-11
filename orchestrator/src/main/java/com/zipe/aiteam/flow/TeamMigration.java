package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 僅遷移執行能力設定與同一 runtime 內的角色模型；runtime、路由、角色、資產與需求政策變更須另建任務。 */
public final class TeamMigration {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public record Preview(String token, String from, String to, List<String> changes) {}
    public static Preview preview(TeamDefinition.Snapshot before, TeamDefinition.Snapshot after) {
        TeamCatalog.validate(after.definition());
        if (!before.repository().equals(after.repository()) || !before.assets().equals(after.assets()))
            throw new IllegalArgumentException("repository 或提示／schema 資產已變更，不能原地遷移；請另建任務");
        ObjectNode left = MAPPER.valueToTree(before.definition()), right = MAPPER.valueToTree(after.definition());
        List<String> changes = new ArrayList<>();
        for (String field : List.of("requiredTools", "cachePaths", "buildInstructions", "testInstructions", "operations", "serviceTarget")) {
            compareRemove((ObjectNode)left.get("project"), (ObjectNode)right.get("project"), field, "project.", changes);
        }
        if (!before.definition().roles().keySet().equals(after.definition().roles().keySet()))
            throw new IllegalArgumentException("角色清單已變更，不能原地遷移");
        for (String role : before.definition().roles().keySet()) {
            compareRemove((ObjectNode)left.path("roles").get(role), (ObjectNode)right.path("roles").get(role), "tools", "roles." + role + ".", changes);
            // 同一 runtime 內換模型（例如 sonnet → opus）只影響之後的派工：套用時 activeNode 清空，
            // 下一步開新對話，不會以新模型 --resume 舊模型的 session。runtime 仍在下方整體比對，換引擎照樣拒絕。
            // #1017 續跑時才發現 Backend 還是舊快照的 sonnet，而這是唯一的差異，卻只能另建任務。
            compareRemove((ObjectNode)left.path("roles").get(role), (ObjectNode)right.path("roles").get(role), "model", "roles." + role + ".", changes);
        }
        if (!before.definition().nodes().keySet().equals(after.definition().nodes().keySet()))
            throw new IllegalArgumentException("節點清單已變更，不能原地遷移");
        for (String node : before.definition().nodes().keySet()) {
            for (String field : List.of("writablePaths", "localTestNetwork"))
                compareRemove((ObjectNode)left.path("nodes").get(node), (ObjectNode)right.path("nodes").get(node), field, "nodes." + node + ".", changes);
        }
        if (!left.equals(right)) throw new IllegalArgumentException("含 runtime、節點、基準分支、核准政策或其他非能力設定變更；請另建任務");
        var token = MAPPER.createObjectNode().put("from", before.revision()).put("to", after.revision());
        token.set("target", MAPPER.valueToTree(after));
        return new Preview(Traceability.digest(token), before.revision(), after.revision(), List.copyOf(changes));
    }
    private static void compareRemove(ObjectNode left, ObjectNode right, String field, String prefix, List<String> changes) {
        JsonNode a = left.remove(field), b = right.remove(field);
        if (!Objects.equals(a, b)) changes.add(prefix + field + "：" + a + " → " + b);
    }
    public static void apply(FlowRun flow, TeamDefinition.Snapshot target, String token) {
        Preview preview = preview(flow.snapshot, target);
        if (!preview.token().equals(token) || preview.changes().isEmpty()) throw new IllegalArgumentException("遷移版本過期或沒有差異，請重新預覽");
        flow.migrationHistory.add(flow.snapshot); // 保留完整舊快照，不覆蓋歷史。
        flow.snapshot = target;
        flow.activeNode = null; // 不沿用舊權限／cwd session。
        flow.independentEvidence = null; flow.approvalHead = null;
        flow.planApprovals.clear(); flow.executionContexts.clear();
        flow.recoveryGrants.clear(); flow.remediationRecoveryGrants.clear();
        // 延長授權綁定舊配置與當時 blocker；不可跨配置沿用，但永久 key／事件歷史仍保留。
        flow.automaticHandoffExtraBudget = 0;
        AutomaticHandoff.clear(flow);
        for (var entry : target.definition().nodes().entrySet()) {
            if (entry.getValue().gate() == TeamDefinition.Gate.VERIFICATION || entry.getValue().gate() == TeamDefinition.Gate.REVIEW) {
                flow.receipts.remove(entry.getKey()); flow.heads.remove(entry.getKey());
            }
        }
        flow.context += "\n管理者核准執行能力配置遷移 " + preview.from() + " → " + preview.to()
                + "；既有工作區／需求／計畫保留，先重新派工前檢查；舊執行與核准證據不套用新版本。";
    }
}
