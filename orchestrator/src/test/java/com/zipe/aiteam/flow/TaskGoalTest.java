package com.zipe.aiteam.flow;

import com.zipe.aiteam.task.Task;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TaskGoalTest {
    @Test void 一行顯示需求並標出承接來源() {
        Task task = new Task("1042", "c", "t", "u", "確認建立測試環境時，\n安裝文件是否齊全");
        assertThat(TaskGoal.line(task, "1041")).isEqualTo("🎯 確認建立測試環境時， 安裝文件是否齊全（承接自 #1041）");
        assertThat(TaskGoal.line(task, (FlowRun) null)).isEqualTo("🎯 確認建立測試環境時， 安裝文件是否齊全");
    }

    @Test void 過長需求截短() {
        Task task = new Task("1", "c", "t", "u", "字".repeat(100));
        assertThat(TaskGoal.line(task, (String) null)).isEqualTo("🎯 " + "字".repeat(TaskGoal.MAX) + "…");
    }

    @Test void 沒有需求原文也不會出錯() {
        assertThat(TaskGoal.line(new Task("1", "c", "t", "u", null), (String) null)).isEqualTo("🎯 （沒有需求原文）");
    }
}
