package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.zipe.aiteam.runtime.Diagnostics;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 角色在本輪有工具呼叫被拒，卻回報「未受阻」時，該結論的證據基礎不可信。
 * 這是機械檢查，不解讀報告文字：agent 會呼叫工具就表示它需要該工具；
 * 被拒之後仍宣稱查核完成、可以往下派工，正是證據造假最容易發生的地方。
 * 已判定受阻的報告不受影響（合法引用被拒工具作為阻礙原因，例如 npm 權限缺口）。
 */
public final class ToolTrust {
    private ToolTrust() {}

    public static String blockingReason(TeamDefinition.Role role, JsonNode denials) {
        if (denials == null || !denials.isArray() || denials.isEmpty()) return null;
        Set<String> missing = new LinkedHashSet<>();
        Set<String> mismatched = new LinkedHashSet<>();
        for (JsonNode denial : denials) {
            String tool = denial.path("tool_name").asText("");
            if (tool.isBlank()) tool = "（未提供工具名）";
            if (granted(role, tool)) mismatched.add(tool); else missing.add(tool);
        }
        StringBuilder reason = new StringBuilder("本輪有工具呼叫遭拒，報告卻回報未受阻；不採信其查核結論。");
        if (!missing.isEmpty()) reason.append("\n角色 ").append(role.name()).append(" 的配置完全沒有這些工具：")
                .append(missing).append("。這是配置與職責不符：要它核實就在配置補上唯讀權限，否則請修改階段指示，明令不得宣稱執行過該工具。");
        if (!mismatched.isEmpty()) reason.append("\n這些工具有配置但本次呼叫方式不符白名單：").append(mismatched)
                .append("。請改用白名單允許的呼叫方式，或調整白名單樣式。");
        reason.append("\n被拒明細：").append(Diagnostics.toolFailures(denials));
        return reason.toString();
    }

    /** 白名單可能寫成 "Bash" 或 "Bash(git show *)"；兩者都算這個角色具備該工具。 */
    private static boolean granted(TeamDefinition.Role role, String tool) {
        List<String> tools = role.tools();
        if (tools == null) return false;
        return tools.stream().anyMatch(entry -> entry != null
                && (entry.equals(tool) || entry.startsWith(tool + "(")));
    }
}
