package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.zipe.aiteam.runtime.Diagnostics;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 角色宣稱查核完成，但它根本沒有能力做那件事時，結論的證據基礎不可信。
 *
 * 只看一件事：被拒的工具是不是**完全不在**該角色的配置裡。
 * - 完全沒有（例如唯讀分流角色沒有 Bash，卻回報 git 查核結果）：它不可能執行過，作廢其結論。
 * - 有這個工具、只是這次的呼叫形式不符白名單（例如 `git -C <路徑> log`、管線、for 迴圈）：
 *   它用其他允許的方式（Read／Grep）做出的判斷仍然成立，只記錄不作廢 ——
 *   否則 agent 每輪都會因為試了一個形式不符的指令而被判失敗，反覆重跑到用完次數（#1016）。
 * 已判定受阻的報告不受影響（合法引用被拒工具作為阻礙原因，例如 npm 權限缺口）。
 */
public final class ToolTrust {
    private ToolTrust() {}

    public static String blockingReason(TeamDefinition.Role role, JsonNode denials) {
        Set<String> missing = classify(role, denials, false);
        if (missing.isEmpty()) return null;
        return "本輪有工具呼叫遭拒，報告卻回報未受阻；不採信其查核結論。"
                + "\n角色 " + role.name() + " 的配置完全沒有這些工具：" + missing
                + "。這是配置與職責不符：要它核實就在配置補上唯讀權限，否則請修改階段指示，明令不得宣稱執行過該工具。"
                + "\n被拒明細：" + Diagnostics.toolFailures(denials);
    }

    /** 有配置但呼叫形式不符白名單：不作廢結論，但要留下紀錄，才看得出白名單樣式與實際用法的落差。 */
    public static String caveat(TeamDefinition.Role role, JsonNode denials) {
        Set<String> mismatched = classify(role, denials, true);
        if (mismatched.isEmpty()) return null;
        return "這些工具有配置，但本次呼叫方式不符白名單樣式：" + mismatched
                + "。本次結論未因此作廢；若該節點確實需要這種呼叫方式，請調整白名單樣式。"
                + "\n被拒明細：" + Diagnostics.toolFailures(denials);
    }

    private static Set<String> classify(TeamDefinition.Role role, JsonNode denials, boolean wantGranted) {
        Set<String> tools = new LinkedHashSet<>();
        if (denials == null || !denials.isArray()) return tools;
        for (JsonNode denial : denials) {
            // Codex 的非零結束碼只是操作失敗，不能用事件名稱推論工具授權。
            if (isCommandFailure(denial)) continue;
            String tool = denial.path("tool_name").asText("");
            if (tool.isBlank()) tool = "（未提供工具名）";
            if (granted(role, tool) == wantGranted) tools.add(tool);
        }
        return tools;
    }

    public static boolean isCommandFailure(JsonNode event) {
        return "COMMAND_FAILURE".equals(event.path("failure_kind").asText())
                && "command_execution".equals(event.path("tool_name").asText());
    }

    /** 白名單可能寫成 "Bash" 或 "Bash(git show *)"；兩者都算這個角色具備該工具。 */
    private static boolean granted(TeamDefinition.Role role, String tool) {
        List<String> tools = role.tools();
        if (tools == null) return false;
        return tools.stream().anyMatch(entry -> entry != null
                && (entry.equals(tool) || entry.startsWith(tool + "(")));
    }
}
