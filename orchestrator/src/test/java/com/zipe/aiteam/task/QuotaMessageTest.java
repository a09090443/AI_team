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

    /** 沒標時區就註明不推測；標了 UTC 就不加註。 */
    @Test void 解禁時間照原文並標示時區是否明確() {
        assertThat(QuotaMessage.resetLine(CODEX))
                .isEqualTo("供應商指定解禁：Sep 15th, 2026 1:09 PM（原文照登，未標時區，系統不推測）\n");
        assertThat(QuotaMessage.resetLine(CLAUDE)).isEqualTo("供應商指定解禁：6:30pm (UTC)\n");
        assertThat(QuotaMessage.resetLine("some other failure")).isEqualTo("供應商未提供解禁時間。\n");
    }
}
