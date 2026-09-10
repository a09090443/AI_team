package com.zipe.aiteam.task;

import java.time.*;
import java.time.format.*;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** 只解讀明確 UTC 時刻；未知格式保守採退避，不猜使用者時區。 */
public final class QuotaRetryTime {
    private QuotaRetryTime() {}
    private static final Pattern RESET = Pattern.compile("resets?\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\s*\\(UTC\\)", Pattern.CASE_INSENSITIVE);

    /**
     * Codex 的措辭：try again at Sep 15th, 2026 1:09 PM。
     * 有日期但**沒有時區**，所以只用來判斷「是不是遠到不值得反覆重試」，
     * 不拿來當成精確的續跑時刻。
     */
    private static final Pattern TRY_AGAIN = Pattern.compile(
            "try again at\\s+([A-Za-z]{3,9})\\s+(\\d{1,2})(?:st|nd|rd|th)?,\\s*(\\d{4})"
          + "(?:\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm))?", Pattern.CASE_INSENSITIVE);

    /** 月份縮寫大小寫不固定，明確用不分大小寫的英文解析，不做手動轉換。 */
    private static final DateTimeFormatter MONTH = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("MMM").toFormatter(Locale.ENGLISH);

    /** 時區未知時，同一個本地時刻最早可能對應的 UTC 瞬間（UTC+14）。 */
    private static final int MAX_AHEAD_HOURS = 14;
    /** 同一個本地時刻最晚可能對應的 UTC 瞬間（UTC−12）。 */
    private static final int MAX_BEHIND_HOURS = 12;

    /** 供應商自己寫出來的解禁時間原文；沒有就回 empty。照原文呈現，不翻譯也不換算。 */
    public static Optional<String> providerReset(String reason) {
        if (reason == null) return Optional.empty();
        var codex = TRY_AGAIN.matcher(reason);
        if (codex.find()) return Optional.of(codex.group().replaceFirst("(?i)^try again at\\s+", ""));
        var claude = RESET.matcher(reason);
        if (claude.find()) return Optional.of(claude.group().replaceFirst("(?i)^resets?\\s+", ""));
        return Optional.empty();
    }

    /**
     * 供應商指名的解禁時刻在時區未知下**最早**可能發生的瞬間。
     * 用最早邊界是刻意的：要停掉自動重試就得確定「連最樂觀的解讀都還很遠」。
     */
    public static Optional<Instant> earliestPossibleReset(String reason) {
        return parseNamedReset(reason).map(local -> local.toInstant(ZoneOffset.ofHours(MAX_AHEAD_HOURS)));
    }

    /** 供應商指名的解禁時刻**最晚**可能發生的瞬間；用來排「最早值得再試一次」的時間。 */
    public static Optional<Instant> latestPossibleReset(String reason) {
        return parseNamedReset(reason).map(local -> local.toInstant(ZoneOffset.ofHours(-MAX_BEHIND_HOURS)));
    }

    /** 供應商指名的解禁時間是否遠到不該反覆重試（連最早解讀都在 24 小時之後）。 */
    public static boolean farBeyondBackoff(String reason, Instant observed) {
        return earliestPossibleReset(reason).filter(r -> r.isAfter(observed.plusSeconds(86400))).isPresent();
    }

    private static Optional<LocalDateTime> parseNamedReset(String reason) {
        if (reason == null) return Optional.empty();
        var match = TRY_AGAIN.matcher(reason);
        if (!match.find()) return Optional.empty();
        try {
            Month month = Month.from(MONTH.parse(match.group(1).substring(0, 3)));
            LocalDate date = LocalDate.of(Integer.parseInt(match.group(3)), month, Integer.parseInt(match.group(2)));
            if (match.group(4) == null) return Optional.of(date.atStartOfDay());
            int hour = Integer.parseInt(match.group(4));
            int minute = match.group(5) == null ? 0 : Integer.parseInt(match.group(5));
            if (hour < 1 || hour > 12 || minute > 59) return Optional.of(date.atStartOfDay());
            hour = hour % 12 + (match.group(6).equalsIgnoreCase("pm") ? 12 : 0);
            return Optional.of(date.atTime(hour, minute));
        } catch (RuntimeException e) {
            return Optional.empty(); // 解析不出來就當沒說，交回退避，不猜。
        }
    }

    public static Instant next(String reason, Instant observed, int attempts) {
        long minutes = Math.min(360, 30L << Math.min(Math.max(attempts, 0), 4));
        Instant backoff = observed.plusSeconds(minutes * 60);
        var match = RESET.matcher(reason);
        if (match.find()) {
            int hour = Integer.parseInt(match.group(1));
            int minute = match.group(2) == null ? 0 : Integer.parseInt(match.group(2));
            if (hour < 1 || hour > 12 || minute > 59) return backoff;
            hour = hour % 12 + (match.group(3).equalsIgnoreCase("pm") ? 12 : 0);
            Instant reset = observed.atZone(ZoneOffset.UTC).toLocalDate().atTime(hour, minute).toInstant(ZoneOffset.UTC);
            if (reset.isBefore(observed)) reset = reset.plusSeconds(86400);
            reset = reset.plusSeconds(120);
            // 首次尊重供應商時間；後續也須符合退避間隔。
            return attempts == 0 || reset.isAfter(backoff) ? reset : backoff;
        }
        // 供應商指名的解禁日還很遠時，退避每 6 小時撞一次牆毫無意義；
        // 改排在時區最壞解讀之後再試一次，中間不重試。
        if (farBeyondBackoff(reason, observed)) {
            var latest = latestPossibleReset(reason).orElseThrow().plusSeconds(120);
            if (latest.isAfter(backoff)) return latest;
        }
        return backoff;
    }
}
