package com.zipe.aiteam.flow;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.assertj.core.api.Assertions.assertThat;

/** #1014／#1016：52 次停止裡有 15 次是系統自己沒跑起來，每次都要人按一下才會再試。 */
class SystemFailureTest {

    @Test void recognisesFailuresThatMeanTheSystemNeverRan() {
        assertThat(SystemFailure.transientFailure(new IOException(
                "Cannot run program \"systemd-run\": Exec failed, error: 7 (Argument list too long)"))).isTrue();
        assertThat(SystemFailure.transientFailure(new IllegalStateException(
                "No conversation found with session ID: 8c834c09"))).isTrue();
        assertThat(SystemFailure.transientFailure(new IOException("slack.com: Temporary failure in name resolution"))).isTrue();
    }

    @Test void looksThroughTheCauseChain() {
        var wrapped = new IllegalStateException("無法執行 Claude Code",
                new IOException("Cannot run program \"systemd-run\""));
        assertThat(SystemFailure.transientFailure(wrapped)).isTrue();
    }

    @Test void workProblemsAreNotTreatedAsSystemFailures() {
        // 這些是工作本身的問題，必須照原本的方式交給人，不能自動重跑掩蓋掉。
        assertThat(SystemFailure.transientFailure(new SecurityException("限寫階段越權，禁止下游與發布：src/main/x.java"))).isFalse();
        assertThat(SystemFailure.transientFailure(new IllegalStateException("節點 verification 缺少有效前置契約 implementation"))).isFalse();
        assertThat(SystemFailure.transientFailure(new IllegalStateException("Claude Code 回報 error_max_turns"))).isFalse();
        assertThat(SystemFailure.transientFailure(new IllegalStateException((String) null))).isFalse();
    }
}
