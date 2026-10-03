package com.zipe.aiteam.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 停掉某個任務在 systemd 裡正在跑的 agent 與系統驗證。
 *
 * 每個 agent 都跑在 `<字首><任務>-<角色>-<時間>`，系統驗證跑在 `<字首>check-<任務>-<uuid>`；
 * 字首見 AiTeamProperties.unitPrefix()（單一實例為 `ai-team-`，多實例為 `ai-team-<實例>-`）。
 * 停掉 unit 等同讓那一輪的程序以非零結束，呼叫端會照常收到失敗，由流程引擎決定怎麼收尾。
 * 以前 Slack 沒有這條路，要中途換模型或止損只能 SSH 進 VM 自己下 systemctl。
 */
public final class TaskUnits {
    private static final Logger log = LoggerFactory.getLogger(TaskUnits.class);
    private TaskUnits() {}

    /**
     * systemctl 對已載入的 unit 比對 glob；任務編號後面一定接 '-'，不會誤停 #10170 這類編號。
     * 字首帶實例名，同一台 VM 上另一個專案服務的同編號任務不會被配到。
     * --no-block：只排入停止工作就返回。Slack 按鈕要在 3 秒內 ack，不能等 agent 真的結束。
     */
    static List<String> command(String unitPrefix, String taskId) {
        if (taskId == null || !taskId.matches("[0-9]+")) throw new IllegalArgumentException("無效任務 ID");
        if (unitPrefix == null || !unitPrefix.matches("ai-team-([a-z][a-z0-9]*-)?"))
            throw new IllegalArgumentException("無效 unit 字首");
        return List.of("systemctl", "--user", "--no-block", "stop",
                unitPrefix + taskId + "-*", unitPrefix + "check-" + taskId + "-*");
    }

    /** 盡力而為：失敗只記 log，不能讓通知或中止本身因此出錯。回傳是否在時限內送出並結束。 */
    public static boolean stop(String unitPrefix, String taskId) {
        try {
            Process process = new ProcessBuilder(command(unitPrefix, taskId)).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); return false; }
            return process.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("停止任務 {} 的執行單元失敗：{}", taskId, e.getClass().getSimpleName());
            return false;
        }
    }
}
