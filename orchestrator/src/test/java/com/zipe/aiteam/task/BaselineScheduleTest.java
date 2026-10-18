package com.zipe.aiteam.task;

import com.zipe.aiteam.agent.StageRunner;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.flow.BaselineVerifier;
import com.zipe.aiteam.publish.PullRequestPublisher;
import com.zipe.aiteam.sandbox.ExecutionSandbox;
import com.zipe.aiteam.slack.SlackNotifier;
import com.zipe.aiteam.store.*;
import com.zipe.aiteam.workspace.WorkspaceManager;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 啟動時只在「目前配置＋基準 commit」還沒有結果時排一次基準驗證；只換 JAR 不重跑。 */
class BaselineScheduleTest {
    AiTeamProperties props = mock(AiTeamProperties.class);
    SlackNotifier slack = mock(SlackNotifier.class);
    BaselineVerifier baseline = mock(BaselineVerifier.class);
    TaskOrchestrator app;
    final String sha = "b".repeat(40);

    @BeforeEach void setup() throws Exception {
        when(props.maxConcurrency()).thenReturn(1); when(props.allowedUsers()).thenReturn(Set.of("u"));
        when(props.channelId()).thenReturn("c"); when(props.baselineVerifyOnStart()).thenReturn(true);
        when(baseline.enabled()).thenReturn(true);
        when(baseline.currentBase()).thenReturn(new WorkspaceManager.BaseRef("master", sha));
        when(slack.post(any(), any(), any(), anyString())).thenReturn("root-ts");
        app = spy(new TaskOrchestrator(props, mock(StageRunner.class), slack, mock(TaskStore.class), mock(AuditLog.class),
                mock(ExecutionSandbox.class), mock(WorkspaceManager.class), mock(PullRequestPublisher.class)));
        ReflectionTestUtils.setField(app, "baseline", baseline);
        doNothing().when(app).enqueue(any());
    }

    @Test void newConfigurationOrBaseCommitIsVerifiedOnce() {
        when(baseline.alreadyVerified(sha)).thenReturn(false);
        app.scheduleBaselineOnStartup();
        verify(slack).post(eq(SlackNotifier.Identity.SYSTEM), eq("c"), isNull(), contains("基準驗證"));
        verify(app).enqueue(argThat(job -> job instanceof TaskOrchestrator.QueuedTask q
                && BaselineVerifier.ID.equals(q.task().id()) && "root-ts".equals(q.task().slackThreadTs())));
    }

    @Test void alreadyVerifiedOrSwitchedOffDoesNothing() {
        when(baseline.alreadyVerified(sha)).thenReturn(true);
        app.scheduleBaselineOnStartup();
        when(baseline.alreadyVerified(sha)).thenReturn(false);
        when(props.baselineVerifyOnStart()).thenReturn(false);
        app.scheduleBaselineOnStartup();
        verify(app, never()).enqueue(any());
    }

    @Test void queueNoticesNameTheBaselineInsteadOfTaskZero() {
        assertThat(TaskOrchestrator.queueLabel(new Task(BaselineVerifier.ID, "c", "t", "u", "基準驗證"))).isEqualTo("基準驗證");
        assertThat(TaskOrchestrator.queueLabel(new Task("1001", "c", "t", "u", "x"))).isEqualTo("任務 #1001");
    }
}
