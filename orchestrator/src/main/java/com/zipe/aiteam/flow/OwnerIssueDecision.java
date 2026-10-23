package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.zipe.aiteam.flow.TeamDefinition.*;

/**
 * 驗收負責人直接裁定待決（DECISION）問題移出本任務範圍，不回需求階段整輪重跑（2026-10-23，#1055）。
 *
 * #1055 驗收 8 項全數 PASS，只因一個「部署後網址能否打開」的待決問題擋住審查與發布。負責人選了「移到後續任務」，
 * 但這個選擇只能借道「核准需求變更」回需求階段整輪重跑；換手次數早已用完，1 秒內又停回等待真人，沒有任何 agent 啟動。
 *
 * 這裡不擴權：只有驗收負責人可以裁定（與核准需求變更同一個人），授權碼綁定配置、問題清單版本、問題與 HEAD，用過即失效。
 * 只在受阻的驗收／審查報告「除了待決問題、問題清單版本與未核實事項之外都通過，而且仍是同一個乾淨 commit」時提供：
 * - {@link Mode#REUSE}：報告直接通過，沿用並接著跑下一個節點；
 * - {@link Mode#WAIVE}：只剩未核實事項，不重跑，留給負責人用「開 PR」逐項放行（OwnerPublish）；
 * - {@link Mode#RERUN}：驗收後問題清單又被改過、無法證明是同一份，在同一個 commit 只重跑這個節點。
 * 裁定後往前走的固定步數（沿 next 走到 PR 核准）給一次有界額度，任何節點受阻就收回，不重設任何歷史計數。
 */
final class OwnerIssueDecision {
    private OwnerIssueDecision() {}

    static final String COMMAND = "負責人裁定問題";
    static final String EXCLUDE = "排除";
    /** systemRetries 的鍵：裁定後往前走的換手上限，以及這條路徑上的節點。 */
    static final String FORWARD_CAP = "owner-forward-cap";
    static final String FORWARD_PATH = "owner-forward-path:";

    enum Mode { REUSE, WAIVE, RERUN }
    /** node：受阻的驗收／審查節點；issues：目前全部待決問題，要逐一裁定。 */
    record Candidate(String node, List<String> issues, Mode mode) {}
    /** proceed 為 false 表示還有其他待決問題；mode 為 WAIVE 時不排入執行，等負責人「開 PR」。 */
    record Applied(String node, boolean proceed, Mode mode) {}

    static Candidate candidate(FlowRun flow, String head, boolean clean) {
        if (flow == null || flow.snapshot == null || flow.dialogue || flow.successor != null
                || !IssueTracker.enabled(flow) || head == null || !clean) return null;
        List<String> decisions = flow.issueInventory.entrySet().stream()
                .filter(e -> "DECISION".equals(e.getValue().path("scope").asText())).map(Map.Entry::getKey).toList();
        if (decisions.isEmpty()) return null;
        for (var entry : flow.snapshot.definition().nodes().entrySet()) {
            String id = entry.getKey();
            Node node = entry.getValue();
            if (node.gate() != Gate.VERIFICATION && node.gate() != Gate.REVIEW) continue;
            if (node.next() == null) continue;
            JsonNode report = flow.blockedFindings.get(id);
            if (report == null || !report.isObject() || !head.equals(flow.attemptHeads.get(id))) continue;
            if (FlowGates.blockingReason(node, flow.receipts.get(id), flow) == null) continue;
            Mode mode = trial(flow, node, (ObjectNode) report, decisions);
            if (mode != null) return new Candidate(id, decisions, mode);
        }
        return null;
    }

    /** 試算待決問題全部移出範圍後的結果；還有其他沒通過的地方回 null。不論結果都還原。 */
    private static Mode trial(FlowRun flow, Node node, ObjectNode report, List<String> decisions) {
        Map<String, JsonNode> inventory = new LinkedHashMap<>(flow.issueInventory);
        Map<String, JsonNode> evidence = new LinkedHashMap<>(flow.issueEvidence);
        try {
            for (String id : decisions) IssueTracker.resolveByOwner(flow, id, "trial", "trial");
            if (FlowGates.blockingReason(node, report, flow) == null) return Mode.REUSE;
            ObjectNode withoutUncertainties = report.deepCopy();
            withoutUncertainties.putArray("uncertainties");
            if (FlowGates.blockingReason(node, withoutUncertainties, flow) == null) return Mode.WAIVE;
            withoutUncertainties.put("issue_revision", IssueTracker.revision(flow));
            return FlowGates.blockingReason(node, withoutUncertainties, flow) == null ? Mode.RERUN : null;
        } finally {
            flow.issueInventory.clear(); flow.issueInventory.putAll(inventory);
            flow.issueEvidence.clear(); flow.issueEvidence.putAll(evidence);
        }
    }

