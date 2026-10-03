package com.zipe.aiteam.task;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 同串再 @主機器人：只有「補充」真的有效的狀態才叫使用者用它。 */
class ThreadTakenMessageTest {
    private static Task task(Task.State state) {
        Task t = new Task("1005", "C1", "1.0", "U1", "目前後端有哪些API");
        t.state(state);
        return t;
    }

    @Test void 已結束的任務不建議補充() {
        String msg = TaskOrchestrator.threadTakenMessage(task(Task.State.COMPLETED));
        assertThat(msg).contains("#1005 已結束（COMPLETED）").contains("@角色").contains("開新討論串")
                .doesNotContain("補充");
    }

    @Test void 等待人工時引導按查看選項按鈕而不是打字() {
        String msg = TaskOrchestrator.threadTakenMessage(task(Task.State.AWAIT_HUMAN));
        assertThat(msg).contains("查看目前可用選項").doesNotContain("補充 #").doesNotContain("#編號");
        assertThat(TaskOrchestrator.waitingForHuman(task(Task.State.WAITING_QUOTA))).isTrue();
        assertThat(TaskOrchestrator.waitingForHuman(task(Task.State.COMPLETED))).isFalse();
    }

    @Test void 執行中不建議補充() {
        assertThat(TaskOrchestrator.threadTakenMessage(task(Task.State.STAGE_RUNNING)))
                .contains("目前 STAGE_RUNNING").doesNotContain("補充");
    }

    @Test void 用字為繁體() {
        for (Task.State s : Task.State.values())
            assertThat(TaskOrchestrator.threadTakenMessage(task(s))).doesNotContain("关联");
    }
}
