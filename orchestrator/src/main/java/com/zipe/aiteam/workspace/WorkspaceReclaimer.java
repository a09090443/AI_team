package com.zipe.aiteam.workspace;

import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.store.TaskStore;
import com.zipe.aiteam.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * 工作區佔用控制。保留中的工作區（PR_CREATED、等待真人、失敗待查…）以前要等服務重啟、而且任務結束才清，
 * 2026-11-05 一個實例 workspaces/ 累積 12G（每個約 1.9G，其中約 1.8G 是 node_modules、build 等可重建產物），
 * 根分割區只剩 410MB，任務在獨立驗證前以「磁碟空間不足」停下等真人。
 *
 * 兩條路徑，都只刪可重建內容（{@link WorkspaceManager#removeBuildOutputs}），git 工作樹與 commit 不動：
 * <ul>
 *   <li>閒置回收：保留中的任務超過 {@code AI_TEAM_WORKSPACE_RECLAIM_IDLE_HOURS} 小時沒有狀態變更，刪建置產物。
 *       服務啟動時與工作區節點開始前檢查，每小時至多一次。</li>
 *   <li>不足回收：可用空間低於 {@link #MIN_FREE_BYTES} 時，先回收已結束任務的工作區（與啟動清理同一套保留規則），
 *       仍不足再從最久沒動的閒置任務開始刪建置產物，直到回到 {@link #TARGET_FREE_BYTES}；之後才由呼叫端決定是否停下。</li>
 * </ul>
 * 只處理本實例的 workspaces/；執行中、排隊中、呼叫者自己，以及正被角色對話讀取的原任務都不碰。
 * 續跑被回收過的任務時，agent 或驗證指令要重建依賴（例如重跑 npm ci）。
 */
@Component
public class WorkspaceReclaimer {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceReclaimer.class);
    /** 與獨立驗證的門檻相同（VerificationScenes.MIN_FREE_BYTES）。 */
    public static final long MIN_FREE_BYTES = 1L << 30;
    /** 回收到這個量才停，避免在門檻上下反覆觸發。 */
    public static final long TARGET_FREE_BYTES = 2L << 30;
    static final Duration IDLE_SWEEP_INTERVAL = Duration.ofHours(1);
    private static final Set<Task.State> QUEUED = EnumSet.of(Task.State.QUEUED, Task.State.RESUME_QUEUED, Task.State.TRIAGE_QUEUED);

    private final AiTeamProperties props;
    private final WorkspaceManager workspaces;
    private final TaskStore store;
    /** 由 TaskOrchestrator 登錄：回收已結束任務的整個工作區（參數為不可刪的任務），回傳刪除數。 */
    private volatile ToIntFunction<Set<String>> terminalPurge;
    /** 由 TaskOrchestrator 登錄：歷史模式任務不屬於目前配置，不碰。 */
    private volatile Predicate<String> eligible = id -> true;
    private Instant lastIdleSweep;
    /** 測試替換用。 */
    java.util.function.ToLongFunction<Path> freeSpace = WorkspaceReclaimer::usable;
    java.util.function.Supplier<Instant> clock = Instant::now;

    public WorkspaceReclaimer(AiTeamProperties props, WorkspaceManager workspaces, TaskStore store) {
        this.props = props; this.workspaces = workspaces; this.store = store;
    }

    public void register(ToIntFunction<Set<String>> terminalPurge, Predicate<String> eligible) {
        this.terminalPurge = terminalPurge;
        if (eligible != null) this.eligible = eligible;
    }

    public boolean enabled() { return props.workspaceReclaim(); }

    /** 服務啟動清理後與工作區節點開始前呼叫；任何錯誤只記 log，不影響任務。 */
    public synchronized void maintain(String currentTaskId) {
        if (!enabled() || props.workspacesPath() == null) return;
        try {
            ensureSpace(props.workspacesPath(), currentTaskId);
            long hours = props.workspaceReclaimIdleHours();
            Instant now = clock.get();
            if (hours <= 0 || (lastIdleSweep != null && lastIdleSweep.plus(IDLE_SWEEP_INTERVAL).isAfter(now))) return;
            lastIdleSweep = now;
            Instant cutoff = now.minus(Duration.ofHours(hours));
            int stripped = 0;
            for (String id : candidates(currentTaskId)) {
                if (store.updatedAt(id).map(t -> t.isBefore(cutoff)).orElse(false) && strip(id)) stripped++;
            }
            if (stripped > 0) log.info("工作區閒置回收：{} 個閒置超過 {} 小時的任務已刪建置產物", stripped, hours);
        } catch (Exception e) {
            log.warn("工作區回收失敗，不影響任務：{}", e.toString());
        }
    }

    /**
     * 可用空間低於門檻時自動回收，回傳回收後的可用空間；關閉時只回報目前空間。
     * 呼叫端仍要自己判斷是否足夠（例如獨立驗證不足 1 GB 仍會停下）。
     */
    public synchronized long ensureSpace(Path where, String currentTaskId) {
        long free = free(where);
        if (!enabled() || free >= MIN_FREE_BYTES) return free;
        long before = free;
        Set<String> busy = busyParents();
        if (currentTaskId != null) busy.add(currentTaskId);
        int purged = 0, stripped = 0;
        ToIntFunction<Set<String>> purge = terminalPurge;
        if (purge != null) {
            try { purged = purge.applyAsInt(Set.copyOf(busy)); }
            catch (Exception e) { log.warn("回收已結束任務的工作區失敗：{}", e.toString()); }
            free = free(where);
        }
        if (free < TARGET_FREE_BYTES) {
            List<String> idle = new ArrayList<>(candidates(currentTaskId));
            idle.sort(Comparator.comparing((String id) -> store.updatedAt(id).orElse(Instant.EPOCH)));
            for (String id : idle) {
                if (strip(id)) { stripped++; free = free(where); }
                if (free >= TARGET_FREE_BYTES) break;
            }
        }
        log.warn("磁碟可用空間 {} MB 低於 {} MB，已自動回收：刪除已結束任務工作區 {} 個、閒置任務建置產物 {} 個，可用空間 {} MB",
                before >> 20, MIN_FREE_BYTES >> 20, purged, stripped, free >> 20);
        return free;
    }

    /** 可以刪建置產物的任務：有工作區、屬於目前配置、不在執行或排隊、不是呼叫者自己、沒有被執行中的角色對話讀取。 */
    List<String> candidates(String currentTaskId) {
        Set<String> busy = busyParents();
        List<String> result = new ArrayList<>();
        for (String id : workspaces.listTaskIds()) {
            if (id.equals(currentTaskId) || busy.contains(id) || !eligible.test(id)) continue;
            if (store.find(id).map(t -> idle(t.state())).orElse(false)) result.add(id);
        }
        return result;
    }

    static boolean idle(Task.State state) { return !state.isRunning() && !QUEUED.contains(state); }

    /** 刪之前再讀一次狀態：等待中的任務可能剛被續跑。 */
    private boolean strip(String id) {
        if (!store.find(id).map(t -> idle(t.state())).orElse(false)) return false;
        try { return !workspaces.removeBuildOutputs(id).isEmpty(); }
        catch (Exception e) {
            log.warn("刪除工作區 task-{} 的建置產物失敗：{}", id, e.getMessage());
            return false;
        }
    }

    /** 未結束的角色對話會讀原任務工作區（TaskOrchestrator.requestRole），讀的期間不刪。 */
    private Set<String> busyParents() {
        Set<String> busy = new HashSet<>();
        try {
            for (Task t : store.findUnfinished())
                store.direct(t.id()).map(d -> d.parentId()).filter(Objects::nonNull).ifPresent(busy::add);
        } catch (Exception e) {
            log.warn("查詢執行中的角色對話失敗：{}", e.toString());
        }
        return busy;
    }

    private long free(Path where) {
        try { return freeSpace.applyAsLong(where); }
        catch (Exception e) { return Long.MAX_VALUE; }
    }

    private static long usable(Path where) {
        try {
            Path p = where.toAbsolutePath();
            while (p != null && !Files.exists(p)) p = p.getParent();
            return p == null ? Long.MAX_VALUE : Files.getFileStore(p).getUsableSpace();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
