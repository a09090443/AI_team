package com.zipe.aiteam.slack;

import com.zipe.aiteam.store.TaskStore;
import com.zipe.aiteam.task.Task;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StageProgressTest {
    @Test void 任務已不存在時不啟動排程() {
        var slack = mock(SlackNotifier.class); var store = mock(TaskStore.class);
        when(store.find("42")).thenReturn(Optional.empty());
        try (var progress = new StageProgress(slack, store, new Task("42", "c", "t", "u", "request"), "QA", 300)) {
            progress.tick(); verifyNoInteractions(slack);
        }
    }
    @Test void 更新同一訊息且關閉後不再回報() {
        var slack = mock(SlackNotifier.class); var store = mock(TaskStore.class);
        var task = new Task("42", "c", "t", "u", "request"); task.state(Task.State.STAGE_RUNNING);
        when(store.find("42")).thenReturn(Optional.of(task));
        when(slack.post(any(), eq("c"), eq("t"), anyString())).thenReturn("card");
        var progress = new StageProgress(slack, store, task, "QA／verification", 300);
        progress.tick();
        verify(slack, times(1)).post(any(), eq("c"), eq("t"), contains("QA／verification"));
        verify(slack).update(any(), eq("c"), eq("card"), contains("尚未核實完成"), isNull());
        progress.close(); clearInvocations(slack);
        progress.tick(); progress.close(); verifyNoInteractions(slack);
    }
    @Test void 可中斷階段附按鈕且結束後移除() {
        var slack = mock(SlackNotifier.class); var store = mock(TaskStore.class);
        var task = new Task("42", "c", "t", "u", "request"); task.state(Task.State.STAGE_RUNNING);
        when(store.find("42")).thenReturn(Optional.of(task));
        when(slack.post(any(), eq("c"), eq("t"), anyString(), anyList())).thenReturn("card");
        var progress = new StageProgress(slack, store, task, "Backend／implementation", 300, true);
        verify(slack).post(any(), eq("c"), eq("t"), contains("任務不會被中止"),
                argThat(blocks -> blocks != null && blocks.toString().contains(SlackBlocks.ACTION_INTERRUPT)));
        progress.close();
        verify(slack).update(any(), eq("c"), eq("card"), contains("定期回報已結束"), isNull());
    }
    @Test void 中止後停止心跳不宣稱成功() {
        var slack = mock(SlackNotifier.class); var store = mock(TaskStore.class);
        var task = new Task("42", "c", "t", "u", "request"); task.state(Task.State.STAGE_RUNNING);
        when(store.find("42")).thenReturn(Optional.of(task));
        when(slack.post(any(), anyString(), anyString(), anyString())).thenReturn("card");
        try (var progress = new StageProgress(slack, store, task, "Backend", 300)) {
            task.state(Task.State.ABORTED); progress.tick();
            verify(slack).update(any(), eq("c"), eq("card"), contains("定期回報已結束"), isNull());
            clearInvocations(slack); progress.tick(); verifyNoInteractions(slack);
        }
    }
    @Test void 通知失敗可重試且不向執行者拋出() {
        var slack = mock(SlackNotifier.class); var store = mock(TaskStore.class);
        var task = new Task("42", "c", "t", "u", "request"); task.state(Task.State.STAGE_RUNNING);
        when(store.find("42")).thenReturn(Optional.of(task));
        when(slack.post(any(), anyString(), anyString(), anyString())).thenThrow(new IllegalStateException()).thenReturn("card");
        try (var progress = new StageProgress(slack, store, task, "Backend", 300)) {
            progress.tick();
            verify(slack, times(2)).post(any(), anyString(), anyString(), anyString());
        }
    }
}
