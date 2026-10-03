package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.zipe.aiteam.runtime.AgentRuntime;
import java.util.*;
import java.util.regex.Pattern;
import static com.zipe.aiteam.flow.TeamDefinition.*;

/**
 * 受保護路徑改派：Claude Code 在自動模式一律拒絕寫入 `.claude/` 等路徑（官方 protected paths，權限設定開放不了）。
 * bestpartner #1014 只改 `.claude/rules/api-endpoints.md` 兩個字就停在需求整理。實作節點可配置 fallback：需求要改的
 * 受保護路徑全部落在管理者允許的 paths 內時，改派給另一個 runtime 的角色（Codex）實作。
 *
 * 安全前提：`.claude/settings*.json` 能定義 hooks 與權限，被改寫後同一工作區後續的 Claude Code 角色會載入並可能執行任意指令。
 * 所以行為類路徑寫死禁止（配置無法覆蓋），而且改派的實作跑完後由系統核對實際變更，越界就停止並禁止下游與發布。
 */
public final class ProtectedPathFallback {
    private ProtectedPathFallback() {}

    /** 行為類或版控類路徑：無論配置怎麼寫都不能交給改派的實作者。 */
    static final List<String> DENIED = List.of(
            ".claude/settings", ".claude/hooks", ".claude/commands", ".claude/agents", ".claude/plugins", ".claude.json",
            ".git", ".mcp.json", ".vscode", ".idea", ".github", ".husky", ".devcontainer");

    /** 配置靜態檢查：只能放在實作節點、角色存在且 runtime 不同、paths 為不涵蓋禁止路徑的目錄 glob。 */
    static void validate(TeamDefinition d, String nodeId, Node node) {
        Fallback fb = node.protectedPathFallback();
        if (fb == null) return;
        TeamCatalog.require(node.gate() == Gate.IMPLEMENTATION && node.workspace(), "protectedPathFallback 只能設在實作節點：" + nodeId);
        Role primary = d.roles() == null ? null : d.roles().get(node.role());
        Role alternate = d.roles() == null || fb.role() == null ? null : d.roles().get(fb.role());
        TeamCatalog.require(primary != null && alternate != null, "protectedPathFallback 引用不存在的角色：" + fb.role());
        TeamCatalog.require(!alternate.runtime().equals(primary.runtime()), "protectedPathFallback 的角色必須使用不同 runtime：" + fb.role());
        TeamCatalog.require(fb.paths() != null && !fb.paths().isEmpty() && fb.paths().size() <= 10, "protectedPathFallback.paths 須為 1～10 條");
        for (String pattern : fb.paths()) {
            TeamCatalog.require(pattern != null && pattern.endsWith("/**") && !pattern.startsWith("/") && !pattern.contains("..")
                    && !pattern.contains("\\") && pattern.length() > 3 && !pattern.substring(0, pattern.length() - 3).contains("*"),
                    "protectedPathFallback.paths 須為明確目錄並以 /** 結尾：" + pattern);
            String base = pattern.substring(0, pattern.length() - 3);
            for (String denied : DENIED)
                TeamCatalog.require(!(denied.startsWith(base + "/") || denied.equals(base) || base.startsWith(denied)),
                        "protectedPathFallback.paths 涵蓋禁止交給改派實作者的路徑 " + denied + "：" + pattern);
        }
    }

    /** 從文字擷取像路徑的字樣（含 / 的片段）。 */
    private static final Pattern PATH = Pattern.compile("(?<![A-Za-z0-9_./-])[.A-Za-z0-9_-][A-Za-z0-9_.-]*(?:/[A-Za-z0-9_.-]+)+/?");
    static Set<String> mentionedPaths(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        var m = PATH.matcher(text);
        while (m.find()) {
            String p = m.group();
            while (p.endsWith(".")) p = p.substring(0, p.length() - 1);
            if (p.contains("/") && !p.startsWith("http")) out.add(p);
        }
        return out;
    }

    /**
     * 需求、需求基準與已完成節點報告中提到、而原角色 runtime 必定寫不了的路徑。
     * 全部落在 fallback.paths 內才回傳；有任何一條不在允許範圍（例如 settings）就回傳 empty，交給原流程停下來問人。
     */
    public static Optional<List<String>> needed(FlowRun flow, Node node, String requestText, AgentRuntime primary) {
        Fallback fb = node.protectedPathFallback();
        if (fb == null || primary == null) return Optional.empty();
        StringBuilder text = new StringBuilder(requestText == null ? "" : requestText);
        for (JsonNode receipt : flow.receipts.values()) text.append('\n').append(receipt);
        List<String> protectedPaths = mentionedPaths(text.toString()).stream().filter(primary::isProtectedWrite).toList();
        if (protectedPaths.isEmpty()) return Optional.empty();
        boolean allAllowed = protectedPaths.stream().allMatch(p -> PathRules.allows(fb.paths(), p.endsWith("/") ? p + "x" : p));
        return allAllowed ? Optional.of(protectedPaths) : Optional.empty();
    }

    /** 改派的實作實際變更中，原 runtime 會拒絕、又不在允許範圍內的路徑（含禁止清單）；非空就必須停止。 */
    public static List<String> violations(List<String> changed, Fallback fb, AgentRuntime primary) {
        return changed.stream().filter(path -> denied(path)
                || (primary != null && primary.isProtectedWrite(path) && !PathRules.allows(fb.paths(), path))).toList();
    }
    static boolean denied(String path) {
        return DENIED.stream().anyMatch(d -> path.equals(d) || path.startsWith(d + "/") || path.startsWith(d));
    }

    /** 給改派實作者的界線說明。 */
    static String notice(Fallback fb) {
        return "\n你是因為需求要改原實作角色無法寫入的路徑而被改派。受保護範圍內你只可以修改：" + fb.paths()
                + "；不得修改 " + DENIED + " 之下的任何檔案（特別是 .claude/settings*.json、hooks），系統會在你完成後逐檔核對，越界會作廢本次實作。"
                + "你的沙箱不能寫入 .git：修改完成後不要嘗試 commit，系統核對通過後會代為提交。其他工作照實作規範完成。";
    }
}
