package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 有結構化受阻證據時的有界自動責任交接；不取代任何真人核准。 */
final class AutomaticHandoff {
    private AutomaticHandoff() {}
    static final int LIMIT = 6;
    static final int HOP_LIMIT = 12;
    static final int EXTENSION_SIZE = 2;
    static final int MAX_EXTRA_BUDGET = 6;
    private static final Pattern LEGACY_KEY = Pattern.compile("^([0-9a-f]{64}):[^:]+:[0-9]+$");
    record Claim(String key, boolean newIncident) {}
    record Normalization(int before, int after, boolean changed) {}

    static void begin(FlowRun flow, String sourceNode, JsonNode report, String head) {
        begin(flow, sourceNode, report.toString(), head);
    }

    static void begin(FlowRun flow, String sourceNode, String evidence, String head) {
        String next = digest(sourceNode + "\n" + evidence + "\n" + head + "\n" + flow.snapshot.revision());
        if (!next.equals(flow.automaticHandoff)) flow.automaticHandoffHops = 0;
        flow.automaticHandoff = next;
    }

    /** 舊快照沒有交接欄位；停在 configured blocked target 時可由已保存原因重建。 */
    static void restoreLegacyBlockedTarget(FlowRun flow) {
        if (flow.automaticHandoff == null) flow.automaticHandoff = pendingEvidence(flow);
    }

    /**
     * 不改動狀態，回答「下一次啟動時會用哪一份受阻證據」。
     * 停止訊息判斷能不能繼續時必須和引擎用同一套規則，否則會提供按了也走不下去的按鈕。
     */
    static String pendingEvidence(FlowRun flow) {
        if (flow.automaticHandoff != null) return flow.automaticHandoff;
        if (flow.lastBlockReason == null || flow.lastBlockReason.isBlank()) return null;
        boolean blockedTarget = flow.snapshot.definition().nodes().values().stream()
                .anyMatch(node -> flow.node.equals(node.blocked()));
        return blockedTarget ? digest(flow.node + "\n" + flow.lastBlockReason + "\n" + flow.snapshot.revision()) : null;
    }

    /** 與 claim 相同的判斷，但不改動狀態。 */
    static boolean wouldClaim(FlowRun flow) {
        String evidence = pendingEvidence(flow);
        if (evidence == null || flow.automaticHandoffHops >= HOP_LIMIT) return false;
        boolean fresh = !flow.automaticHandoffKeys.contains("incident:" + evidence);
        return !fresh || flow.automaticHandoffs < effectiveLimit(flow);
    }

    /** 目前卡在事件額度，而且人工延長確實能讓下一次啟動通過。 */
    static boolean extensionWouldHelp(FlowRun flow) {
        String evidence = pendingEvidence(flow);
        return evidence != null && flow.automaticHandoffHops < HOP_LIMIT
                && !flow.automaticHandoffKeys.contains("incident:" + evidence)
                && flow.automaticHandoffs >= effectiveLimit(flow)
                && flow.automaticHandoffExtraBudget < MAX_EXTRA_BUDGET;
    }

    static Claim claim(FlowRun flow) {
        if (flow.automaticHandoff == null || flow.automaticHandoffHops >= HOP_LIMIT) return null;
        String key = "incident:" + flow.automaticHandoff;
        boolean fresh = !flow.automaticHandoffKeys.contains(key);
        if (fresh && flow.automaticHandoffs >= effectiveLimit(flow)) return null;
        if (fresh) {
            flow.automaticHandoffKeys.add(key);
            flow.automaticHandoffs++;
        }
        flow.automaticHandoffHops++;
        return new Claim(key, fresh);
    }

    static void restore(FlowRun flow, Claim claim) {
        if (claim == null) return;
        flow.automaticHandoffHops = Math.max(0, flow.automaticHandoffHops - 1);
        if (claim.newIncident() && flow.automaticHandoffKeys.remove(claim.key()))
            flow.automaticHandoffs = Math.max(0, flow.automaticHandoffs - 1);
    }

    static void clear(FlowRun flow) { flow.automaticHandoff = null; flow.automaticHandoffHops = 0; }

    /**
     * 第一版以「證據:節點:次數」逐跳計額；新版以 incident 逐事件計額。
     * 只有全部 key 都能辨識時才重算，遇到未知格式便保留較高舊值，採 fail closed。
     */
    static Normalization normalizeLegacyKeys(FlowRun flow) {
        int before = flow.automaticHandoffs;
        if (flow.automaticHandoffKeys == null) flow.automaticHandoffKeys = new LinkedHashSet<>();
        Set<String> original = new LinkedHashSet<>(flow.automaticHandoffKeys);
        Set<String> normalized = new LinkedHashSet<>();
        boolean allRecognized = !flow.automaticHandoffKeys.isEmpty()
                && before == flow.automaticHandoffKeys.size();
        for (String key : flow.automaticHandoffKeys) {
            if (key != null && key.startsWith("incident:") && key.length() > "incident:".length()) {
                normalized.add(key);
                continue;
            }
            Matcher legacy = key == null ? null : LEGACY_KEY.matcher(key);
            if (legacy != null && legacy.matches()) normalized.add("incident:" + legacy.group(1));
            else {
                allRecognized = false;
                if (key != null) normalized.add(key);
            }
        }
        flow.automaticHandoffKeys = normalized;
        if (allRecognized) flow.automaticHandoffs = normalized.size();
        else flow.automaticHandoffs = Math.max(before, normalized.size());
        return new Normalization(before, flow.automaticHandoffs,
                before != flow.automaticHandoffs || !original.equals(normalized));
    }

    static int effectiveLimit(FlowRun flow) {
        return LIMIT + Math.max(0, Math.min(flow.automaticHandoffExtraBudget, MAX_EXTRA_BUDGET));
    }

    static String extensionToken(FlowRun flow, String head) {
        if (flow.automaticHandoff == null) return null;
        return digest("extend\n" + flow.snapshot.revision() + "\n" + flow.automaticHandoff + "\n" + String.valueOf(head));
    }

    static void authorizeExtension(FlowRun flow, String suppliedToken, String head) {
        String expected = extensionToken(flow, head);
        if (expected == null || !expected.equals(suppliedToken))
            throw new IllegalArgumentException("延長授權與目前受阻證據、配置或 HEAD 不符，請使用最新停止訊息");
        if (flow.automaticHandoffExtensionKeys == null) flow.automaticHandoffExtensionKeys = new LinkedHashSet<>();
        if (flow.automaticHandoffExtensionKeys.contains(suppliedToken))
            throw new IllegalArgumentException("此自動交接延長授權已使用，不能重放");
        if (flow.automaticHandoffs < effectiveLimit(flow))
            throw new IllegalArgumentException("自動交接事件額度尚未用完，不需要延長");
        if (flow.automaticHandoffExtraBudget >= MAX_EXTRA_BUDGET)
            throw new IllegalArgumentException("人工延長已達硬上限，必須人工檢查流程或建立新任務");
        flow.automaticHandoffExtensionKeys.add(suppliedToken);
        flow.automaticHandoffExtraBudget = Math.min(MAX_EXTRA_BUDGET,
                flow.automaticHandoffExtraBudget + EXTENSION_SIZE);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("不能建立自動交接證據版本", e); }
    }
}
