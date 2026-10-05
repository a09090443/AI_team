package com.zipe.aiteam.task;

import java.time.*;
import java.time.format.*;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** 只解讀時區確定的時刻；未知格式保守採退避，不猜使用者時區。 */
public final class QuotaRetryTime {
    private QuotaRetryTime() {}
    private static final Pattern RESET = Pattern.compile("resets?\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\s*\\(UTC\\)", Pattern.CASE_INSENSITIVE);

    /**
     * Codex 的措辭：不同天是 `try again at Sep 15th, 2026 1:09 PM`，同一天只有 `try again at 1:09 PM`。
     * 訊息不標時區，但 Codex CLI 以執行它的機器的本地時區輸出（codex-rs protocol/src/error.rs
     * `format_retry_timestamp` 用 chrono `Local`，0.156.1 起實查）；CodexRuntime 派工時固定 `TZ=UTC`，
     * 所以一律以 UTC 解讀。日期與時刻至少要有一個，否則不算。
     */
    private static final Pattern TRY_AGAIN = Pattern.compile(
            "try again at\\s+(?:([A-Za-z]{3,9})\\s+(\\d{1,2})(?:st|nd|rd|th)?,\\s*(\\d{4})\\s*)?"
          + "(?:(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm))?", Pattern.CASE_INSENSITIVE);

    /** 月份縮寫大小寫不固定，明確用不分大小寫的英文解析，不做手動轉換。 */
    private static final DateTimeFormatter MONTH = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("MMM").toFormatter(Locale.ENGLISH);

    /** 供應商自己寫出來的解禁時間原文；沒有就回 empty。 */
    public static Optional<String> providerReset(String reason) {
        if (reason == null) return Optional.empty();
        var codex = TRY_AGAIN.matcher(reason);
        while (codex.find())
            if (codex.group(3) != null || codex.group(6) != null)
                return Optional.of(codex.group().replaceFirst("(?i)^try again at\\s+", "").trim());
        var claude = RESET.matcher(reason);
        if (claude.find()) return Optional.of(claude.group().replaceFirst("(?i)^resets?\\s+", ""));
        return Optional.empty();
    }

    /** 供應商指定的解禁瞬間（Claude 標明 UTC；Codex 依派工時的 TZ=UTC）；格式不合回 empty，不猜。 */
    public static Optional<Instant> providerResetInstant(String reason, Instant observed) {
        if (reason == null || observed == null) return Optional.empty();
        var utc = utcReset(reason, observed);
        return utc.isPresent() ? utc : codexReset(reason, observed);
    }

    /** 供應商指名的解禁時間是否遠到不該反覆重試（24 小時之後）。 */
    public static boolean farBeyondBackoff(String reason, Instant observed) {
        return providerResetInstant(reason, observed).filter(r -> r.isAfter(observed.plusSeconds(86400))).isPresent();
    }

    static Optional<Instant> codexReset(String reason, Instant observed) {
        var match = TRY_AGAIN.matcher(reason);
        while (match.find()) {
            if (match.group(3) == null && match.group(6) == null) continue;
            try {
                LocalTime time = LocalTime.MIDNIGHT;
                if (match.group(6) != null) {
                    int hour = Integer.parseInt(match.group(4));
                    int minute = match.group(5) == null ? 0 : Integer.parseInt(match.group(5));
                    if (hour < 1 || hour > 12 || minute > 59) return Optional.empty();
                    time = LocalTime.of(hour % 12 + (match.group(6).equalsIgnoreCase("pm") ? 12 : 0), minute);
                }
                if (match.group(3) != null) {
                    Month month = Month.from(MONTH.parse(match.group(1).substring(0, 3)));
                    LocalDate date = LocalDate.of(Integer.parseInt(match.group(3)), month, Integer.parseInt(match.group(2)));
                    return Optional.of(date.atTime(time).toInstant(ZoneOffset.UTC));
                }
                // 只有時刻代表 Codex 判斷是「它的今天」；我方觀察時刻稍晚於它，跨過午夜時取隔天。
                Instant reset = observed.atZone(ZoneOffset.UTC).toLocalDate().atTime(time).toInstant(ZoneOffset.UTC);
                return Optional.of(reset.isBefore(observed.minusSeconds(3600)) ? reset.plusSeconds(86400) : reset);
            } catch (RuntimeException e) {
                return Optional.empty(); // 解析不出來就當沒說，交回退避，不猜。
            }
        }
        return Optional.empty();
    }

    /**
     * Claude 的「resets 6:30pm (UTC)」只有時刻沒有日期：取觀察當下之後第一個符合的 UTC 時刻。
     * 時區明確，所以可以換算；格式不合就回 empty，不猜。
     */
    public static Optional<Instant> utcReset(String reason, Instant observed) {
        if (reason == null || observed == null) return Optional.empty();
        var match = RESET.matcher(reason);
        if (!match.find()) return Optional.empty();
        int hour = Integer.parseInt(match.group(1));
        int minute = match.group(2) == null ? 0 : Integer.parseInt(match.group(2));
        if (hour < 1 || hour > 12 || minute > 59) return Optional.empty();
        hour = hour % 12 + (match.group(3).equalsIgnoreCase("pm") ? 12 : 0);
        Instant reset = observed.atZone(ZoneOffset.UTC).toLocalDate().atTime(hour, minute).toInstant(ZoneOffset.UTC);
        return Optional.of(reset.isBefore(observed) ? reset.plusSeconds(86400) : reset);
    }

    public static Instant next(String reason, Instant observed, int attempts) {
        long minutes = Math.min(360, 30L << Math.min(Math.max(attempts, 0), 4));
        Instant backoff = observed.plusSeconds(minutes * 60);
        var parsed = providerResetInstant(reason, observed);
        if (parsed.isEmpty()) return backoff;
        Instant reset = parsed.get().plusSeconds(120);
        // 首次尊重供應商時間；後續也須符合退避間隔。解禁時間很遠時直接排在那之後，不每 6 小時撞牆。
        return attempts == 0 || reset.isAfter(backoff) ? reset : backoff;
    }
}
