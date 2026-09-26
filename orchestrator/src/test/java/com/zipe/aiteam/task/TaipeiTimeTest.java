package com.zipe.aiteam.task;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class TaipeiTimeTest {
    /** UTC 16:30 跨日成台北隔天 00:30，確認不是沿用主機時區。 */
    @Test void 換算台北時間且跨日正確() {
        Instant utc = Instant.parse("2026-09-25T16:30:05.987654321Z");
        assertThat(TaipeiTime.minutes(utc)).isEqualTo("2026-09-26 00:30（台北時間）");
        assertThat(TaipeiTime.seconds(utc)).isEqualTo("2026-09-26 00:30:05（台北時間）");
    }
}
