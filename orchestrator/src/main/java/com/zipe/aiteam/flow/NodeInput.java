package com.zipe.aiteam.flow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 節點這次啟動時「看到的輸入」指紋。
 *
 * 防循環上限原本是「跑過幾次」，但正常的返工鏈（分流→實作→驗收→審查）每一站都在前進，
 * 卻同樣被計入次數，任務因此在還有事情可做時就被擋下。這裡改成只計算輸入完全相同的重跑：
 * 工作區 commit、前置節點的結果、以及目前的受阻證據任一改變，就代表這次啟動有新的依據。
 */
final class NodeInput {
    private NodeInput() {}

    static String fingerprint(FlowRun flow, TeamDefinition.Node node, String head) {
        StringBuilder raw = new StringBuilder(flow.node).append('\n')
                .append(flow.snapshot.revision()).append('\n')
                .append(head == null ? "" : head).append('\n')
                .append(flow.baselineRevision == null ? "" : flow.baselineRevision).append('\n')
                .append("supplements=").append(flow.supplements).append('\n')
                .append(flow.lastBlockReason == null ? "" : flow.lastBlockReason);
        if (node.requires() != null) for (String dependency : node.requires()) {
            raw.append('\n').append(dependency).append('=')
               .append(flow.receipts.get(dependency) == null ? "" : Traceability.digest(flow.receipts.get(dependency)));
        }
        if (node.plan() != null && flow.receipts.get(node.plan()) != null)
            raw.append("\nplan=").append(Traceability.digest(flow.receipts.get(node.plan())));
        if (flow.blockedFindings != null) flow.blockedFindings.forEach((id, report) ->
                raw.append('\n').append(id).append("!=").append(Traceability.digest(report)));
        return digest(raw.toString());
    }

    /**
     * 這次啟動的輸入和上次完全相同嗎。
     * 沒有留下上次的指紋時一律視為相同：舊快照沒有這個欄位，而 repeats 同樣是 0，
     * 不會因此誤擋；反之若放寬成「沒有指紋就算新輸入」，防循環保護會被一次繞過。
     */
    static boolean repeated(FlowRun flow, String fingerprint) {
        String last = flow.nodeFingerprints.get(flow.node);
        return last == null || last.equals(fingerprint);
    }

    /**
     * 記錄本次輸入並回傳「這是第幾次以完全相同的輸入啟動」。
     * 輸入不同就從 1 重新起算；歷史總次數（visits）照常累加，不受影響。
     */
    static int record(FlowRun flow, String fingerprint) {
        boolean same = repeated(flow, fingerprint);
        int repeat = same ? flow.repeats.getOrDefault(flow.node, 0) + 1 : 1;
        flow.nodeFingerprints.put(flow.node, fingerprint);
        flow.repeats.put(flow.node, repeat);
        return repeat;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("不能建立節點輸入指紋", e); }
    }
}
