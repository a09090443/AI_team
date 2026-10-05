package com.zipe.aiteam.task;

import java.time.Instant;

/** 額度訊息的呈現：時區確定的時間換算成台北時間並附原文；不確定的照原文，不把兩者混為一談。 */
public final class QuotaMessage {
    private QuotaMessage() {}

    /** 我方排定的時刻，換算成台北時間；奈秒與方括號對閱讀沒有幫助。 */
    public static String taipei(Instant instant) {
        return TaipeiTime.minutes(instant);
    }

    /** 我方排定的時刻，口語格式（今天／明天、星期、多久之後）。 */
    public static String taipei(Instant instant, Instant now) {
        return TaipeiTime.friendly(instant, now);
    }

    /**
     * 供應商自己寫的解禁時間。Claude 標明 UTC；Codex 不標時區，但以派工時固定的 TZ=UTC 輸出
     * （見 QuotaRetryTime）。兩者都換算成台北時間並附原文；解析不了的照原文呈現。
     *
     * @param observed 觀察到額度停止的時刻。只給時刻不給日期時，要以它判斷是當天還是隔天。
     */
    public static String resetLine(String reason, Instant observed) {
        var reset = QuotaRetryTime.providerResetInstant(reason, observed);
        var original = QuotaRetryTime.providerReset(reason);
        if (reset.isPresent())
            return "供應商指定解禁：" + taipei(reset.get(), observed) + "（原文 "
                    + original.map(text -> text.toLowerCase(java.util.Locale.ROOT).contains("utc") ? text : text + "，UTC").orElse("UTC") + "）\n";
        return original
                .map(text -> "供應商指定解禁：" + text + "（原文照登，無法解析，系統不推測）\n")
                .orElse("供應商未提供解禁時間。\n");
    }
}
