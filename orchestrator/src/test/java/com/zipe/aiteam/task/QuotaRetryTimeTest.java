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

    /** codex-cli 0.156.1 原始碼的措辭（彎引號），不同天帶日期。 */
    private static final String CODEX = "You’ve hit your usage limit. Upgrade to Pro (https://chatgpt.com/explore/pro), "
            + "visit https://chatgpt.com/codex/settings/usage to purchase more credits "
            + "or try again at Sep 15th, 2026 1:09 PM.";

    @Test void codex解禁時間照原文取出() {
        assertThat(QuotaRetryTime.providerReset(CODEX)).contains("Sep 15th, 2026 1:09 PM");
        assertThat(QuotaRetryTime.providerReset("You’ve hit your usage limit. Try again at 1:09 PM.")).contains("1:09 PM");
        assertThat(QuotaRetryTime.providerReset("You've hit your session limit · resets 6:30pm (UTC)")).contains("6:30pm (UTC)");
        assertThat(QuotaRetryTime.providerReset("nothing here")).isEmpty();
        assertThat(QuotaRetryTime.providerReset("please try again at your convenience")).isEmpty();
    }

    /** Codex 以本地時區輸出、派工時固定 TZ=UTC，所以照 UTC 解讀。 */
    @Test void codex解禁時間以UTC解讀() {
        Instant observed = Instant.parse("2026-09-09T15:53:23Z");
        assertThat(QuotaRetryTime.providerResetInstant(CODEX, observed)).contains(Instant.parse("2026-09-15T13:09:00Z"));
        assertThat(QuotaRetryTime.providerResetInstant("try again at Oct 1st, 2026", observed))
                .contains(Instant.parse("2026-10-01T00:00:00Z"));
    }

    /** 同一天只有時刻；Codex 的「今天」跨過午夜時取隔天。 */
    @Test void codex只有時刻時以觀察時間判斷日期() {
        String sameDay = "You’ve hit your usage limit. Try again at 9:20 PM.";
        assertThat(QuotaRetryTime.providerResetInstant(sameDay, Instant.parse("2026-10-05T17:00:00Z")))
                .contains(Instant.parse("2026-10-05T21:20:00Z"));
        assertThat(QuotaRetryTime.next(sameDay, Instant.parse("2026-10-05T17:00:00Z"), 0))
                .isEqualTo(Instant.parse("2026-10-05T21:22:00Z"));
        assertThat(QuotaRetryTime.providerResetInstant("Try again at 12:30 AM.", Instant.parse("2026-10-05T23:50:00Z")))
                .contains(Instant.parse("2026-10-06T00:30:00Z"));
    }

    /** 解禁日還很遠時不再每 6 小時撞牆，直接排在解禁之後。 */
    @Test void 遠期解禁改排單次而非反覆退避() {
        Instant observed = Instant.parse("2026-09-09T15:53:23Z");
        assertThat(QuotaRetryTime.farBeyondBackoff(CODEX, observed)).isTrue();
        assertThat(QuotaRetryTime.next(CODEX, observed, 4)).isEqualTo(Instant.parse("2026-09-15T13:11:00Z"));
        // 已過解禁時刻仍撞牆時，回到退避，不把時間往回排。
        Instant late = Instant.parse("2026-09-16T00:00:00Z");
        assertThat(QuotaRetryTime.farBeyondBackoff(CODEX, late)).isFalse();
        assertThat(QuotaRetryTime.next(CODEX, late, 1)).isEqualTo(late.plusSeconds(3600));
    }
}
