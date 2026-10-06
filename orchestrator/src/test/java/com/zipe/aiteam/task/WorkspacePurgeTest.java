package com.zipe.aiteam.task;

import com.zipe.aiteam.agent.StageRunner;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.publish.PullRequestPublisher;
import com.zipe.aiteam.sandbox.ExecutionSandbox;
import com.zipe.aiteam.slack.SlackNotifier;
import com.zipe.aiteam.store.AuditLog;
import com.zipe.aiteam.store.TaskStore;
import com.zipe.aiteam.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.List;
import static org.mockito.Mockito.*;

/** 啟動清理：負責人結案（OWNER_CLOSE）的 ABORTED 任務保留工作區，一般中止照舊刪除。 */
class WorkspacePurgeTest {
    @TempDir Path tmp;

    @Test void ownerClosedTaskKeepsItsWorkspaceWhileAPlainAbortIsDiscarded() throws Exception {
        var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmp.resolve("purge.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        var store = new TaskStore(jdbc);
        var audit = new AuditLog(jdbc);
        for (String id : List.of("1022", "1023")) {
            Task task = new Task(id, "c", "t-" + id, "u", "req");
            task.state(Task.State.ABORTED);
            store.save(task);
        }
        audit.record("1022", "u", AuditLog.ACTION_OWNER_CLOSE, "負責人結案（工作區保留）");

        var props = mock(AiTeamProperties.class);
        when(props.maxConcurrency()).thenReturn(1);
        when(props.logsPath()).thenReturn(tmp);
        var workspace = mock(WorkspaceManager.class);
        when(workspace.listTaskIds()).thenReturn(List.of("1022", "1023"));
        var app = new TaskOrchestrator(props, mock(StageRunner.class), mock(SlackNotifier.class), store, audit,
                mock(ExecutionSandbox.class), workspace, mock(PullRequestPublisher.class));
        try {
            ReflectionTestUtils.invokeMethod(app, "purgeWorkspaces");
            verify(workspace).discard("1023");
            verify(workspace, never()).discard("1022");
        } finally { app.shutdown(); }
    }
}
