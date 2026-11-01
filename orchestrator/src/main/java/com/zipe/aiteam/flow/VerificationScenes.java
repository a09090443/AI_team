package com.zipe.aiteam.flow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 驗證現場（verification-runs/&lt;task&gt;/run-*、0/baseline-*）的回收。
 * 收據、重用與發布前核對只讀現場目錄下的 log 與比對檔；work/（全新 clone、HOME、建置產物）結束後只剩診斷用途，
 * 每份約 1 GB。2026-11-01 VM 因此磁碟用完，任務撞上 SQLITE_FULL 卡在執行中。
 */
public final class VerificationScenes {
    private static final Logger log = LoggerFactory.getLogger(VerificationScenes.class);
    /** 低於此可用空間就不開新的驗證 clone，讓任務以明確原因停下，而不是寫到一半把資料庫也寫壞。 */
    static final long MIN_FREE_BYTES = 1L << 30;
    private VerificationScenes() {}

    static boolean scene(Path dir) {
        String name = dir.getFileName().toString();
        return name.startsWith("run-") || name.startsWith("baseline-");
    }

    /** 刪除一個現場的 work/，log 保留；失敗只記 warn，不影響收據。回傳釋放與否。 */
    static boolean discard(Path run) {
        if (run == null || !scene(run)) return false;
        Path work = run.resolve("work");
        if (!Files.isDirectory(work, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            deleteTree(work);
            return true;
        } catch (IOException | UncheckedIOException e) {
            log.warn("刪除驗證現場 {} 失敗：{}", work, e.toString());
            return false;
        }
    }

    /** 同一個任務目錄下，除了 keep 以外的現場都刪 work/；keep 為 null 時全刪。 */
    static int discardAllBut(Path taskRoot, Path keep) {
        if (!Files.isDirectory(taskRoot)) return 0;
        int removed = 0;
        try (Stream<Path> dirs = Files.list(taskRoot)) {
            for (Path dir : dirs.filter(Files::isDirectory).filter(VerificationScenes::scene).toList())
                if (!dir.equals(keep) && discard(dir)) removed++;
        } catch (IOException e) {
            log.warn("列出驗證現場 {} 失敗：{}", taskRoot, e.toString());
        }
        return removed;
    }

    /** 最新的現場（依修改時間），沒有則 null。 */
    static Path latest(Path taskRoot) {
        if (!Files.isDirectory(taskRoot)) return null;
        try (Stream<Path> dirs = Files.list(taskRoot)) {
            return dirs.filter(Files::isDirectory).filter(VerificationScenes::scene)
                    .max(Comparator.comparing(VerificationScenes::modified)).orElse(null);
        } catch (IOException e) { return null; }
    }

    static void requireSpace(Path root) throws IOException {
        long free = Files.getFileStore(root).getUsableSpace();
        if (free < MIN_FREE_BYTES)
            throw new IllegalStateException("磁碟空間不足：驗證目錄所在分割區只剩 " + (free >> 20)
                    + " MB（需至少 " + (MIN_FREE_BYTES >> 20) + " MB），未開始驗證；請管理者釋放空間後續跑");
    }

    /**
     * 啟動時整理：keepLatest 回傳 true 的任務（仍在等待或失敗待查）保留最新一份現場，其餘全部刪 work/。
     * 基準驗證（0/）只作警告，log 已足夠，一律刪。
     */
    public static int sweep(Path root, java.util.function.Predicate<String> keepLatest) {
        if (!Files.isDirectory(root)) return 0;
        int removed = 0;
        try (Stream<Path> tasks = Files.list(root)) {
            for (Path dir : tasks.filter(Files::isDirectory).toList()) {
                String id = dir.getFileName().toString();
                if (!id.matches("[0-9]+")) continue;
                boolean keep = !"0".equals(id) && keepLatest.test(id);
                removed += discardAllBut(dir, keep ? latest(dir) : null);
            }
        } catch (IOException e) {
            log.warn("列出驗證現場 {} 失敗：{}", root, e.toString());
        }
        return removed;
    }

    private static java.nio.file.attribute.FileTime modified(Path p) {
        try { return Files.getLastModifiedTime(p); }
        catch (IOException e) { return java.nio.file.attribute.FileTime.fromMillis(0); }
    }

    /** 依賴快取與 .git 物件有唯讀檔與唯讀目錄：先全部設成可寫（Windows 唯讀檔刪不掉），再由深到淺刪除；不跟隨 symlink。 */
    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.toList())
                if (!Files.isSymbolicLink(p)) p.toFile().setWritable(true);
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
