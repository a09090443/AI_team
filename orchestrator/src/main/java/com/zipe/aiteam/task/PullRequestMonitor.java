package com.zipe.aiteam.task;

import com.zipe.aiteam.store.TaskStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 定期檢查系統開出的 PR 是否與基準衝突（#1056）；發現衝突時由流程引擎回原任務同步。
 * 只查 PR_CREATED 任務，每 10 分鐘一次；配置未開啟 project.autonomy.pullRequestSyncRounds 時不呼叫 GitHub。
 * 實際工作排進原中央佇列，這裡不執行 agent。
 */
@Component
public class PullRequestMonitor {
    private static final Logger log = LoggerFactory.getLogger(PullRequestMonitor.class);
    static final long INTERVAL_MINUTES = 10;
    private final TaskStore store;
    private final TaskOrchestrator orchestrator;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pull-request-monitor"); t.setDaemon(true); return t;
    });

    public PullRequestMonitor(TaskStore store, TaskOrchestrator orchestrator) {
        this.store = store; this.orchestrator = orchestrator;
    }
    public void start() {
        timer.scheduleWithFixedDelay(this::poll, 2, INTERVAL_MINUTES * 60, TimeUnit.SECONDS);
    }
    void poll() {
        try {
            for (String id : store.pullRequestTaskIds()) {
                try { store.find(id).ifPresent(task -> orchestrator.checkPullRequest(task, null)); }
                catch (Exception e) { log.warn("PR 衝突檢查：任務 {} 失敗：{}", id, e.getClass().getSimpleName()); }
            }
        } catch (Exception e) { log.warn("PR 衝突檢查暫時無法查詢：{}", e.getClass().getSimpleName()); }
    }
    @PreDestroy public void stop() { timer.shutdownNow(); }
}
