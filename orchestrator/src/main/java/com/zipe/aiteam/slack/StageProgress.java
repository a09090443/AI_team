package com.zipe.aiteam.slack;

import com.zipe.aiteam.task.Task;
import com.zipe.aiteam.store.TaskStore;
import java.time.Instant;
import java.util.concurrent.*;

/** 系統觀察的階段心跳；不將等待時間當成產品完成進度。 */
public final class StageProgress implements AutoCloseable {
    private final SlackNotifier slack;
    private final TaskStore store;
    private final Task task;
    private final String label;
    private final long started = System.nanoTime();
    private final ScheduledExecutorService scheduler;
    private String messageTs;
    private boolean closed;

    public StageProgress(SlackNotifier slack, TaskStore store, Task task, String label, long seconds) {
        this.slack = slack; this.store = store; this.task = task; this.label = label;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "stage-progress-" + task.id());
            thread.setDaemon(true); return thread;
        });
        tick();
        if (!closed) scheduler.scheduleWithFixedDelay(this::tick, Math.max(30, seconds), Math.max(30, seconds), TimeUnit.SECONDS);
    }

    synchronized void tick() {
        if (closed || slack == null || store == null) return;
        try {
            var current = store.find(task.id());
            if (current.isEmpty() || !current.get().state().isRunning()) {
                close(); return;
            }
            publish("執行呼叫尚未結束，等待階段結果；尚未核實完成。\n"
                    + "這是系統定期狀態回報，不代表最近有工具活動。");
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(StageProgress.class).warn("任務 {} 定期回報失敗：{}",
                    task.id(), e.getClass().getSimpleName());
        }
    }

    private void publish(String status) {
        String text = "任務 #" + task.id() + "｜" + label + "\n" + status
                + "\n本次階段耗時：" + TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
                + " 秒\n狀態更新時間（UTC）：" + Instant.now();
        if (messageTs == null) messageTs = slack.post(SlackNotifier.Identity.SYSTEM,
                task.slackChannel(), task.slackThreadTs(), text);
        else slack.update(SlackNotifier.Identity.SYSTEM, task.slackChannel(), messageTs, text, null);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        scheduler.shutdownNow();
        if (slack != null && messageTs != null) {
            try { publish("本次階段定期回報已結束；完成、受阻或中止結果請見任務主狀態與後續報告。"); }
            catch (Exception ignored) { /* 通知失敗不得蓋掉原本執行結果。 */ }
        }
    }
}
