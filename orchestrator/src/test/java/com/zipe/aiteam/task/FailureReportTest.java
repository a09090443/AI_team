package com.zipe.aiteam.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zipe.aiteam.agent.StageRunner;
import com.zipe.aiteam.agent.StageSpec;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.publish.PullRequestPublisher;
import com.zipe.aiteam.runtime.AgentRuntime;
import com.zipe.aiteam.slack.SlackNotifier;
import com.zipe.aiteam.store.AuditLog;
import com.zipe.aiteam.store.TaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FailureReportTest {
    @org.junit.jupiter.api.io.TempDir Path tmp;
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode blocked() throws Exception {
        return (ObjectNode) mapper.readTree("""
            {"execution_status":"BLOCKED","failure_report":{
              "operation":"git diff origin/develop...HEAD",
              "observed_error":"bwrap: .agents: Read-only file system",
              "evidence":"tool-call-42 /logs/review.jsonl",
              "impact":"未讀取 diff，無法核實程式碼",
              "cause_status":"UNKNOWN","cause":"尚未確認哪個掛載設定造成唯讀",
              "next_action":"查核該 sandbox unit 的掛載設定並重跑 git diff",
              "owner":"維運人員"}}
            """);
    }

    @Test void allRoleSchemasReceiveTheSameRequiredContract() throws Exception {
        for (String name : List.of("task", "triage", "adr", "impl", "qa", "qa-plan", "review")) {
            var schema = mapper.readTree(FailureReport.extendSchema(
                    Files.readString(Path.of("schemas", name + "-schema.json"))));
            assertThat(schema.path("required").toString()).contains("execution_status", "failure_report");
            assertThat(schema.path("properties").path("failure_report").path("required").size()).isEqualTo(8);
            assertThat(schema.path("properties").path("failure_report").path("type").toString())
                    .contains("null", "object");
            // 需要真人決定時的白話選擇題，所有角色共用同一份格式。
            assertThat(schema.path("required").toString()).contains("human_decision");
            var decision = schema.path("properties").path("human_decision");
            assertThat(decision.path("type").toString()).contains("null", "object");
            assertThat(decision.path("required").toString())
                    .contains("headline", "question", "options", "recommendation", "also_needed");
            assertThat(decision.path("properties").path("options").path("items").path("required").toString())
                    .contains("label", "consequence");
        }
    }

    @Test void instructionsTellAgentsToExplainDecisionsInPlainLanguage() {
        assertThat(FailureReport.INSTRUCTIONS).contains("human_decision").contains("白話")
                .contains("route=human").contains("also_needed");
    }

    /** #1022：「確認結案」按了五次，每次只是把選擇交回分流，流程沒有結案路線。 */
    @Test void instructionsForbidOptionsTheFlowCannotCarryOut() {
        assertThat(FailureReport.INSTRUCTIONS).contains("沒有「結案」路線")
                .contains("中止任務").contains("改用新任務繼續").contains("options 給空陣列");
    }

    @Test void reportsWithoutHumanDecisionStayValidSoOldReceiptsStillParse() throws Exception {
        var completed = mapper.readTree("{\"execution_status\":\"COMPLETED\",\"failure_report\":null}");
        assertThat(FailureReport.blockingReason("generic", completed)).isNull();
    }

    @Test void stageRunnerActuallySendsContractToRuntime() throws Exception {
        var props = mock(AiTeamProperties.class);
        when(props.homePath()).thenReturn(tmp);
        when(props.repoPath()).thenReturn(tmp);
        when(props.userHome()).thenReturn(tmp);
        when(props.mavenRepo()).thenReturn(tmp);
        when(props.roleRuntime(anyString())).thenReturn("fake");
        when(props.roleModel(anyString())).thenReturn("");
        when(props.taskTimeout()).thenReturn(java.time.Duration.ZERO);
        Files.createDirectories(tmp.resolve("prompts"));
        Files.createDirectories(tmp.resolve("schemas"));
        Files.writeString(tmp.resolve("prompts/pm.md"), "PM role");
        Files.writeString(tmp.resolve("schemas/task-schema.json"),
                Files.readString(Path.of("schemas/task-schema.json")));
        var registry = mock(com.zipe.aiteam.runtime.RuntimeRegistry.class);
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(registry.require("fake")).thenReturn(runtime);
        new StageRunner(props, registry, null).run(new Task("42", "c", "t", "u", "mail"),
                StageSpec.PM, null, "補充需求", "resume-42");
        var capture = org.mockito.ArgumentCaptor.forClass(AgentRuntime.RuntimeRequest.class);
        verify(runtime).execute(capture.capture(), any());
        assertThat(capture.getValue().prompt()).contains("補充需求", "cause_status", "不得杜撰");
        assertThat(capture.getValue().jsonSchema()).contains("execution_status", "observed_error");
    }

    @Test void missingSessionFallsBackOnceWithPinnedRuntimeModelAndWorkspace() throws Exception {
        var props = mock(AiTeamProperties.class);
        when(props.homePath()).thenReturn(tmp);
        when(props.repoPath()).thenReturn(tmp);
        when(props.userHome()).thenReturn(tmp);
        when(props.mavenRepo()).thenReturn(tmp);
        when(props.roleRuntime(anyString())).thenReturn("changed-runtime");
        when(props.roleModel(anyString())).thenReturn("changed-model");
        when(props.taskTimeout()).thenReturn(java.time.Duration.ZERO);
        Files.createDirectories(tmp.resolve("prompts"));
        Files.createDirectories(tmp.resolve("schemas"));
        Files.writeString(tmp.resolve("prompts/pm.md"), "PM role");
        Files.writeString(tmp.resolve("schemas/task-schema.json"), Files.readString(Path.of("schemas/task-schema.json")));
        var registry = mock(com.zipe.aiteam.runtime.RuntimeRegistry.class);
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.id()).thenReturn("original-runtime");
        when(registry.require("original-runtime")).thenReturn(runtime);
        var store = mock(TaskStore.class);
        var slack = mock(SlackNotifier.class);
        doAnswer(call -> { throw new AgentRuntime.RuntimeExecutionException("Session not found"); })
                .doAnswer(call -> {
                    java.util.function.Consumer<String> callback = call.getArgument(1);
                    callback.accept("new-session");
                    return null;
                }).when(runtime).execute(any(), any());
        Task task = new Task("42", "c", "t", "u", "mail");
        task.continuation(new Checkpoint("42", "pm", "original-runtime", "original-model", "old-session", "context", null, "quota"));
        new StageRunner(props, registry, null, store, slack).run(task, StageSpec.PM, "context", "繼續原任務", "old-session");
        var capture = org.mockito.ArgumentCaptor.forClass(AgentRuntime.RuntimeRequest.class);
        verify(runtime, times(2)).execute(capture.capture(), any());
        assertThat(capture.getAllValues().get(0).resumeSessionId()).isEqualTo("old-session");
        assertThat(capture.getAllValues().get(1).resumeSessionId()).isNull();
        for (var request : capture.getAllValues()) {
            assertThat(request.model()).isEqualTo("original-model");
            assertThat(request.workingDirectory()).isEqualTo(tmp);
        }
        verify(store).saveCheckpoint(argThat(cp -> "new-session".equals(cp.sessionId())));
        verify(slack).post(any(), eq("c"), eq("t"), contains("新 session"));
        verify(registry, never()).require("changed-runtime");
    }

    @Test void everyRoleReportsObservedErrorWithoutInventingRootCause() throws Exception {
        for (String role : List.of("pm", "architecture", "backend", "qa", "code_review")) {
            assertThat(FailureReport.blockingReason(role, blocked()))
                    .contains("git diff", "Read-only file system", "tool-call-42", "UNKNOWN",
                            "尚未確認哪個掛載", "維運人員", "未讀取 diff");
        }
    }

    @Test void vagueOrIncompleteFailureCannotPass() throws Exception {
        ObjectNode r = blocked();
        ((ObjectNode) r.path("failure_report")).remove("observed_error");
        assertThat(FailureReport.blockingReason("pm", r)).contains("observed_error", "尚未確認");
        ((ObjectNode) r.path("failure_report")).put("observed_error", " ");
        assertThat(FailureReport.blockingReason("pm", r)).contains("observed_error");
    }

    @Test void successLabelCannotHideFailedBuildOrLowConfidence() throws Exception {
        ObjectNode r = mapper.createObjectNode().put("execution_status", "COMPLETED").putNull("failure_report");
        r.put("confidence", "LOW");
        assertThat(FailureReport.blockingReason("pm", r)).isNotNull();
        r.put("confidence", "HIGH");
        assertThat(FailureReport.blockingReason("pm", r)).isNull();
        r.put("status", "SUCCESS");
        r.putObject("quality_gate").put("verify_passed", false);
        assertThat(FailureReport.blockingReason("backend", r)).isNotNull();
        r.put("verdict", "PASS_WITH_CONCERNS").put("verify_passed", false);
        assertThat(FailureReport.blockingReason("qa", r)).isNotNull();
    }

    @Test void blockedPmIsPersistedReportedAndNeverStartsDownstream() throws Exception {
        AiTeamProperties props = mock(AiTeamProperties.class);
        when(props.maxConcurrency()).thenReturn(1);
        StageRunner runner = mock(StageRunner.class);
        ObjectNode report = blocked();
        // 即使 PM 同時宣稱 QUESTION，仍須先處理 BLOCKED。
        report.put("task_type", "QUESTION");
        when(runner.run(any(), any(), any(), any(), any())).thenReturn(new AgentRuntime.RuntimeResult(
                "session-42", "raw", report, null, false, Path.of("/logs/pm.log"), "test", null));
        TaskStore store = mock(TaskStore.class);
        SlackNotifier slack = mock(SlackNotifier.class);
        PullRequestPublisher publisher = mock(PullRequestPublisher.class);
        TaskOrchestrator app = new TaskOrchestrator(props, runner, slack, store,
                mock(AuditLog.class), null, null, publisher);
        try {
            Task task = new Task("42", "channel", "thread", "user", "mail");
            ReflectionTestUtils.invokeMethod(app, "runPipeline", task);
            assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
            verify(store).saveStage(eq("42"), any());
            verify(runner, times(1)).run(eq(task), eq(StageSpec.PM), any(), any(), any());
            verify(slack).post(eq(SlackNotifier.Identity.SYSTEM), eq("channel"), eq("thread"),
                    contains("tool-call-42"));
            verifyNoInteractions(publisher);
        } finally { app.shutdown(); }
    }

    @Test void runtimeErrorFlagCannotBeTreatedAsSuccessfulOutput() throws Exception {
        AiTeamProperties props = mock(AiTeamProperties.class);
        when(props.maxConcurrency()).thenReturn(1);
        when(props.logsPath()).thenReturn(Path.of("/logs"));
        StageRunner runner = mock(StageRunner.class);
        when(runner.run(any(), any(), any(), any(), any())).thenReturn(new AgentRuntime.RuntimeResult(
                "s", "connection refused", mapper.createObjectNode(), null, true,
                Path.of("/logs/pm.log"), "test", null));
        TaskOrchestrator app = new TaskOrchestrator(props, runner, mock(SlackNotifier.class), mock(TaskStore.class),
                mock(AuditLog.class), null, null, mock(PullRequestPublisher.class));
        try {
            Task task = new Task("42", "channel", "thread", "user", "mail");
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(app, "runStage", task,
                    StageSpec.PM, null, "progress")).hasStackTraceContaining("isError=true")
                    .hasStackTraceContaining("connection refused");
            assertThat(task.stages()).isEmpty();
        } finally { app.shutdown(); }
    }
}
