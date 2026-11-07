package com.zipe.aiteam.task;

import com.zipe.aiteam.agent.StageRunner;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.publish.PullRequestPublisher;
import com.zipe.aiteam.sandbox.ExecutionSandbox;
import com.zipe.aiteam.slack.SlackBlocks;
import com.zipe.aiteam.slack.SlackNotifier;
import com.zipe.aiteam.slack.SlackNotifier.Identity;
import com.zipe.aiteam.store.AuditLog;
import com.zipe.aiteam.store.TaskStore;
import com.zipe.aiteam.workspace.WorkspaceManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 取消排隊中的任務：直接 ABORTED、移出佇列、不啟動 agent，且與「輪到執行」不會同時成立。 */
class QueueCancelTest {
    @TempDir Path tmp;
    TaskStore store;
    TaskOrchestrator app;
    SlackNotifier slack = mock(SlackNotifier.class);
    AiTeamProperties props = mock(AiTeamProperties.class);

    @BeforeEach void setup() throws Exception {
        var ds = new org.sqlite.SQLiteDataSource(); ds.setUrl("jdbc:sqlite:" + tmp.resolve("test.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        store = new TaskStore(new JdbcTemplate(ds));
        when(props.maxConcurrency()).thenReturn(1); when(props.allowedUsers()).thenReturn(Set.of("u"));
        when(props.channelId()).thenReturn("c"); when(props.logsPath()).thenReturn(tmp);
        app = new TaskOrchestrator(props, mock(StageRunner.class), slack, store, mock(AuditLog.class),
                mock(ExecutionSandbox.class), mock(WorkspaceManager.class), mock(PullRequestPublisher.class));
        app.shutdown(); // 同步執行佇列工作，競態由測試自己控制。
        ((Thread) ReflectionTestUtils.getField(app, "worker")).join(2000);
    }
    @AfterEach void close() { app.shutdown(); }

    @SuppressWarnings("unchecked")
    LinkedBlockingQueue<Runnable> work() {
        return (LinkedBlockingQueue<Runnable>) ReflectionTestUtils.getField(app, "work");
    }

    Task saved(String id, Task.State state) {
        Task t = new Task(id, "c", "t" + id, "u", "需求 " + id);
        t.state(state); store.save(t);
        return t;
    }

    @Test void cancelRemovesQueuedTaskWithoutStartingAnyAgent() {
        Task duplicate = saved("60", Task.State.QUEUED);
        Runnable action = mock(Runnable.class);
        app.enqueue(new TaskOrchestrator.QueuedTask(duplicate, action));

        assertThat(app.cancelQueued(duplicate, "u")).isTrue();

        assertThat(store.find("60").orElseThrow().state()).isEqualTo(Task.State.ABORTED);
        assertThat(work()).isEmpty();
        verifyNoInteractions(action);
        verify(slack).post(eq(Identity.SYSTEM), eq("c"), eq("t60"), contains("已由 <@u> 取消排隊"));
    }

    @Test void resumeAndTriageQueuedTasksCanAlsoBeCancelled() {
        for (var state : new Task.State[]{Task.State.RESUME_QUEUED, Task.State.TRIAGE_QUEUED}) {
            Task t = saved(state == Task.State.RESUME_QUEUED ? "61" : "62", state);
            assertThat(app.cancelQueued(t, "u")).isTrue();
            assertThat(store.find(t.id()).orElseThrow().state()).isEqualTo(Task.State.ABORTED);
        }
    }

    @Test void cancelIsRefusedOnceTheTaskHasStartedEvenBeforeItsStateChanges() throws Exception {
        Task task = saved("63", Task.State.QUEUED);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        app.enqueue(new TaskOrchestrator.QueuedTask(task, () -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }));
        var job = (TaskOrchestrator.QueuedTask) work().remove();
        Thread execution = new Thread(() -> app.executeQueued(job)); execution.start();
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            // 狀態仍是 QUEUED（流程尚未改成執行中），但已輪到執行：不能標成中止。
            assertThat(app.cancelQueued(task, "u")).isFalse();
            assertThat(store.find("63").orElseThrow().state()).isEqualTo(Task.State.QUEUED);
            verify(slack).post(eq(Identity.SYSTEM), eq("c"), eq("t63"), contains("已經開始執行，無法取消排隊"));
        } finally { release.countDown(); execution.join(2000); }
    }

