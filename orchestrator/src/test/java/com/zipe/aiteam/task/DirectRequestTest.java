package com.zipe.aiteam.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zipe.aiteam.agent.*;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.runtime.*;
import com.zipe.aiteam.sandbox.ExecutionSandbox;
import com.zipe.aiteam.slack.SlackNotifier;
import com.zipe.aiteam.slack.SlackNotifier.Identity;
import com.zipe.aiteam.store.*;
import com.zipe.aiteam.workspace.WorkspaceManager;
import com.zipe.aiteam.publish.PullRequestPublisher;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DirectRequestTest {
    @TempDir Path tmp;
    TaskStore store;
    TaskOrchestrator app;
    StageRunner runner = mock(StageRunner.class);
    SlackNotifier slack = mock(SlackNotifier.class);
    WorkspaceManager workspace = mock(WorkspaceManager.class);
    PullRequestPublisher publisher = mock(PullRequestPublisher.class);
    AiTeamProperties props = mock(AiTeamProperties.class);
    @BeforeEach void setup() throws Exception {
        var ds = new org.sqlite.SQLiteDataSource(); ds.setUrl("jdbc:sqlite:" + tmp.resolve("test.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        store = new TaskStore(new JdbcTemplate(ds));
        when(props.maxConcurrency()).thenReturn(1); when(props.allowedUsers()).thenReturn(Set.of("u"));
        when(props.channelId()).thenReturn("c"); when(props.logsPath()).thenReturn(tmp);
        when(runner.run(any(), any(), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn(new AgentRuntime.RuntimeResult("session", "", new ObjectMapper().readTree("""
                    {"execution_status":"COMPLETED","failure_report":null,"answer":"應測 SMTP 故障與成功情境"}
                    """), 0.1, false, tmp.resolve("log"), "model", null));
        app = new TaskOrchestrator(props, runner, slack, store, mock(AuditLog.class), mock(ExecutionSandbox.class), workspace, publisher);
        app.shutdown(); // 本測試明確同步執行佇列工作，避免非決定性競態。
        ((Thread)ReflectionTestUtils.getField(app, "worker")).join(2000);
    }
    @AfterEach void close() { app.shutdown(); }

    @SuppressWarnings("unchecked")
    TaskOrchestrator.QueuedTask nextQueued() {
        return (TaskOrchestrator.QueuedTask) ((java.util.concurrent.LinkedBlockingQueue<Runnable>)
                ReflectionTestUtils.getField(app, "work")).remove();
    }

    @Test void queueNoticeUsesActualOrderAndNamesTheRunningTask() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        Task first = new Task("90", "c", "t90", "u", "先執行"); store.save(first);
        Task second = new Task("12", "c", "t12", "u", "較舊任務續跑"); store.save(second);
        Task third = new Task("91", "c", "t91", "u", "承接任務"); store.save(third);
        Task waiting = new Task("11", "c", "t11", "u", "等人"); waiting.state(Task.State.AWAIT_HUMAN); store.save(waiting);
        app.enqueue(new TaskOrchestrator.QueuedTask(first, () -> {
            entered.countDown();
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }));
        var firstJob = nextQueued();
        Thread execution = new Thread(() -> app.executeQueued(firstJob)); execution.start();
        try {
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            app.enqueue(new TaskOrchestrator.QueuedTask(second, () -> {}));
            app.enqueue(new TaskOrchestrator.QueuedTask(third, () -> {}));
            verify(slack).post(eq(Identity.SYSTEM), eq("c"), eq("t91"), argThat((String s) ->
                    s.contains("目前執行中：#90") && s.contains("前方排隊任務（依順序）：#12")
                            && !s.contains("#11") && s.contains("不需要重複送出")));
        } finally { release.countDown(); execution.join(2000); }
        app.executeQueued(nextQueued());
        app.executeQueued(nextQueued());
        var ordered = inOrder(slack);
        ordered.verify(slack).post(eq(Identity.SYSTEM), eq("c"), eq("t12"), contains("輪到任務 #12"));
        ordered.verify(slack).post(eq(Identity.SYSTEM), eq("c"), eq("t91"), contains("輪到任務 #91"));
    }

    @Test void cancelledQueueEntryIsNotReportedAsAheadOrStarted() {
        Task cancelled = new Task("50", "c", "t50", "u", "取消"); store.save(cancelled);
        Runnable action = mock(Runnable.class);
        app.enqueue(new TaskOrchestrator.QueuedTask(cancelled, action));
        cancelled.state(Task.State.ABORTED); store.save(cancelled);
        Task next = new Task("51", "c", "t51", "u", "下一個"); store.save(next);
        app.enqueue(new TaskOrchestrator.QueuedTask(next, () -> {}));
        verify(slack).post(eq(Identity.SYSTEM), eq("c"), eq("t51"), contains("目前前方沒有其他工作"));
        app.executeQueued(nextQueued());
        verifyNoInteractions(action);
        verify(slack, never()).post(eq(Identity.SYSTEM), eq("c"), eq("t50"), contains("輪到任務"));
    }

    @Test void failedQueueNoticeDoesNotDropTheJob() {
        when(slack.post(eq(Identity.SYSTEM), eq("c"), eq("t52"), anyString())).thenThrow(new RuntimeException("offline"));
        Task task = new Task("52", "c", "t52", "u", "通知失敗"); store.save(task);
        Runnable action = mock(Runnable.class);
        app.enqueue(new TaskOrchestrator.QueuedTask(task, action));
        app.executeQueued(nextQueued());
        verify(action).run();
    }

    @Test void newTaskGetsQueueNoticeBeforeAnyAgentRuns() {
        Task first = app.createAndSubmit("c", "first", "u", "第一項");
        Task second = app.createAndSubmit("c", "second", "u", "第二項");
        verify(slack).post(eq(Identity.SYSTEM), eq("c"), eq("second"), argThat((String s) ->
                s.contains("任務 #" + second.id()) && s.contains("前方排隊任務（依順序）：#" + first.id())));
        verifyNoInteractions(runner);
    }
    Task ask() {
        app.requestRole(Identity.QA, "c", "t", "u", "哪些情境尚未核實？");
        return store.inThread("c", "t").stream().filter(t -> store.direct(t.id()).isPresent()).reduce((a,b) -> b).orElseThrow();
    }
    void run(Task task) { ReflectionTestUtils.invokeMethod(app, "runDirect", task, store.direct(task.id()).orElseThrow()); }
    Task parent() {
        Task task = new Task("42", "c", "t", "u", "SMTP failover"); task.state(Task.State.AWAIT_HUMAN); store.save(task);
        store.saveCheckpoint(new Checkpoint("42", "qa", "claude-code", "sonnet", "qa-session", "qa-context", "head", "blocked"));
        return task;
    }
    @Test void consultationUsesRoleAndNeverMutatesParentOrPublishes() throws Exception {
        parent(); Task task = ask(); run(task);
        assertThat(store.direct(task.id()).orElseThrow().context()).contains("SMTP failover", "指定角色：QA");
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(store.checkpoint("42").orElseThrow().sessionId()).isEqualTo("qa-session");
        assertThat(task.state()).isEqualTo(Task.State.COMPLETED);
        verify(runner).run(eq(task), argThat(s -> s.role().equals("direct_qa") && s.writeScope() == StageSpec.WriteScope.NONE
                && s.allowedTools().equals(List.of("Read", "Grep", "Glob"))), anyString(), isNull(), isNull());
        verifyNoInteractions(publisher); verify(workspace, never()).provision(any());
    }
    @Test void followingConversationIncludesPriorAnswer() {
        Task first = ask(); run(first); Task next = ask();
        assertThat(store.direct(next.id()).orElseThrow().context()).contains("SMTP 故障與成功情境");
        assertThat(next.id()).isNotEqualTo(first.id());
    }
    @Test void directReplyDefaultsToBriefWhileFullAnswerRemainsStored() throws Exception {
        String full = "細".repeat(3000);
        when(runner.run(any(), any(), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn(new AgentRuntime.RuntimeResult("session", "", new ObjectMapper().readTree(
                        "{\"execution_status\":\"COMPLETED\",\"failure_report\":null,\"answer\":\"" + full + "\"}"),
                        null, false, tmp.resolve("log"), "model", null));
        Task task = ask(); run(task);

        verify(slack).post(eq(Identity.QA), eq("c"), eq("t"), argThat((String text) ->
                text.length() < 1500 && text.contains("簡要回覆") && text.contains("追問該角色")));
        assertThat(task.stages().values().iterator().next().result().path("answer").asText()).hasSize(3000);
    }
    @Test void otherThreadNeverEntersContext() {
        store.save(new Task("41", "c", "other", "u", "secret-other-task"));
        assertThat(store.direct(ask().id()).orElseThrow().context()).doesNotContain("secret-other-task");
    }
    @Test void duplicatePendingConversationRefused() { ask(); ask(); assertThat(store.inThread("c", "t")).hasSize(1); }
    @Test void noAuthorizationMeansNoNewTask() { app.requestRole(Identity.BACKEND, "c", "t", "stranger", "執行 修改"); assertThat(store.inThread("c", "t")).isEmpty(); }
    @Test void existingExecutionDoesNotCreateParallelPipeline() {
        parent(); app.requestRole(Identity.BACKEND, "c", "t", "u", "執行 補測試");
        assertThat(store.inThread("c", "t")).hasSize(1); verifyNoInteractions(runner, publisher);
    }
    @Test void directCannotApprovePrOrRedoAsPipeline() {
        Task task = ask(); app.approvePr(task, "u"); app.redo(task, "do it", "u");
        assertThat(task.redoCount()).isZero(); verifyNoInteractions(publisher, runner);
    }
    @Test void newConfigurationNeverRunsOrPublishesLegacyTaskAgainstNewProject() {
        var flow = mock(com.zipe.aiteam.flow.FlowEngine.class); when(flow.enabled()).thenReturn(true);
        ReflectionTestUtils.setField(app, "flowEngine", flow);
        Task old = parent(); app.approvePr(old, "u"); app.continueTask(old, "u"); app.redo(old, "修改", "u");
        app.requestTriage(old, "補充", "u");
        ReflectionTestUtils.invokeMethod(app, "runPipeline", old);
        verifyNoInteractions(runner, publisher); assertThat(old.redoCount()).isZero();
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.AWAIT_HUMAN);
    }
    @Test void quotaPausesAndResumesOnlyDirectRole() throws Exception {
        Task task = ask();
        var checkpoint = new Checkpoint(task.id(), "direct_qa", "claude-code", "sonnet", "direct-session", "context", null, "quota");
        store.saveCheckpoint(checkpoint);
        when(runner.run(any(), any(), nullable(String.class), nullable(String.class), nullable(String.class))).thenThrow(new QuotaExceeded("usage_limit_reached"));
        run(task); assertThat(task.state()).isEqualTo(Task.State.WAITING_QUOTA);
        reset(runner);
        when(runner.run(any(), any(), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn(new AgentRuntime.RuntimeResult("direct-session", "", new ObjectMapper().readTree("""
                    {"execution_status":"COMPLETED","failure_report":null,"answer":"完成回答"}
                    """), null, false, tmp.resolve("log"), "model", null));
        app.continueTask(task, "u");
        java.util.concurrent.LinkedBlockingQueue<Runnable> queue = (java.util.concurrent.LinkedBlockingQueue<Runnable>)ReflectionTestUtils.getField(app, "work");
        queue.remove(); // 原始 requestRole 的排隊工作
        queue.remove().run();
        verify(runner).run(any(), argThat(s -> s.role().equals("direct_qa")), anyString(), contains("續跑"), eq("direct-session"));
        assertThat(store.find(task.id()).orElseThrow().state()).isEqualTo(Task.State.COMPLETED);
        verifyNoInteractions(publisher);
    }
}
