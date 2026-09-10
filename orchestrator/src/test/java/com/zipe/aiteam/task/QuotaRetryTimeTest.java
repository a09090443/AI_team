package com.zipe.aiteam.task;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
class QuotaRetryTimeTest {
    @Test void utc重置加緩衝() {
        assertThat(QuotaRetryTime.next("You've hit your session limit · resets 6:30pm (UTC)", Instant.parse("2026-09-08T14:07:33Z"), 0))
                .isEqualTo(Instant.parse("2026-09-08T18:32:00Z"));
    }
    @Test void 未知時區不猜且退避有上限() {
        Instant now = Instant.parse("2026-09-08T14:00:00Z");
        assertThat(QuotaRetryTime.next("resets 6pm", now, 0)).isEqualTo(now.plusSeconds(1800));
        assertThat(QuotaRetryTime.next("quota", now, 1)).isEqualTo(now.plusSeconds(3600));
        assertThat(QuotaRetryTime.next("quota", now, 100)).isEqualTo(now.plusSeconds(21600));
    }
    @Test void 跨日與無效時間() {
        Instant now = Instant.parse("2026-09-08T23:00:00Z");
        assertThat(QuotaRetryTime.next("resets 12am (UTC)", now, 0)).isEqualTo(Instant.parse("2026-09-09T00:02:00Z"));
        assertThat(QuotaRetryTime.next("resets 99pm (UTC)", now, 0)).isEqualTo(now.plusSeconds(1800));
    }

    private static final String CODEX = "You've hit your usage limit. Upgrade to Pro (https://chatgpt.com/explore/pro), "
            + "visit https://chatgpt.com/codex/settings/usage to purchase more credits "
            + "or try again at Sep 15th, 2026 1:09 PM.";

    @Test void codex解禁時間照原文取出() {
        assertThat(QuotaRetryTime.providerReset(CODEX)).contains("Sep 15th, 2026 1:09 PM");
        assertThat(QuotaRetryTime.providerReset("You've hit your session limit · resets 6:30pm (UTC)")).contains("6:30pm (UTC)");
        assertThat(QuotaRetryTime.providerReset("nothing here")).isEmpty();
    }

    /** 時區未知：最早取 UTC+14、最晚取 UTC−12，不猜中間值。 */
    @Test void 時區未知時只給邊界() {
        assertThat(QuotaRetryTime.earliestPossibleReset(CODEX)).contains(Instant.parse("2026-09-14T23:09:00Z"));
        assertThat(QuotaRetryTime.latestPossibleReset(CODEX)).contains(Instant.parse("2026-09-16T01:09:00Z"));
    }

    /** 解禁日還很遠時不再每 6 小時撞牆，改排在最壞解讀之後試一次。 */
    @Test void 遠期解禁改排單次而非反覆退避() {
        Instant observed = Instant.parse("2026-09-09T15:53:23Z");
        assertThat(QuotaRetryTime.farBeyondBackoff(CODEX, observed)).isTrue();
        assertThat(QuotaRetryTime.next(CODEX, observed, 4)).isEqualTo(Instant.parse("2026-09-16T01:11:00Z"));
        // 已經接近解禁時，維持原本的退避，不把時間反而往後推。
        Instant late = Instant.parse("2026-09-16T00:00:00Z");
        assertThat(QuotaRetryTime.farBeyondBackoff(CODEX, late)).isFalse();
        assertThat(QuotaRetryTime.next(CODEX, late, 0)).isEqualTo(late.plusSeconds(1800));
    }

    /** 只給日期、沒給時刻時當天起算，仍然不猜時區。 */
    @Test void 只有日期也能判斷遠近() {
        assertThat(QuotaRetryTime.earliestPossibleReset("try again at Oct 1st, 2026"))
                .contains(Instant.parse("2026-09-30T10:00:00Z"));
    }
}
