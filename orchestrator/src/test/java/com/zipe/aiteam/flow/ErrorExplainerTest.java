package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.runtime.AgentRuntime;
import com.zipe.aiteam.runtime.RuntimeRegistry;
import com.zipe.aiteam.store.AuditLog;
import com.zipe.aiteam.task.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ErrorExplainerTest {
    @TempDir Path tmp;
    AiTeamProperties props = mock(AiTeamProperties.class);
    RuntimeRegistry runtimes = mock(RuntimeRegistry.class);
    AgentRuntime runtime = mock(AgentRuntime.class);
    AuditLog audit = mock(AuditLog.class);
    ErrorExplainer explainer;
    Task task = new Task("1019", "c", "t", "u", "多組 SMTP 容錯");
    Exception error = new AgentRuntime.RuntimeExecutionException(
            "已確認：Claude Code 回報 error_max_turns，結束碼 1；TOKEN=private123");

    @BeforeEach void setup() {
        when(props.homePath()).thenReturn(tmp.resolve("home"));
        when(props.userHome()).thenReturn(tmp.resolve("user"));
        when(props.explainerEnabled()).thenReturn(true);
        when(props.explainerModel()).thenReturn("haiku");
        when(runtimes.require("claude-code")).thenReturn(runtime);
        explainer = new ErrorExplainer(props, runtimes, audit);
    }

    AgentRuntime.RuntimeResult answer(String json) throws Exception {
        return new AgentRuntime.RuntimeResult("s1", "", new ObjectMapper().readTree(json), 0.01, false, tmp.resolve("log"), "haiku", null);
    }

    @Test void plainExplanationUsesNoToolsTheConfiguredModelAndMaskedErrorText() throws Exception {
        AtomicReference<AgentRuntime.RuntimeRequest> sent = new AtomicReference<>();
        when(runtime.execute(any(), any())).thenAnswer(c -> {
            sent.set(c.getArgument(0));
            return answer("{\"what_happened\":\"agent 在限定步數內沒做完\",\"why\":\"目前無法確定原因\","
                    + "\"cause_confirmed\":false,\"impact\":\"任務停下，成果還在\",\"what_you_can_do\":\"按「繼續執行」\"}");
        });

        String text = explainer.explain(task, "任務在「implementation」這一步執行時", error, List.of("繼續執行：現在直接繼續就會開始處理", "告訴它還缺什麼：你寫的內容會進入流程脈絡"));

        assertThat(text).contains("*發生了什麼*\nagent 在限定步數內沒做完").contains("*為什麼*（尚未確認）")
                .contains("*影響*").contains("*你可以怎麼做*\n按「繼續執行」").doesNotContain("Exception");
        var request = sent.get();
        assertThat(request.allowedTools()).isEmpty();
        assertThat(request.model()).isEqualTo("haiku");
        assertThat(request.jsonSchema()).isEqualTo(ErrorExplainer.SCHEMA);
        assertThat(request.prompt()).contains("・繼續執行：現在直接繼續就會開始處理\n・告訴它還缺什麼：").contains("error_max_turns")
                .doesNotContain("private123");
        verify(audit).record(eq("1019"), eq(AuditLog.ACTOR_SYSTEM), eq(AuditLog.ACTION_ERROR), contains("RuntimeExecutionException"));
    }

    @Test void runtimeFailureFallsBackToAFixedPlainSentence() throws Exception {
        when(runtime.execute(any(), any())).thenThrow(new AgentRuntime.RuntimeExecutionException("quota"));

        String text = explainer.explain(task, "情境", error, List.of());

        assertThat(text).startsWith("這一步因系統錯誤停下，自動白話說明暫時無法產生。").contains("error_max_turns")
                .contains("請通知管理者").doesNotContain("Exception").doesNotContain("private123");
    }

    /** #1042：帳號被拒時說明 agent 用同一個帳號必定失敗，改用固定中文說明，不呼叫模型。 */
    @Test void providerAccessDeniedUsesFixedPlainTextWithoutCallingAModel() throws Exception {
        var denied = new com.zipe.aiteam.runtime.ProviderAccessDenied(com.zipe.aiteam.runtime.ProviderAccessDenied.Kind.ORGANIZATION,
                "claude-code", "供應商拒絕存取：Your organization has disabled Claude subscription access");

        String text = explainer.explain(task, "任務在「分析」這一步執行時", new RuntimeException("wrap", denied), List.of());

        assertThat(text).startsWith("*AI 服務帳號無法使用*").contains("系統管理者").doesNotContain("Your organization");
        verify(runtime, never()).execute(any(), any());
        verify(audit).record(eq("1019"), eq(AuditLog.ACTOR_SYSTEM), eq(AuditLog.ACTION_ERROR), contains("Your organization"));
    }

    @Test void explainerOwnAccountFailureDoesNotPasteEnglishOriginal() throws Exception {
        when(runtime.execute(any(), any())).thenThrow(new com.zipe.aiteam.runtime.ProviderAccessDenied(
                com.zipe.aiteam.runtime.ProviderAccessDenied.Kind.LOGIN, "claude-code", "Not logged in · Please run /login"));

        String text = explainer.explain(task, "情境", error, List.of());

        assertThat(text).contains("產生說明用的 AI 服務帳號目前無法使用").contains("請通知管理者")
                .doesNotContain("Not logged in").doesNotContain("error_max_turns");
    }

    @Test void incompleteAnswerFallsBack() throws Exception {
        when(runtime.execute(any(), any())).thenReturn(answer("{\"what_happened\":\"只有一段\"}"));

        assertThat(explainer.explain(task, "情境", error, List.of())).startsWith("這一步因系統錯誤停下");
    }

    @Test void disabledExplainerNeverCallsAModel() throws Exception {
        when(props.explainerEnabled()).thenReturn(false);

        assertThat(explainer.explain(task, "情境", error, List.of())).startsWith("這一步因系統錯誤停下");
        verify(runtime, never()).execute(any(), any());
    }
}
