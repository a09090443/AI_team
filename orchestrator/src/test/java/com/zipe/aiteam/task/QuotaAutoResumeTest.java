package com.zipe.aiteam.task;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.store.TaskStore;
import com.zipe.aiteam.slack.SlackNotifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class QuotaAutoResumeTest {
    @Test void 保存排程重啟沿用且同一到期只排一次() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:file:quota-test?mode=memory&cache=shared"));
        // 保持 SQLite 記憶體資料庫存活。
        try (var connection = jdbc.getDataSource().getConnection()) {
            jdbc.execute("CREATE TABLE task(id TEXT PRIMARY KEY,state TEXT,updated_at TEXT)");
            jdbc.execute("CREATE TABLE quota_retry(task_id TEXT PRIMARY KEY,observed TEXT,next_at TEXT,attempts INTEGER)");
            jdbc.update("INSERT INTO task VALUES('42','WAITING_QUOTA','2026-09-08T14:00:00Z')");
            var store = mock(TaskStore.class); var owner = mock(TaskOrchestrator.class);
            var props = mock(AiTeamProperties.class); var slack = mock(SlackNotifier.class);
            when(props.allowedUsers()).thenReturn(Set.of("u"));
            Task task = new Task("42","c","t","u","request"); task.state(Task.State.WAITING_QUOTA);
            when(store.find("42")).thenReturn(Optional.of(task));
            when(store.checkpoint("42")).thenReturn(Optional.of(new Checkpoint("42","implementation","claude-code","sonnet","session","context","head","You've hit your session limit · resets 6:30pm (UTC)")));
            var scheduler = new QuotaAutoResume(jdbc,store,owner,props,slack);
            scheduler.check(task,Instant.parse("2026-09-08T14:01:00Z")); scheduler.stop();
            assertThat(jdbc.queryForObject("SELECT next_at FROM quota_retry",String.class)).isEqualTo("2026-09-08T18:32:00Z");
            var restored = new QuotaAutoResume(jdbc,store,owner,props,slack);
            restored.check(task,Instant.parse("2026-09-08T18:31:00Z")); verifyNoInteractions(owner);
            restored.check(task,Instant.parse("2026-09-08T18:33:00Z"));
            restored.check(task,Instant.parse("2026-09-08T18:33:01Z")); verify(owner,times(1)).continueTask(task,"u");
            task.state(Task.State.ABORTED); restored.check(task,Instant.parse("2026-09-09T18:33:00Z"));
            verifyNoMoreInteractions(owner); restored.stop();
        } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
    }
    @Test void 等待人工不會自動執行() {
        var owner = mock(TaskOrchestrator.class); var store = mock(TaskStore.class);
        var timer = new QuotaAutoResume(mock(JdbcTemplate.class),store,owner,mock(AiTeamProperties.class),mock(SlackNotifier.class));
        Task task = new Task("42","c","t","u","request"); task.state(Task.State.AWAIT_HUMAN);
        timer.check(task,Instant.now()); verifyNoInteractions(owner,store); timer.stop();
    }
}
