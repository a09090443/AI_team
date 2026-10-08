package com.zipe.aiteam.flow;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.assertj.core.api.Assertions.assertThat;

/** #1014／#1016：52 次停止裡有 15 次是系統自己沒跑起來，每次都要人按一下才會再試。 */
class SystemFailureTest {

    @Test void recognisesFailuresThatMeanTheSystemNeverRan() {
        assertThat(SystemFailure.transientFailure(new IOException(
                "Cannot run program \"systemd-run\": error=11, Resource temporarily unavailable"))).isTrue();
        assertThat(SystemFailure.transientFailure(new IllegalStateException(
                "No conversation found with session ID: 8c834c09"))).isTrue();
        assertThat(SystemFailure.transientFailure(new IOException("slack.com: Temporary failure in name resolution"))).isTrue();
    }

    @Test void droppedStreamToTheModelServiceIsRetriedAutomatically() {
        // #1019：Codex 串流被服務端切斷，CLI 重連 5 次後結束碼 1；兩次都停給人，手動續跑即成功。
        assertThat(SystemFailure.transientFailure(new com.zipe.aiteam.runtime.AgentRuntime.RuntimeExecutionException(
                "Codex 結束碼 1（Reconnecting... 2/5 (stream disconnected before completion: "
                        + "websocket closed by server before response.completed)"))).isTrue();
    }

    @Test void looksThroughTheCauseChain() {
        var wrapped = new IllegalStateException("無法執行 Claude Code",
                new IOException("Cannot run program \"systemd-run\""));
        assertThat(SystemFailure.transientFailure(wrapped)).isTrue();
    }

    @Test void argumentListTooLongIsNeverRetried() {
        // #1029：參數過長每次必定重現，卻因訊息提到 systemd-run 被重試兩次，每次先重跑約 25 分鐘的獨立驗證。
        var e2big = new IOException("Cannot run program \"systemd-run\": Exec failed, error: 7 (Argument list too long)");
        assertThat(SystemFailure.transientFailure(e2big)).isFalse();
        assertThat(SystemFailure.transientFailure(new IllegalStateException("無法執行 Codex",
                new IllegalStateException("包裝", e2big)))).isFalse();
    }

    @Test void workProblemsAreNotTreatedAsSystemFailures() {
        // 這些是工作本身的問題，必須照原本的方式交給人，不能自動重跑掩蓋掉。
        assertThat(SystemFailure.transientFailure(new SecurityException("限寫階段越權，禁止下游與發布：src/main/x.java"))).isFalse();
        assertThat(SystemFailure.transientFailure(new IllegalStateException("節點 verification 缺少有效前置契約 implementation"))).isFalse();
        assertThat(SystemFailure.transientFailure(new IllegalStateException("Claude Code 回報 error_max_turns"))).isFalse();
        assertThat(SystemFailure.transientFailure(new IllegalStateException((String) null))).isFalse();
    }
}