    static String token(FlowRun flow, String issueId, String head) {
        return Traceability.digest(JsonNodeFactory.instance.textNode("owner-issue\n" + flow.snapshot.revision()
                + "\n" + IssueTracker.revision(flow) + "\n" + issueId + "\n" + head));
    }

    static Applied apply(FlowRun flow, String issueId, String suppliedToken, String user, String head, boolean clean, String at) {
        if (user == null || !user.equals(flow.acceptanceOwner))
            throw new IllegalArgumentException("只有這個任務的需求負責人 <@" + flow.acceptanceOwner + "> 可以裁定問題範圍，剛才的內容沒有生效。");
        Candidate candidate = candidate(flow, head, clean);
        if (candidate == null || !candidate.issues().contains(issueId))
            throw new IllegalArgumentException("目前沒有只差這個待決問題的驗收結果可以沿用（commit 或工作區已改變，或還有其他未通過的項目），"
                    + "剛才的內容沒有生效。請按「重新整理選項」看目前可用的做法。");
        if (!token(flow, issueId, head).equals(suppliedToken))
            throw new IllegalArgumentException("裁定授權與目前的問題清單或 commit 不符（可能已經處理過），請使用最新的選項。");
        IssueTracker.resolveByOwner(flow, issueId, user, at);
        // 需求基準沒有改變：取代尚未執行的需求變更核准，避免又回需求階段整輪重跑。
        flow.changeTarget = null;
        flow.changeAuthorized = false;
        flow.issueScopeChangeAuthorized = false;
        if (candidate.issues().stream().anyMatch(id -> "DECISION".equals(flow.issueInventory.get(id).path("scope").asText())))
            return new Applied(candidate.node(), false, candidate.mode());
        Node node = flow.snapshot.definition().nodes().get(candidate.node());
        flow.activeNode = null;
        AutomaticHandoff.clear(flow);
        switch (candidate.mode()) {
            case REUSE -> {
                flow.receipts.put(candidate.node(), flow.blockedFindings.remove(candidate.node()));
                flow.heads.put(candidate.node(), head);
                flow.node = node.next();
                grantForward(flow, flow.node);
            }
            case RERUN -> {
                flow.node = candidate.node();
                grantForward(flow, flow.node);
            }
            case WAIVE -> { }
        }
        return new Applied(candidate.node(), true, candidate.mode());
    }

    /** 從 start 沿 next 走到第一個非 agent 節點（通常是 PR 核准），路上每個 agent 節點給一次換手額度。 */
    static void grantForward(FlowRun flow, String start) {
        clearForward(flow);
        Map<String, Node> nodes = flow.snapshot.definition().nodes();
        int steps = 0;
        Set<String> seen = new HashSet<>();
        for (String id = start; id != null && seen.add(id); ) {
            Node node = nodes.get(id);
            if (node == null || node.kind() != Kind.AGENT) break;
            flow.systemRetries.put(FORWARD_PATH + id, 1);
            steps++;
            id = node.next();
        }
        if (steps > 0) flow.systemRetries.put(FORWARD_CAP, flow.transitions + steps);
    }

    /** 目前節點在裁定後的前進路徑上，而且額度還沒用完。 */
    static boolean forwardAllowed(FlowRun flow) {
        Integer cap = flow.systemRetries.get(FORWARD_CAP);
        return cap != null && flow.transitions < cap && flow.systemRetries.containsKey(FORWARD_PATH + flow.node);
    }

    static void clearForward(FlowRun flow) {
        flow.systemRetries.keySet().removeIf(key -> key.equals(FORWARD_CAP) || key.startsWith(FORWARD_PATH));
    }
}
