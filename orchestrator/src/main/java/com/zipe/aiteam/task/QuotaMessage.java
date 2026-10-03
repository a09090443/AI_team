package com.zipe.aiteam.task;

import java.time.Instant;

/** 額度訊息的呈現：供應商原文照登，我方時間才換算，不把兩者混為一談。 */
public final class QuotaMessage {
    private QuotaMessage() {}

    /** 我方排定的時刻，換算成台北時間；奈秒與方括號對閱讀沒有幫助。 */
    public static String taipei(Instant instant) {
        return TaipeiTime.minutes(instant);
    }

    /**
     * 供應商自己寫的解禁時間。標明 UTC 的（Claude）換算成台北時間並附原文；
     * Codex 的措辭沒有標時區，換算成任何時區都是猜測，猜錯會讓人白等或提早撞牆，所以照原文呈現。
     *
     * @param observed 觀察到額度停止的時刻。Claude 只給時刻不給日期，要以它判斷是當天還是隔天。
     */
    public static String resetLine(String reason, Instant observed) {
        var utc = QuotaRetryTime.utcReset(reason, observed);
        if (utc.isPresent())
            return "供應商指定解禁：" + taipei(utc.get()) + "（原文 "
                    + QuotaRetryTime.providerReset(reason).orElse("UTC") + "）\n";
        return QuotaRetryTime.providerReset(reason)
                .map(text -> "供應商指定解禁：" + text
                        + (text.toLowerCase(java.util.Locale.ROOT).contains("utc") ? "\n" : "（原文照登，未標時區，系統不推測）\n"))
                .orElse("供應商未提供解禁時間。\n");
    }
}
