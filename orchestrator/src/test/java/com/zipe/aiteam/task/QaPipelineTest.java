package com.zipe.aiteam.task;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QaPipelineTest {
    @TempDir Path tmp;
    @Test void planPrecedesBackendAndBothReceiveSameScenario() throws Exception { exercise("success"); }
    @Test void blockedPlanStopsBeforeProvisionAndBackend() throws Exception { exercise("blocked-plan"); }
    @Test void missingScenarioStopsBeforeReviewerAndApproval() throws Exception { exercise("missing-result"); }
    @Test void quotaDuringPlanPausesWithoutProvisioning() throws Exception { exercise("quota-plan"); }
    @Test void resumedPlanReusesPmAndThenRunsBackend() throws Exception { exercise("resume-plan"); }

    void exercise(String mode) throws Exception {
        var props = mock(AiTeamProperties.class);
        when(props.maxConcurrency()).thenReturn(1);
        when(props.logsPath()).thenReturn(tmp);
        var runner = mock(StageRunner.class);
        var store = mock(TaskStore.class);
        var workspace = mock(WorkspaceManager.class);
        when(workspace.provision(any())).thenReturn(tmp);
        when(workspace.exists(anyString())).thenReturn(true);
        when(workspace.headSha(anyString())).thenReturn("head");
        when(workspace.changedPathsBetween(anyString(), anyString(), anyString())).thenReturn(List.of());
        var publisher = mock(PullRequestPublisher.class);
        var mapper = new ObjectMapper();
        var pm = mapper.readTree("""
            {"execution_status":"COMPLETED","failure_report":null,"task_type":"FEATURE",
             "confidence":"HIGH","required_agents":["backend","qa"]}
            """);
        var be = mapper.readTree("""
            {"execution_status":"COMPLETED","failure_report":null,"status":"SUCCESS",
             "summary":"BACKEND_ONLY_MARKER","quality_gate":{"verify_passed":true}}
            """);
        var plan = QaGateTest.plan();
        var qa = QaGateTest.report();
        if (mode.equals("blocked-plan")) plan.put("status", "BLOCKED");
        if (mode.equals("missing-result")) qa.withArray("scenario_results").removeAll();
        Task task = new Task("42", "c", "t", "u", "mail requirement");
        Checkpoint cp = new Checkpoint("42", "qa_plan", "claude-code", "sonnet", "plan-session", "original plan context", null, "quota");
        when(store.checkpoint("42")).thenReturn(Optional.of(cp));
        if (mode.equals("resume-plan")) {
            task.continuation(cp);
            task.putStage(new StageOutcome("pm", "pm", pm, null, 1));
        }
        List<String> calls = new ArrayList<>();
        when(runner.run(any(), any(), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenAnswer(call -> {
                    StageSpec stage = call.getArgument(1);
                    calls.add(stage.role());
                    String upstream = call.getArgument(2);
                    if (stage == StageSpec.QA_PLAN) {
                        assertThat(upstream).doesNotContain("BACKEND_ONLY_MARKER");
                        if (mode.equals("quota-plan")) throw new QuotaExceeded("usage_limit_reached");
                    }
                    if (stage == StageSpec.BACKEND || stage == StageSpec.QA) assertThat(upstream).contains("S1", "only primary receives once");
                    var result = switch (stage.role()) {
                        case "pm" -> pm;
                        case "qa_plan" -> plan;
                        case "backend" -> be;
                        case "qa" -> qa;
                        case "pm_triage" -> RemediationTest.triage("HUMAN");
                        default -> throw new AssertionError("Unexpected role");
                    };
                    return new AgentRuntime.RuntimeResult(stage.role(), "", result, null, false, tmp.resolve("log"), "sonnet", null);
                });
        var app = new TaskOrchestrator(props, runner, mock(SlackNotifier.class), store, mock(AuditLog.class),
                mock(ExecutionSandbox.class), workspace, publisher);
        try {
            ReflectionTestUtils.invokeMethod(app, "runPipeline", task);
            verifyNoInteractions(publisher);
            if (mode.endsWith("-plan") && !mode.equals("resume-plan")) {
                assertThat(calls).containsExactly("pm", "qa_plan");
                verify(workspace, never()).provision(any());
                assertThat(task.state()).isEqualTo(mode.equals("quota-plan") ? Task.State.WAITING_QUOTA : Task.State.AWAIT_HUMAN);
            } else {
                assertThat(calls).containsExactlyElementsOf(mode.equals("missing-result") ? List.of("pm", "qa_plan", "backend", "qa", "pm_triage") : mode.equals("resume-plan")
                        ? List.of("qa_plan", "backend", "qa") : List.of("pm", "qa_plan", "backend", "qa"));
                assertThat(task.state()).isEqualTo(mode.equals("missing-result") ? Task.State.AWAIT_HUMAN : Task.State.AWAIT_PR_APPROVAL);
                verify(store).saveStage(eq("42"), argThat(o -> o.role().equals("qa_plan")));
                if (mode.equals("resume-plan")) verify(runner).run(eq(task), eq(StageSpec.QA_PLAN), any(), contains("original plan context"), eq("plan-session"));
            }
        } finally { app.shutdown(); }
    }
}
