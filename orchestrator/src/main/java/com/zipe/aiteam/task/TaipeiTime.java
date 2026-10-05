package com.zipe.aiteam.task;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** 給人看的時間一律用台北時間；資料庫仍存 UTC Instant，只在呈現時換算。 */
public final class TaipeiTime {
    private TaipeiTime() {}
    public static final ZoneId ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String WEEKDAYS = "一二三四五六日";

    /** 精確到分，附「（台北時間）」。 */
    public static String minutes(Instant instant) {
        return MINUTES.format(instant.atZone(ZONE)) + "（台北時間）";
    }

    /** 精確到秒，附「（台北時間）」；給定期心跳這類需要分辨先後的訊息。 */
    public static String seconds(Instant instant) {
        return SECONDS.format(instant.atZone(ZONE)) + "（台北時間）";
    }

    /**
     * 給等待類訊息的口語格式：「明天（週一）01:20（台北時間，約 3 小時 50 分後）」。
     * 今天／明天之外寫月日，跨年才寫年份；已過去的時刻不附相對時間。
     */
    public static String friendly(Instant instant, Instant now) {
        ZonedDateTime t = instant.atZone(ZONE), n = now.atZone(ZONE);
        long days = n.toLocalDate().until(t.toLocalDate(), java.time.temporal.ChronoUnit.DAYS);
        String date = days == 0 ? "今天" : days == 1 ? "明天" : days == 2 ? "後天"
                : (t.getYear() != n.getYear() ? t.getYear() + "年" : "") + t.getMonthValue() + "月" + t.getDayOfMonth() + "日";
        return date + "（週" + WEEKDAYS.charAt(t.getDayOfWeek().getValue() - 1) + "）"
                + String.format("%02d:%02d", t.getHour(), t.getMinute()) + "（台北時間" + relative(instant, now) + "）";
    }

    private static String relative(Instant instant, Instant now) {
        long minutes = (Duration.between(now, instant).getSeconds() + 59) / 60;
        if (minutes <= 0) return "";
        if (minutes < 60) return "，約 " + minutes + " 分鐘後";
        if (minutes < 48 * 60) return "，約 " + minutes / 60 + " 小時" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + " 分") + "後";
        return "，約 " + Math.round(minutes / 1440.0) + " 天後";
    }
}
