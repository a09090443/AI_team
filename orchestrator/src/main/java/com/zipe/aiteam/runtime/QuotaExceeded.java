package com.zipe.aiteam.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;

/** 只辨識供應商錯誤欄位，不掃描工具輸出、prompt 或一般 429。 */
public final class QuotaExceeded extends AgentRuntime.RuntimeExecutionException {
    public QuotaExceeded(String message) { super(message); }

    public static boolean explicit(String message) {
        // Codex CLI（0.156.1 實查原始碼）寫的是彎引號「You’ve」，統一成直引號再比對，否則額度停止會被當成一般失敗。
        String s = message.toLowerCase(Locale.ROOT).replace('’', '\'');
        return s.contains("usage_limit_reached") || s.contains("insufficient_quota")
                || s.contains("usage limit reached") || s.contains("usage limit has been reached")
                || s.contains("you've hit your usage limit") || s.contains("you have hit your usage limit")
                || s.contains("you've hit your limit") || s.contains("you have exceeded your current quota")
                || s.contains("you've hit your session limit") || s.contains("you have hit your session limit")
                || s.contains("已達用量上限") || s.contains("使用量已達上限");
    }

    static void check(String runtime, String output, String evidence) throws QuotaExceeded {
        ObjectMapper mapper = new ObjectMapper();
        if ("claude-code".equals(runtime)) {
            try {
                JsonNode r = mapper.readTree(output);
                if (r != null && r.path("is_error").asBoolean(false)) {
                    inspect(r.path("result").asText(), evidence);
                    inspect(r.path("errors").toString(), evidence);
                }
            } catch (java.io.IOException ignored) { /* 無法解析不能推論成額度耗盡 */ }
        } else {
            for (String line : output.split("\n")) {
                try {
                    JsonNode e = mapper.readTree(line);
                    if (e == null) continue;
                    switch (e.path("type").asText()) {
                        case "error" -> inspect(e.toString(), evidence);
                        case "turn.failed" -> inspect(e.path("error").toString(), evidence);
                        case "item.completed" -> {
                            if ("error".equals(e.path("item").path("type").asText()))
                                inspect(e.path("item").toString(), evidence);
                        }
                        default -> { }
                    }
                } catch (java.io.IOException ignored) { }
            }
        }
    }

    private static void inspect(String error, String evidence) throws QuotaExceeded {
        if (explicit(error)) throw new QuotaExceeded("供應商明確回報額度耗盡："
                + Diagnostics.clip(error, 1200) + "\n證據：" + evidence
                + "\n重置時間：以上供應商原始錯誤為準；自動續跑排程將另外回報。");
    }

    public static QuotaExceeded find(Throwable error) {
        for (int i = 0; error != null && i < 10; i++, error = error.getCause())
            if (error instanceof QuotaExceeded q) return q;
        return null;
    }
}
