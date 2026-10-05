package com.zipe.aiteam.task;

import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.runtime.QuotaExceeded;
import com.zipe.aiteam.slack.SlackNotifier;
import com.zipe.aiteam.store.TaskStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.*;

/** 持久化等待時間，使用原中央工作佇列，不執行額外模型探測。 */
@Component
public class QuotaAutoResume {
    private final JdbcTemplate jdbc;
    private final TaskStore store;
    private final TaskOrchestrator orchestrator;
    private final AiTeamProperties props;
    private final SlackNotifier slack;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "quota-auto-resume"); t.setDaemon(true); return t;
    });
    public QuotaAutoResume(JdbcTemplate jdbc, TaskStore store, TaskOrchestrator orchestrator,
                           AiTeamProperties props, SlackNotifier slack) {
        this.jdbc = jdbc; this.store = store; this.orchestrator = orchestrator; this.props = props; this.slack = slack;
    }
    public void start() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS quota_retry(task_id TEXT PRIMARY KEY, observed TEXT NOT NULL, next_at TEXT NOT NULL, attempts INTEGER NOT NULL)");
        timer.scheduleWithFixedDelay(this::poll, 5, 60, TimeUnit.SECONDS);
    }
    void poll() {
        try {
            for (Task task : store.findUnfinished()) {
                try { check(task, Instant.now()); }
                catch (Exception e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("額度排程任務 {} 檢查失敗：{}", task.id(), e.getClass().getSimpleName()); }
            }
        } catch (Exception e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("額度排程暫時無法查詢：{}", e.getClass().getSimpleName()); }
    }
    void check(Task task, Instant now) {
        if (task.state() != Task.State.WAITING_QUOTA || !props.allowedUsers().contains(task.requesterUserId())) return;
        var checkpoint = store.checkpoint(task.id()).orElse(null);
        if (checkpoint == null || checkpoint.reason() == null || !QuotaExceeded.explicit(checkpoint.reason())) return;
        var flow = store.flow(task.id());
        if (flow.isPresent() && flow.get().changeTarget != null) return;
        String observed = jdbc.queryForObject("SELECT updated_at FROM task WHERE id=?", String.class, task.id());
        var rows = jdbc.queryForList("SELECT * FROM quota_retry WHERE task_id=?", task.id());
        int attempts = rows.isEmpty() ? 0 : ((Number) rows.getFirst().get("attempts")).intValue();
        if (rows.isEmpty() || !observed.equals(rows.getFirst().get("observed"))) {
            Instant next = QuotaRetryTime.next(checkpoint.reason(), Instant.parse(observed), attempts);
            jdbc.update("INSERT INTO quota_retry(task_id,observed,next_at,attempts) VALUES(?,?,?,?) ON CONFLICT(task_id) DO UPDATE SET observed=excluded.observed,next_at=excluded.next_at",
                    task.id(), observed, next.toString(), attempts);
            slack.post(SlackNotifier.Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(),
                    "任務 #" + task.id() + " 等待額度。\n"
                  + QuotaMessage.resetLine(checkpoint.reason(), Instant.parse(observed))
                  + "下次自動嘗試：" + QuotaMessage.taipei(next, Instant.parse(observed)) + "\n"
                  + (QuotaRetryTime.farBeyondBackoff(checkpoint.reason(), Instant.parse(observed))
                        ? "供應商指定的解禁時間還很遠，因此在那之前不再反覆重試；你也可以在解禁後自行送出續跑。\n"
                        : "")
                  + "這是嘗試時間，不是額度已恢復的保證；可中止任務取消。原工作區、session 與配置保留。");
            return;
        }
        Instant due = Instant.parse(rows.getFirst().get("next_at").toString());
        if (due.isAfter(now)) return;
        // 先保存退避；即使程序在排隊前停止，重啟亦不會密集重試。
        // 供應商指名的解禁日還很遠時，這裡也要沿用同一套判斷，否則會退回每 6 小時撞牆。
        Instant following = QuotaRetryTime.next(checkpoint.reason(), now, attempts + 1);
        int claimed = jdbc.update("UPDATE quota_retry SET next_at=?,attempts=attempts+1 WHERE task_id=? AND next_at=? AND EXISTS(SELECT 1 FROM task WHERE id=? AND state='WAITING_QUOTA')",
                following.toString(), task.id(), due.toString(), task.id());
        if (claimed != 1) return;
        Task fresh = store.find(task.id()).orElse(null);
        if (fresh == null || fresh.state() != Task.State.WAITING_QUOTA) return;
        orchestrator.continueTask(fresh, fresh.requesterUserId());
    }
    @PreDestroy public void stop() { timer.shutdownNow(); }
}
