package com.zipe.aiteam.task;

import com.zipe.aiteam.store.TaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PullRequestMonitorTest {
    @org.junit.jupiter.api.io.TempDir Path tmp;

    @Test void onlyPullRequestTasksAreCheckedAndOneFailureDoesNotStopTheRest() {
        var ds = new org.sqlite.SQLiteDataSource(); ds.setUrl("jdbc:sqlite:" + tmp.resolve("db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        var store = new TaskStore(new JdbcTemplate(ds));
        for (var entry : List.of(List.of("1044", "PR_CREATED"), List.of("1055", "PR_CREATED"), List.of("1056", "AWAIT_HUMAN"), List.of("9", "COMPLETED"))) {
            Task t = new Task(entry.get(0), "c", "t" + entry.get(0), "u", "r"); t.state(Task.State.valueOf(entry.get(1))); store.save(t);
        }
        var orchestrator = mock(TaskOrchestrator.class);
        doThrow(new IllegalStateException("GitHub 暫時失敗")).when(orchestrator).checkPullRequest(argThatId("1044"), any());

        new PullRequestMonitor(store, orchestrator).poll();

        assertThat(store.pullRequestTaskIds()).containsExactly("1044", "1055");
        verify(orchestrator).checkPullRequest(argThatId("1044"), org.mockito.ArgumentMatchers.isNull());
        verify(orchestrator).checkPullRequest(argThatId("1055"), org.mockito.ArgumentMatchers.isNull());
        verifyNoMoreInteractions(orchestrator);
    }

    private static Task argThatId(String id) {
        return org.mockito.ArgumentMatchers.argThat(t -> t != null && t.id().equals(id));
    }
}
