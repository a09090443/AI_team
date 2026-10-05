package com.zipe.aiteam.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zipe.aiteam.agent.StageRunner;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.publish.PullRequestPublisher;
import com.zipe.aiteam.runtime.AgentRuntime;
import com.zipe.aiteam.sandbox.ExecutionSandbox;
import com.zipe.aiteam.slack.SlackNotifier;
import com.zipe.aiteam.store.AuditLog;
import com.zipe.aiteam.store.TaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewGateTest {
    private ObjectNode verified() throws Exception {
        return (ObjectNode) new ObjectMapper().readTree("""
            {"verdict":"COMMENT","summary":"已獨立核實", "confidence":"HIGH",
             "verification_status":"VERIFIED","blockers":[],
             "checked":["commit、diff 與程式碼"],"findings":[]}
            """);
    }

    @Test void verifiedCommentsCanReachApproval() throws Exception {
        assertThat(ReviewGate.blockingReason(verified())).isNull();
    }

    @Test void historicalMailReportCannotPass() throws Exception {
        ObjectNode r = verified();
        r.remove("verification_status");
        r.remove("blockers");
        r.put("confidence", "LOW");
        r.put("summary", "bwrap 無法建立 .agents：Read-only file system");
        assertThat(ReviewGate.blockingReason(r)).contains("無法建立 .agents");
    }

    @Test void unverifiedAndLowConfidenceAndBlockersAllStop() throws Exception {
        ObjectNode r = verified();
        r.put("verification_status", "UNVERIFIED");
        assertThat(ReviewGate.blockingReason(r)).isNotNull();
        r = verified();
        r.put("confidence", "LOW");
        assertThat(ReviewGate.blockingReason(r)).isNotNull();
        r = verified();
        r.withArray("blockers").add("git diff 無法執行，需修復環境");
        assertThat(ReviewGate.blockingReason(r)).contains("git diff");
    }

    @Test void requestChangesAndHighFindingsStopEvenWhenVerified() throws Exception {
        ObjectNode r = verified();
        r.put("verdict", "REQUEST_CHANGES");
        assertThat(ReviewGate.blockingReason(r)).isNotNull();
        r = verified();
        r.withArray("findings").addObject().put("severity", "HIGH");
        assertThat(ReviewGate.blockingReason(r)).isNotNull();
    }

    @Test void missingOrMalformedReportsFailClosed() throws Exception {
        assertThat(ReviewGate.blockingReason(null)).isNotNull();
        assertThat(ReviewGate.blockingReason(new ObjectMapper().createObjectNode())).isNotNull();
        ObjectNode r = verified();
        r.remove("checked");
        assertThat(ReviewGate.blockingReason(r)).isNotNull();
    }

    @Test void approvalAndQueuedPublishReloadStateAndNeverPublishBlockedReports() throws Exception {
        for (boolean queued : new boolean[]{false, true}) {
            TaskStore store = mock(TaskStore.class);
            SlackNotifier slack = mock(SlackNotifier.class);
            PullRequestPublisher publisher = mock(PullRequestPublisher.class);
            TaskOrchestrator orchestrator = new TaskOrchestrator(new AiTeamProperties(),
                    null, slack, store, mock(AuditLog.class), null, null, publisher);
            try {
                Task stale = new Task("1010", "channel", "thread", "user", "mail");
                stale.state(Task.State.AWAIT_PR_APPROVAL);
                Task fresh = new Task("1010", "channel", "thread", "user", "mail");
                fresh.state(Task.State.AWAIT_PR_APPROVAL);
                ObjectNode r = verified();
                r.put("confidence", "LOW");
                fresh.putStage(new StageOutcome("code_review", "session", r, null, 1));
                when(store.find("1010")).thenReturn(Optional.of(fresh));
                if (queued) ReflectionTestUtils.invokeMethod(orchestrator, "publish", stale, "user");
                else orchestrator.approvePr(stale, "user");
                assertThat(fresh.state()).isEqualTo(Task.State.AWAIT_HUMAN);
                verify(store).save(fresh);
                verify(slack).post(eq(SlackNotifier.Identity.SYSTEM), eq("channel"), eq("thread"),
                        contains("已向上回報並停止發布"));
                verifyNoInteractions(publisher);
                fresh.state(Task.State.ABORTED);
                ReflectionTestUtils.invokeMethod(orchestrator, "publish", stale, "user");
                assertThat(fresh.state()).isEqualTo(Task.State.ABORTED);
                verifyNoInteractions(publisher);
            } finally { orchestrator.shutdown(); }
        }
    }

    @Test void reviewRuntimeFailureEscalatesInsteadOfSkipping() throws Exception {
        StageRunner runner = mock(StageRunner.class);
        when(runner.run(any(), any(), any(), any(), any()))
                .thenThrow(new AgentRuntime.RuntimeExecutionException("sandbox initialization failed"));
        AiTeamProperties props = mock(AiTeamProperties.class);
        when(props.maxConcurrency()).thenReturn(1);
        when(props.codeReviewEnabled()).thenReturn(true);
        when(props.logsPath()).thenReturn(java.nio.file.Path.of("/tmp/test-logs"));
        SlackNotifier slack = mock(SlackNotifier.class);
        TaskOrchestrator orchestrator = new TaskOrchestrator(props, runner, slack,
                mock(TaskStore.class), mock(AuditLog.class), mock(ExecutionSandbox.class), null,
                mock(PullRequestPublisher.class));
        try {
            Task task = new Task("1010", "channel", "thread", "user", "mail");
            StageOutcome prior = new StageOutcome("pm", "session", verified(), null, 1);
            ReflectionTestUtils.invokeMethod(orchestrator, "runCodeReview", task, prior, null,
                    prior, prior, "progress");
            assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
            verify(slack).post(eq(SlackNotifier.Identity.SYSTEM), eq("channel"), eq("thread"),
                    contains("sandbox initialization failed"));
        } finally { orchestrator.shutdown(); }
    }

    /**
     * bestpartner #1022：schema 沒限定 severity，審查寫了 INFO，閘門判為無法判定等級而擋下已 APPROVE 的發布。
     * 配置式審查的 schema 只能允許閘門認得的等級（HIGH 會擋、MEDIUM／LOW 放行）。
     */
    @Test void teamReviewSchemaOnlyAllowsSeveritiesTheGateUnderstands() throws Exception {
        var schema = new ObjectMapper().readTree(java.nio.file.Path.of("teams/schemas/review.json").toFile());
        var values = new java.util.ArrayList<String>();
        schema.at("/properties/findings/items/properties/severity/enum").forEach(v -> values.add(v.asText()));
        assertThat(values).containsExactlyInAnyOrder("HIGH", "MEDIUM", "LOW");
        ObjectNode report = verified();
        report.putArray("findings").addObject().put("severity", "INFO").put("detail", "提醒");
        assertThat(ReviewGate.blockingReason(report)).contains("無法判定等級");
        ((ObjectNode) report.withArray("findings").get(0)).put("severity", "LOW");
        assertThat(ReviewGate.blockingReason(report)).isNull();
    }
}
