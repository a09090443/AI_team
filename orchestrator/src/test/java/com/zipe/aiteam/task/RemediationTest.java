package com.zipe.aiteam.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zipe.aiteam.agent.*;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.runtime.*;
import com.zipe.aiteam.sandbox.ExecutionSandbox;
import com.zipe.aiteam.slack.SlackNotifier;
import com.zipe.aiteam.store.*;
import com.zipe.aiteam.workspace.WorkspaceManager;
import com.zipe.aiteam.publish.PullRequestPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RemediationTest {
    @TempDir Path tmp;
    static ObjectNode triage(String action) throws Exception {
        ObjectNode r = (ObjectNode)new ObjectMapper().readTree("""
            {"execution_status":"COMPLETED","failure_report":null,"summary":"已分析",
             "reason":"QA 缺少驗證","instructions":"依原情境補證據，不降低標準",
             "questions":[],"requires_human":false,"within_existing_scope":true}
            """);
        r.put("action", action);
        if (action.equals("HUMAN")) { r.put("requires_human", true); r.withArray("questions").add("是否接受重複寄送風險？"); }
        return r;
    }
    @Test void humanDecisionReallyRunsPmAndStops() throws Exception { exercise("human"); }
    @Test void technicalFixRunsBackendThenQaThenReviewer() throws Exception { exercise("backend"); }
    @Test void verificationFixRunsQaWithoutBackend() throws Exception { exercise("qa"); }
    @Test void repeatedFailuresStopAfterTwoRepairs() throws Exception { exercise("loop"); }
    @Test void quotaDuringPmPreservesDispatchAndPauses() throws Exception { exercise("quota"); }
    @Test void quotaResumeRunsOnlyCurrentRepairPhase() throws Exception { exercise("resume"); }
    @Test void contradictoryPmActionCannotDispatch() throws Exception {
        var r = triage("BACKEND"); r.put("requires_human", true);
        assertThat(TriageGate.blockingReason(r)).contains("人工");
        assertThat(FailureReport.blockingReason("pm_triage", r)).isNotNull();
        r = triage("HUMAN"); r.withArray("questions").removeAll();
        assertThat(TriageGate.blockingReason(r)).contains("問題");
    }
    void exercise(String mode) throws Exception {
        var props = mock(AiTeamProperties.class);
        when(props.maxConcurrency()).thenReturn(1); when(props.logsPath()).thenReturn(tmp);
        when(props.codeReviewEnabled()).thenReturn(true);
        var runner = mock(StageRunner.class); var store = mock(TaskStore.class);
        var slack = mock(SlackNotifier.class); var publisher = mock(PullRequestPublisher.class);
        var workspace = mock(WorkspaceManager.class);
        when(workspace.exists("42")).thenReturn(true);
        when(workspace.headSha("42")).thenReturn("head");
        when(workspace.changedPathsBetween(anyString(), anyString(), anyString())).thenReturn(List.of());
        var mapper = new ObjectMapper();
        Task task = new Task("42", "c", "t", "u", "mail");
        task.state(Task.State.QA_RUNNING); task.qaBaseline("before-qa"); task.statusMessageTs("status");
        task.putStage(new StageOutcome("pm", "pm", mapper.readTree("""
          {"execution_status":"COMPLETED","failure_report":null,"required_agents":["backend","qa"]}
          """), null, 1));
        task.putStage(new StageOutcome("qa_plan", "plan", QaGateTest.plan(), null, 1));
        var be = mapper.readTree("""
          {"execution_status":"COMPLETED","failure_report":null,"status":"SUCCESS","quality_gate":{"verify_passed":true}}
          """);
        task.putStage(new StageOutcome("backend", "be", be, null, 1));
        var failedQa = QaGateTest.report(); failedQa.withArray("uncertainties").add("尚未驗證");
        task.putStage(new StageOutcome("qa", "old-qa", failedQa, null, 1));
        var review = mapper.readTree("""
          {"execution_status":"COMPLETED","failure_report":null,"verification_status":"VERIFIED","confidence":"HIGH",
           "verdict":"APPROVE","blockers":[],"checked":["diff"],"findings":[]}
          """);
        AtomicReference<Remediation> saved = new AtomicReference<>();
        doAnswer(c -> { saved.set(c.getArgument(0)); return null; }).when(store).saveRemediation(any());
        when(store.remediation("42")).thenAnswer(c -> Optional.ofNullable(saved.get()));
        var cp = new Checkpoint("42", mode.equals("resume") ? "qa" : "pm_triage", "claude-code", "opus", "session", "context", "before-qa", "quota");
        when(store.checkpoint("42")).thenReturn(Optional.of(cp));
        if (mode.equals("resume")) task.continuation(cp);
        List<String> calls = new ArrayList<>();
        when(runner.run(any(), any(), nullable(String.class), nullable(String.class), nullable(String.class))).thenAnswer(c -> {
            StageSpec s = c.getArgument(1); calls.add(s.role());
            if (mode.equals("quota")) throw new QuotaExceeded("usage_limit_reached");
            var result = switch (s.role()) {
                case "pm_triage" -> triage(mode.equals("human") ? "HUMAN" : mode.equals("backend") ? "BACKEND" : "QA");
                case "backend" -> be;
                case "qa" -> mode.equals("loop") ? failedQa : QaGateTest.report();
                case "code_review" -> review;
                default -> throw new AssertionError("重跑了不應執行的階段 " + s);
            };
            return new AgentRuntime.RuntimeResult("session", "", result, null, false, tmp.resolve("log"), "model", null);
        });
        var app = new TaskOrchestrator(props, runner, slack, store, mock(AuditLog.class), mock(ExecutionSandbox.class), workspace, publisher);
        try {
            Remediation initial = new Remediation("42", mode.equals("resume") ? "qa" : "pm_triage", mode.equals("resume") ? 1 : 0, "尚未驗證");
            saved.set(initial);
            ReflectionTestUtils.invokeMethod(app, "runRemediation", task, initial);
            verify(workspace, never()).provision(any()); verifyNoInteractions(publisher);
            if (mode.equals("human")) {
                assertThat(calls).containsExactly("pm_triage");
                assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
                verify(slack).post(eq(SlackNotifier.Identity.PM), eq("c"), eq("t"), contains("補充 #42"));
            } else if (mode.equals("quota")) {
                assertThat(task.state()).isEqualTo(Task.State.WAITING_QUOTA);
                assertThat(saved.get().phase()).isEqualTo("pm_triage");
            } else if (mode.equals("loop")) {
                assertThat(calls).containsExactly("pm_triage", "qa", "pm_triage", "qa");
                assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
                assertThat(saved.get().repairs()).isEqualTo(2);
            } else {
                assertThat(calls).containsExactlyElementsOf(mode.equals("backend") ? List.of("pm_triage","backend","qa","code_review")
                        : mode.equals("resume") ? List.of("qa","code_review") : List.of("pm_triage","qa","code_review"));
                assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
                assertThat(saved.get().phase()).isEqualTo("DONE");
            }
        } finally { app.shutdown(); }
    }
}
