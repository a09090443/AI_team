package com.zipe.aiteam.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 供應商拒絕這個帳號或登入：未登入、登入過期、組織停用訂閱存取等。
 *
 * 與額度耗盡一樣是「本次沒有任何模型工作發生」，但額度會自己恢復、這個不會，
 * 只能由帳號擁有者處理，所以不排自動續跑，也不呼叫白話說明 agent（它用同一個帳號，必定同因失敗）。
 * #1042（2026-10-14）就是說明 agent 跟著失敗，使用者只拿到英文原文。
 *
 * 只辨識供應商錯誤欄位，不掃描工具輸出、prompt 或 agent 訊息，判定條件來源：
 * - Claude Code 2.1.282：結果 JSON 的 api_error_status／api_error_code 與 CLI 內建的登入錯誤句（實查執行檔字串）
 * - Codex 0.156.1：refresh token 失效與要求重新 codex login 的錯誤句（實查執行檔字串）
 */
public final class ProviderAccessDenied extends AgentRuntime.RuntimeExecutionException {
    public enum Kind { ORGANIZATION, LOGIN }

    /** Claude Code 自己把這些視為帳號／組織層的存取拒絕。 */
    static final Set<String> ORGANIZATION_CODES = Set.of("oauth_not_allowed_for_organization", "organization_disabled",
            "organization_on_hold", "claude_code_key_creator_not_member", "ip_not_in_allowed_range", "oauth_scope_insufficient");
    private static final List<String> ORGANIZATION_PHRASES = List.of(
            "disabled claude subscription access", "organization has been disabled", "does not have access to claude");
    private static final List<String> LOGIN_PHRASES = List.of(
            "not logged in", "please run /login", "oauth token revoked", "invalid api key", "failed to authenticate",
            "your access token could not be refreshed", "your authentication session could not be refreshed",
            "please re-run codex login");

    private final Kind kind;
    private final String runtime;

    public ProviderAccessDenied(Kind kind, String runtime, String message) {
        super(message);
        this.kind = kind; this.runtime = runtime;
    }
    public Kind kind() { return kind; }
    public String runtime() { return runtime; }

    static Kind classify(String message) {
        String s = message.toLowerCase(Locale.ROOT).replace('’', '\'');
        if (ORGANIZATION_PHRASES.stream().anyMatch(s::contains)) return Kind.ORGANIZATION;
        if (LOGIN_PHRASES.stream().anyMatch(s::contains)) return Kind.LOGIN;
        return null;
    }

    static void check(String runtime, String output, String evidence) throws ProviderAccessDenied {
        ObjectMapper mapper = new ObjectMapper();
        if ("claude-code".equals(runtime)) {
            try {
                JsonNode r = mapper.readTree(output);
                if (r == null || !r.path("is_error").asBoolean(false)) return;
                String code = r.path("api_error_code").asText("");
                int status = r.path("api_error_status").asInt(0);
                String result = r.path("result").asText("");
                Kind kind = ORGANIZATION_CODES.contains(code) ? Kind.ORGANIZATION
                        : "token_expired".equals(code) || status == 401 ? Kind.LOGIN : classify(result);
                // 沒有已知代碼或句子的 403 不猜：可能是模型或功能層級的權限，照一般失敗處理。
                if (kind != null) throw denied(kind, runtime, "status=" + status + " code=" + code + " " + result, evidence);
            } catch (java.io.IOException ignored) { /* 無法解析不能推論成帳號問題 */ }
        } else {
            for (String line : output.split("\n")) {
                try {
                    JsonNode e = mapper.readTree(line);
                    if (e == null) continue;
                    String error = switch (e.path("type").asText()) {
                        case "error" -> e.toString();
                        case "turn.failed" -> e.path("error").toString();
                        case "item.completed" -> "error".equals(e.path("item").path("type").asText()) ? e.path("item").toString() : null;
                        default -> null;
                    };
                    Kind kind = error == null ? null : classify(error);
                    if (kind != null) throw denied(kind, runtime, error, evidence);
                } catch (java.io.IOException ignored) { }
            }
        }
    }

    private static ProviderAccessDenied denied(Kind kind, String runtime, String error, String evidence) {
        return new ProviderAccessDenied(kind, runtime, "供應商拒絕存取（" + (kind == Kind.ORGANIZATION ? "帳號或組織權限" : "登入")
                + "）：" + Diagnostics.clip(error, 1200) + "\n證據：" + evidence);
    }

    public static ProviderAccessDenied find(Throwable error) {
        for (int i = 0; error != null && i < 10; i++, error = error.getCause())
            if (error instanceof ProviderAccessDenied d) return d;
        return null;
    }

    /** 給使用者的固定說明：只寫已確認的事實，不依賴任何模型。situation 例如「任務在「分析」這一步執行時」。 */
    public String forUser(String situation) {
        String service = "codex".equals(runtime) ? "Codex" : "opencode".equals(runtime) ? "opencode" : "Claude";
        String why = kind == Kind.ORGANIZATION
                ? "這個帳號所屬的組織停用了 " + service + " 的使用權限（或帳號被停用、暫停），所以每次呼叫都會被拒絕。"
                : "服務主機上的 " + service + " 沒有登入、登入已過期或被撤銷，所以每次呼叫都會被拒絕。";
        return "*AI 服務帳號無法使用*\n"
                + (situation == null || situation.isBlank() ? "" : "發生位置：" + situation + "\n")
                + "系統呼叫 " + service + " 時被拒絕。" + why
                + "這不是任務內容的問題，已完成的工作都還在。"
                + "\n\n*需要誰處理*\n系統管理者（帳號擁有者）："
                + (kind == Kind.ORGANIZATION ? "請組織管理者重新開放權限，或在服務主機上改用可用的帳號登入。"
                                             : "請在服務主機上重新登入 " + service + "。")
                + "\n\n*處理完之後*\n再用原本的選項繼續；帳號恢復前重試一樣會失敗，系統也不會自動重試。"
                + "\n（技術細節已記錄在稽核紀錄。）";
    }
}
