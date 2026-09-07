package com.zipe.aiteam.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zipe.aiteam.agent.StageRunner;
import com.zipe.aiteam.agent.StageSpec;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.runtime.AgentRuntime;
import com.zipe.aiteam.runtime.QuotaExceeded;
import com.zipe.aiteam.sandbox.ExecutionSandbox;
import com.zipe.aiteam.slack.SlackNotifier;
import com.zipe.aiteam.store.TaskStore;
import com.zipe.aiteam.store.AuditLog;
import com.zipe.aiteam.workspace.WorkspaceManager;
import com.zipe.aiteam.publish.PullRequestPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QuotaContinuationTest {
    @TempDir Path tmp;
    @Test void checkpointSurvivesRestartAndContinuationClaimIsAtomic() {
        var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmp.resolve("test.db"));
        var ddl = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        ddl.execute(ds);
        TaskStore store = new TaskStore(new JdbcTemplate(ds));
        Task task = new Task("42", "c", "t", "u", "mail");
        task.state(Task.State.WAITING_QUOTA);
        store.save(task);
        Checkpoint cp = new Checkpoint("42", "qa", "claude-code", "sonnet", "session", "upstream", "original-head", "quota");
        store.saveCheckpoint(cp);
        ddl.execute(ds); // 冪等 migration 不破壞既有資料。
        TaskStore restarted = new TaskStore(new JdbcTemplate(ds));
        assertThat(restarted.checkpoint("42")).contains(cp);
        assertThat(restarted.claimContinuation("42")).isTrue();
        assertThat(restarted.claimContinuation("42")).isFalse();
        assertThat(restarted.find("42").orElseThrow().state()).isEqualTo(Task.State.RESUME_QUEUED);
    }

    @Test void resumedQaReusesWorkspaceAndOriginalAuditBaseline() throws Exception {
        exercise(false);
    }

    @Test void triageClaimAndRepairStateSurviveRestartWithoutOverwritingAttempts() throws Exception {
        var ds = new org.sqlite.SQLiteDataSource(); ds.setUrl("jdbc:sqlite:" + tmp.resolve("repair.db"));
        var ddl = new ResourceDatabasePopulator(new ClassPathResource("schema.sql")); ddl.execute(ds);
        var jdbc = new JdbcTemplate(ds); var store = new TaskStore(jdbc);
        Task task = new Task("43", "c", "t", "u", "mail"); task.state(Task.State.AWAIT_HUMAN); store.save(task);
        var r = new Remediation("43", "qa", 2, "PM instructions"); store.saveRemediation(r);
        store.saveStage("43", new StageOutcome("qa", "old", QaGateTest.report(), null, 1));
        store.saveStage("43", new StageOutcome("qa", "new", QaGateTest.report(), null, 1));
        ddl.execute(ds); var restarted = new TaskStore(jdbc);
        assertThat(restarted.remediation("43")).contains(r);
        assertThat(restarted.claimTriage("43")).isTrue(); assertThat(restarted.claimTriage("43")).isFalse();
        assertThat(restarted.find("43").orElseThrow().state()).isEqualTo(Task.State.TRIAGE_QUEUED);
        assertThat(jdbc.queryForObject("select count(*) from stage_attempt", Integer.class)).isEqualTo(2);
    }
    @Test void stillExhaustedQuotaPausesAgainWithoutPublishing() throws Exception {
        exercise(true);
    }

    private void exercise(boolean quota) throws Exception {
        var props = mock(AiTeamProperties.class);
        when(props.maxConcurrency()).thenReturn(1);
        when(props.logsPath()).thenReturn(tmp);
        when(props.codeReviewEnabled()).thenReturn(false);
        var workspace = mock(WorkspaceManager.class);
        when(workspace.exists("42")).thenReturn(true);
        when(workspace.pathFor("42")).thenReturn(tmp);
        when(workspace.headSha("42")).thenReturn("new-head");
        when(workspace.changedPathsBetween(anyString(), anyString(), anyString())).thenReturn(List.of("module/src/test/Test.java"));
        var runner = mock(StageRunner.class);
        var store = mock(TaskStore.class);
        var slack = mock(SlackNotifier.class);
        var publisher = mock(PullRequestPublisher.class);
        var mapper = new ObjectMapper();
        Task task = new Task("42", "c", "t", "u", "mail");
        task.state(Task.State.RESUME_QUEUED);
        Checkpoint cp = new Checkpoint("42", "qa", "claude-code", "sonnet", "qa-session", "saved context", "original-qa-head", "quota");
        task.continuation(cp);
        task.putStage(new StageOutcome("qa_plan", "plan", QaGateTest.plan(), null, 1));
        when(store.checkpoint("42")).thenReturn(Optional.of(cp));
        task.putStage(new StageOutcome("pm", "pm", mapper.readTree("""
                {"execution_status":"COMPLETED","failure_report":null,"task_type":"FEATURE",
                 "confidence":"HIGH","required_agents":["backend","qa"]}
                """), null, 1));
        task.putStage(new StageOutcome("backend", "be", mapper.readTree("""
                {"execution_status":"COMPLETED","failure_report":null,"status":"SUCCESS",
                 "quality_gate":{"verify_passed":true}}
                """), null, 1));
        if (quota) when(runner.run(any(), any(), any(), any(), any())).thenThrow(new QuotaExceeded("usage_limit_reached"));
        else when(runner.run(any(), any(), any(), any(), any())).thenReturn(new AgentRuntime.RuntimeResult("qa-session", "",
                mapper.readTree("""
                    {"execution_status":"COMPLETED","failure_report":null,"verdict":"PASS","verify_passed":true,
                     "scenario_results":[{"scenario_id":"S1","status":"PASS","actual_result":"delivered once","command":"mvn test","evidence":"surefire report"}]}
                    """), null, false, tmp.resolve("log"), "sonnet", null));
        var app = new TaskOrchestrator(props, runner, slack, store, mock(AuditLog.class),
                mock(ExecutionSandbox.class), workspace, publisher);
        try {
            ReflectionTestUtils.invokeMethod(app, "runPipeline", task);
            verify(runner, times(1)).run(eq(task), eq(StageSpec.QA), any(), contains("saved context"), eq("qa-session"));
            verify(workspace, never()).provision(any());
            verify(workspace, never()).discard(anyString());
            verifyNoInteractions(publisher);
            if (quota) {
                assertThat(task.state()).isEqualTo(Task.State.WAITING_QUOTA);
                verify(store).saveCheckpoint(argThat(c -> c.reason().contains("usage_limit_reached")));
            } else {
                assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
                verify(workspace).changedPathsBetween("42", "original-qa-head", "new-head");
            }
        } finally { app.shutdown(); }
    }
}
