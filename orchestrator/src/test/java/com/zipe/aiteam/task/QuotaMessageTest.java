package com.zipe.aiteam.task;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class QuotaMessageTest {
    private static final String CODEX = "You’ve hit your usage limit. Upgrade to Pro (https://chatgpt.com/explore/pro), "
            + "visit https://chatgpt.com/codex/settings/usage to purchase more credits "
            + "or try again at Oct 7th, 2026 1:09 PM.";
    private static final String CLAUDE = "You've hit your session limit · resets 6:30pm (UTC)";

    @Test void 台北時間可讀且不含奈秒() {
        assertThat(QuotaMessage.taipei(Instant.parse("2026-09-10T04:56:23.141258134Z")))
                .isEqualTo("2026-09-10 12:56（台北時間）");
    }

    private static final Instant OBSERVED = Instant.parse("2026-10-03T13:10:00Z"); // 台北 10/3（週六）21:10

    /** 兩家都換算成台北時間的口語格式並附原文；Codex 原文標出 UTC（派工時固定的時區）。 */
    @Test void 解禁時間換算成台北時間並附原文() {
        assertThat(QuotaMessage.resetLine(CODEX, OBSERVED))
                .isEqualTo("供應商指定解禁：10月7日（週三）21:09（台北時間，約 4 天後）（原文 Oct 7th, 2026 1:09 PM，UTC）\n");
        assertThat(QuotaMessage.resetLine(CLAUDE, OBSERVED))
                .isEqualTo("供應商指定解禁：明天（週日）02:30（台北時間，約 5 小時 20 分後）（原文 6:30pm (UTC)）\n");
        assertThat(QuotaMessage.resetLine("try again at Foo 99th, 2026 1:09 PM", OBSERVED))
                .isEqualTo("供應商指定解禁：Foo 99th, 2026 1:09 PM（原文照登，無法解析，系統不推測）\n");
        assertThat(QuotaMessage.resetLine("some other failure", OBSERVED)).isEqualTo("供應商未提供解禁時間。\n");
    }

    /** 只給時刻：觀察時已過了當天那個 UTC 時刻，就是隔天；台北日期也跟著跨日。 */
    @Test void 只有時刻的UTC解禁以觀察時間判斷當天或隔天() {
        String reason = "You've hit your session limit · resets 2:30pm (UTC)";
        assertThat(QuotaMessage.resetLine(reason, Instant.parse("2026-10-03T13:10:00Z")))
                .startsWith("供應商指定解禁：今天（週六）22:30（台北時間，約 1 小時 20 分後）");
        assertThat(QuotaMessage.resetLine(reason, Instant.parse("2026-10-03T15:00:00Z")))
                .startsWith("供應商指定解禁：明天（週日）22:30（台北時間，約 23 小時 30 分後）");
        assertThat(QuotaMessage.resetLine("resets 13:30pm (UTC)", OBSERVED)).doesNotContain("台北時間");
    }

    @Test void 口語格式() {
        Instant now = Instant.parse("2026-12-31T02:00:00Z"); // 台北 12/31 10:00
        assertThat(TaipeiTime.friendly(Instant.parse("2026-12-31T02:30:00Z"), now)).isEqualTo("今天（週四）10:30（台北時間，約 30 分鐘後）");
        assertThat(TaipeiTime.friendly(Instant.parse("2027-01-01T04:00:00Z"), now)).isEqualTo("明天（週五）12:00（台北時間，約 26 小時後）");
        assertThat(TaipeiTime.friendly(Instant.parse("2027-01-05T04:00:00Z"), now)).isEqualTo("2027年1月5日（週二）12:00（台北時間，約 5 天後）");
        assertThat(TaipeiTime.friendly(Instant.parse("2026-12-31T01:00:00Z"), now)).isEqualTo("今天（週四）09:00（台北時間）");
    }
}
