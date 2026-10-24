package com.zipe.aiteam.runtime;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProviderAccessDeniedTest {
    /** #1042（2026-10-14）triage.stdout.json 的實際內容（只保留判定用欄位）。 */
    static final String ORG_DISABLED = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"terminal_reason\":\"api_error\","
            + "\"api_error_status\":403,\"api_error_code\":\"oauth_not_allowed_for_organization\","
            + "\"result\":\"Your organization has disabled Claude subscription access for Claude Code · Use an Anthropic API key instead, or ask your admin to enable access\"}";

    @Test void claude組織停用訂閱存取辨識為帳號問題() {
        assertThatThrownBy(() -> ProviderAccessDenied.check("claude-code", ORG_DISABLED, "log"))
                .isInstanceOfSatisfying(ProviderAccessDenied.class, e -> {
                    assertThat(e.kind()).isEqualTo(ProviderAccessDenied.Kind.ORGANIZATION);
                    assertThat(e.runtime()).isEqualTo("claude-code");
                    assertThat(e.getMessage()).contains("oauth_not_allowed_for_organization").contains("證據：log");
                });
    }

    @Test void claude未登入與登入過期辨識為登入問題() {
        for (String output : new String[]{
                "{\"is_error\":true,\"result\":\"Not logged in · Please run /login\"}",
                "{\"is_error\":true,\"result\":\"OAuth token revoked · Please run /login\"}",
                "{\"is_error\":true,\"api_error_status\":401,\"result\":\"Failed to authenticate\"}",
                "{\"is_error\":true,\"api_error_code\":\"token_expired\",\"result\":\"x\"}"}) {
            assertThatThrownBy(() -> ProviderAccessDenied.check("claude-code", output, "log"))
                    .isInstanceOfSatisfying(ProviderAccessDenied.class, e -> assertThat(e.kind()).isEqualTo(ProviderAccessDenied.Kind.LOGIN));
        }
    }

    @Test void codexRefreshToken失效辨識為登入問題() {
        assertThatThrownBy(() -> ProviderAccessDenied.check("codex",
                "{\"type\":\"turn.failed\",\"error\":{\"message\":\"Your access token could not be refreshed because your refresh token has expired. Please log out and sign in again.\"}}", "log"))
                .isInstanceOfSatisfying(ProviderAccessDenied.class, e -> assertThat(e.kind()).isEqualTo(ProviderAccessDenied.Kind.LOGIN));
    }

    @Test void 工具輸出與成功結果不能冒充帳號問題() {
        assertThatCode(() -> ProviderAccessDenied.check("claude-code",
                "{\"is_error\":false,\"result\":\"文件寫著 Not logged in · Please run /login\"}", "log")).doesNotThrowAnyException();
        assertThatCode(() -> ProviderAccessDenied.check("codex", """
            {"type":"item.completed","item":{"type":"command_execution","aggregated_output":"Not logged in · Please run /login"}}
            {"type":"item.completed","item":{"type":"agent_message","text":"Your access token could not be refreshed"}}
            """, "log")).doesNotThrowAnyException();
    }

    @Test void 沒有已知代碼的403與額度錯誤不算帳號問題() {
        assertThatCode(() -> ProviderAccessDenied.check("claude-code",
                "{\"is_error\":true,\"api_error_status\":403,\"result\":\"Request not allowed\"}", "log")).doesNotThrowAnyException();
        assertThatCode(() -> ProviderAccessDenied.check("claude-code",
                "{\"is_error\":true,\"api_error_status\":429,\"result\":\"You've hit your session limit\"}", "log")).doesNotThrowAnyException();
        assertThatCode(() -> ProviderAccessDenied.check("codex",
                "{\"type\":\"error\",\"message\":\"401 Unauthorized\"}", "log")).doesNotThrowAnyException();
    }

    @Test void 使用者說明是中文且不含英文原文或路徑() {
        var denied = new ProviderAccessDenied(ProviderAccessDenied.Kind.ORGANIZATION, "claude-code",
                "供應商拒絕存取：Your organization has disabled Claude subscription access /home/agent/logs/1042/triage.stdout.json");
        String text = denied.forUser("任務在「分析」這一步執行時");
        assertThat(text).startsWith("*AI 服務帳號無法使用*").contains("發生位置：任務在「分析」這一步執行時")
                .contains("組織停用了 Claude 的使用權限").contains("系統管理者").contains("系統也不會自動重試")
                .doesNotContain("Your organization").doesNotContain("/home/agent").doesNotContain("Exception");
        assertThat(new ProviderAccessDenied(ProviderAccessDenied.Kind.LOGIN, "codex", "x").forUser(""))
                .contains("重新登入 Codex").doesNotContain("發生位置");
    }

    @Test void 包在其他例外裡也找得到() {
        var denied = new ProviderAccessDenied(ProviderAccessDenied.Kind.LOGIN, "codex", "x");
        assertThat(ProviderAccessDenied.find(new RuntimeException("wrap", denied))).isSameAs(denied);
        assertThat(ProviderAccessDenied.find(new RuntimeException("other"))).isNull();
    }
}
