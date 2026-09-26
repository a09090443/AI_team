package com.zipe.aiteam.task;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 給人看的時間一律用台北時間；資料庫仍存 UTC Instant，只在呈現時換算。 */
public final class TaipeiTime {
    private TaipeiTime() {}
    public static final ZoneId ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 精確到分，附「（台北時間）」。 */
    public static String minutes(Instant instant) {
        return MINUTES.format(instant.atZone(ZONE)) + "（台北時間）";
    }

    /** 精確到秒，附「（台北時間）」；給定期心跳這類需要分辨先後的訊息。 */
    public static String seconds(Instant instant) {
        return SECONDS.format(instant.atZone(ZONE)) + "（台北時間）";
    }
}