    @Test void jobAlreadyTakenFromTheQueueIsSkippedWhenCancelWinsTheRace() {
        Task task = saved("64", Task.State.QUEUED);
        Runnable action = mock(Runnable.class);
        app.enqueue(new TaskOrchestrator.QueuedTask(task, action));
        // 工作執行緒已從佇列取出、尚未進入 executeQueued 時，取消先完成。
        var job = (TaskOrchestrator.QueuedTask) work().remove();
        assertThat(app.cancelQueued(task, "u")).isTrue();
        app.executeQueued(job);
        verifyNoInteractions(action);
        verify(slack, never()).post(eq(Identity.SYSTEM), eq("c"), eq("t64"), contains("輪到任務"));
    }

    @Test void concurrentCancelAndStartNeverBothHappen() throws Exception {
        for (int i = 0; i < 50; i++) {
            Task task = saved(String.valueOf(100 + i), Task.State.QUEUED);
            var ran = new java.util.concurrent.atomic.AtomicBoolean();
            app.enqueue(new TaskOrchestrator.QueuedTask(task, () -> ran.set(true)));
            var job = (TaskOrchestrator.QueuedTask) work().remove();
            var start = new CountDownLatch(1);
            var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            Thread runner = new Thread(() -> { await(start); app.executeQueued(job); });
            Thread canceller = new Thread(() -> { await(start); cancelled.set(app.cancelQueued(task, "u")); });
            runner.start(); canceller.start(); start.countDown();
            runner.join(2000); canceller.join(2000);
            assertThat(ran.get() && cancelled.get()).as("任務 %s 不可同時執行又被取消", task.id()).isFalse();
            assertThat(ran.get() || cancelled.get()).isTrue();
            assertThat(store.find(task.id()).orElseThrow().state())
                    .isEqualTo(cancelled.get() ? Task.State.ABORTED : Task.State.QUEUED);
        }
    }

    @Test void nonQueuedStatesAreNotChanged() {
        Task waiting = saved("65", Task.State.AWAIT_HUMAN);
        assertThat(app.cancelQueued(waiting, "u")).isFalse();
        assertThat(store.find("65").orElseThrow().state()).isEqualTo(Task.State.AWAIT_HUMAN);
        verify(slack).post(eq(Identity.SYSTEM), eq("c"), eq("t65"), contains("目前不是排隊狀態"));
    }

    @Test void abortButtonOnQueuedTaskUsesTheAtomicCancel() {
        Task task = saved("66", Task.State.QUEUED);
        Runnable action = mock(Runnable.class);
        app.enqueue(new TaskOrchestrator.QueuedTask(task, action));
        app.abort(task, "u");
        assertThat(store.find("66").orElseThrow().state()).isEqualTo(Task.State.ABORTED);
        assertThat(work()).isEmpty();
        verify(slack).post(eq(Identity.SYSTEM), eq("c"), eq("t66"), contains("取消排隊"));
    }

    @Test void queueNoticeOffersCancelButtonOnlyWhenTheTaskMustWait() {
        Task first = saved("67", Task.State.QUEUED);
        Task second = saved("68", Task.State.QUEUED);
        app.enqueue(new TaskOrchestrator.QueuedTask(first, () -> {}));
        app.enqueue(new TaskOrchestrator.QueuedTask(second, () -> {}));
        verify(slack, never()).post(eq(Identity.SYSTEM), eq("c"), eq("t67"), anyString(), anyList());
        verify(slack).post(eq(Identity.SYSTEM), eq("c"), eq("t68"), contains("取消排隊"), argThat(blocks ->
                blocks != null && blocks.toString().contains(SlackBlocks.ACTION_CANCEL_QUEUED)));
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
