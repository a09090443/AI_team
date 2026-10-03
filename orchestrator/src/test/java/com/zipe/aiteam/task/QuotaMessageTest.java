package com.zipe.aiteam.task;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class QuotaMessageTest {
    private static final String CODEX = "You've hit your usage limit. Upgrade to Pro (https://chatgpt.com/explore/pro), "
            + "visit https://chatgpt.com/codex/settings/usage to purchase more credits "
            + "or try again at Sep 15th, 2026 1:09 PM.";
    private static final String CLAUDE = "You've hit your session limit · resets 6:30pm (UTC)";

    @Test void 台北時間可讀且不含奈秒() {
        assertThat(QuotaMessage.taipei(Instant.parse("2026-09-10T04:56:23.141258134Z")))
                .isEqualTo("2026-09-10 12:56（台北時間）");
    }

    private static final Instant OBSERVED = Instant.parse("2026-10-03T13:10:00Z");

    /** 沒標時區就照原文並註明不推測；標了 UTC 就換算成台北時間並附原文。 */
    @Test void 解禁時間依時區是否明確決定換算或照登() {
        assertThat(QuotaMessage.resetLine(CODEX, OBSERVED))
                .isEqualTo("供應商指定解禁：Sep 15th, 2026 1:09 PM（原文照登，未標時區，系統不推測）\n");
        assertThat(QuotaMessage.resetLine(CLAUDE, OBSERVED))
                .isEqualTo("供應商指定解禁：2026-10-04 02:30（台北時間）（原文 6:30pm (UTC)）\n");
        assertThat(QuotaMessage.resetLine("some other failure", OBSERVED)).isEqualTo("供應商未提供解禁時間。\n");
    }

    /** 只給時刻：觀察時已過了當天那個 UTC 時刻，就是隔天；台北日期也跟著跨日。 */
    @Test void 只有時刻的UTC解禁以觀察時間判斷當天或隔天() {
        String reason = "You've hit your session limit · resets 2:30pm (UTC)";
        assertThat(QuotaMessage.resetLine(reason, Instant.parse("2026-10-03T13:10:00Z")))
                .startsWith("供應商指定解禁：2026-10-03 22:30（台北時間）");
        assertThat(QuotaMessage.resetLine(reason, Instant.parse("2026-10-03T15:00:00Z")))
                .startsWith("供應商指定解禁：2026-10-04 22:30（台北時間）");
        assertThat(QuotaMessage.resetLine("resets 13:30pm (UTC)", OBSERVED)).doesNotContain("台北時間");
    }
}
