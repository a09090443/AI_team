package com.zipe.aiteam.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** 對外只輸出遮蔽後的診斷；原始 log 仍留在本機供授權查核。 */
public final class Diagnostics {
    private Diagnostics() {}

    public static String safe(String value) {
        return safe(value, System.getenv());
    }

    static String safe(String value, Map<String, String> env) {
        if (value == null) return "（未提供）";
        String result = value;
        for (var entry : env.entrySet()) {
            if (entry.getKey().matches("(?i).*(TOKEN|SECRET|PASSWORD|API_KEY).*")
                    && entry.getValue() != null && entry.getValue().length() >= 6) {
                result = result.replace(entry.getValue(), "[REDACTED]");
            }
        }
        result = result.replaceAll("(?i)(Bearer\\s+)[^\\s\"']+", "$1[REDACTED]");
        result = result.replaceAll("(?:github_pat_|gh[pousr]_|xox[baprs]-|sk-)[A-Za-z0-9_-]+", "[REDACTED]");
        result = result.replaceAll("(?i)((?:[A-Z_]*(?:TOKEN|PASSWORD|SECRET|API_KEY)|authorization)\\s*[\"']?\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;}]+)", "$1[REDACTED]");
        result = result.replaceAll("(?i)(--(?:password|token|api-key|secret)\\s+)(?:\"[^\"]*\"|'[^']*'|[^\\s]+)", "$1[REDACTED]");
        result = result.replaceAll("(https?://)[^/\\s:@]+:[^@\\s/]+@", "$1[REDACTED]@");
        return result;
    }

    public static String clip(String text, int max) {
        String safe = safe(text);
        return safe.length() <= max ? safe : safe.substring(0, max) + "…（完整證據見本機紀錄）";
    }

    /** 底層先顯示，避免外層訊息與截斷掩蓋真正的系統錯誤。 */
    public static String describe(Throwable error) {
        var causes = new ArrayList<String>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable e = error; e != null && seen.add(e) && causes.size() < 8; e = e.getCause()) {
            causes.add(e.getClass().getSimpleName() + ": " + clip(e.getMessage(), 1600));
        }
        Collections.reverse(causes);
        return String.join("\n呼叫脈絡：", causes);
    }

    public static String toolFailures(com.fasterxml.jackson.databind.JsonNode failures) {
        if (failures == null || !failures.isArray() || failures.isEmpty()) return "";
        StringBuilder detail = new StringBuilder();
        int shown = 0;
        for (var f : failures) {
            if (shown++ == 5) {
                detail.append("\n其餘 ").append(failures.size() - 5).append(" 筆見完整事件紀錄。");
                break;
            }
            var input = f.path("tool_input");
            detail.append("\n工具：").append(clip(f.path("tool_name").asText("未提供"), 80))
                  .append("；呼叫 ID：").append(clip(f.path("tool_use_id").asText("未提供"), 100))
                  .append("\n操作：").append(clip(input.path("command").asText(
                          input.path("file_path").asText("runtime 未提供操作內容")), 350))
                  .append("\n觀察錯誤：").append(clip(f.path("reason").asText(
                          "runtime 回報工具遭拒，未提供具體拒絕理由；不可據此判定是哪層權限"), 450));
        }
        return detail.toString();
    }
}
