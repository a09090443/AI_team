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
    /** 執行中是否附「中斷這一輪」按鈕；只有流程引擎能接住中斷的階段才開。 */
    private final boolean interruptible;
    private final long started = System.nanoTime();
    private final ScheduledExecutorService scheduler;
    private String messageTs;
    private boolean closed;

    public StageProgress(SlackNotifier slack, TaskStore store, Task task, String label, long seconds) {
        this(slack, store, task, label, seconds, false);
    }

    public StageProgress(SlackNotifier slack, TaskStore store, Task task, String label, long seconds, boolean interruptible) {
        this.slack = slack; this.store = store; this.task = task; this.label = label; this.interruptible = interruptible;
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
                    + "這是系統定期狀態回報，不代表最近有工具活動。"
                    + (interruptible ? "\n需要停下這一輪（例如想換模型或方向錯了）可按下方按鈕；任務不會被中止。" : ""),
                    interruptible);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(StageProgress.class).warn("任務 {} 定期回報失敗：{}",
                    task.id(), e.getClass().getSimpleName());
        }
    }

    private void publish(String status, boolean withInterrupt) {
        String text = "任務 #" + task.id() + "｜" + label + "\n" + status
                + "\n本次階段耗時：" + TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
                + " 秒\n狀態更新時間（UTC）：" + Instant.now();
        // 結束時以 null blocks 更新，按鈕隨之消失，避免之後誤按到舊訊息。
        var blocks = withInterrupt ? SlackBlocks.progressBlocks(task.id(), text) : null;
        if (messageTs == null) messageTs = blocks == null
                ? slack.post(SlackNotifier.Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), text)
                : slack.post(SlackNotifier.Identity.SYSTEM, task.slackChannel(), task.slackThreadTs(), text, blocks);
        else slack.update(SlackNotifier.Identity.SYSTEM, task.slackChannel(), messageTs, text, blocks);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        scheduler.shutdownNow();
        if (slack != null && messageTs != null) {
            try { publish("本次階段定期回報已結束；完成、受阻或中止結果請見任務主狀態與後續報告。", false); }
            catch (Exception ignored) { /* 通知失敗不得蓋掉原本執行結果。 */ }
        }
    }
}
