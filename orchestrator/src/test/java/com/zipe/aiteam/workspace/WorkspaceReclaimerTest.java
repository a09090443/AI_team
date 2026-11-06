package com.zipe.aiteam.workspace;

import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.store.TaskStore;
import com.zipe.aiteam.task.DirectRequest;
import com.zipe.aiteam.task.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class WorkspaceReclaimerTest {
    @TempDir Path tmp;
    AiTeamProperties props;
    WorkspaceManager workspaces;
    TaskStore store;
    WorkspaceReclaimer reclaimer;
    final Instant now = Instant.parse("2026-11-05T12:00:00Z");
    final AtomicLong free = new AtomicLong();
    final Map<String, Task.State> states = new HashMap<>();
    final Map<String, Instant> updated = new HashMap<>();
    final List<String> stripped = new ArrayList<>();

    @BeforeEach void setUp() throws Exception {
        props = new AiTeamProperties();
        ReflectionTestUtils.setField(props, "workspacesPath", tmp.toString());
        workspaces = mock(WorkspaceManager.class);
        store = mock(TaskStore.class);
        when(workspaces.listTaskIds()).thenAnswer(i -> new ArrayList<>(new TreeSet<>(states.keySet())));
        when(store.find(anyString())).thenAnswer(i -> Optional.ofNullable(states.get((String) i.getArgument(0)))
                .map(s -> task(i.getArgument(0), s)));
        when(store.updatedAt(anyString())).thenAnswer(i -> Optional.ofNullable(updated.get((String) i.getArgument(0))));
        when(store.findUnfinished()).thenReturn(List.of());
        when(store.direct(anyString())).thenReturn(Optional.empty());
        when(workspaces.removeBuildOutputs(anyString())).thenAnswer(i -> {
            stripped.add(i.getArgument(0));
            free.addAndGet(1L << 30);
            return List.of("node_modules");
        });
        reclaimer = new WorkspaceReclaimer(props, workspaces, store);
        reclaimer.freeSpace = p -> free.get();
        reclaimer.clock = () -> now;
    }

    static Task task(String id, Task.State state) {
        return Task.rehydrate(id, "C", "1.0", "U", "req", state, null, 0, null, Instant.EPOCH);
    }

    void workspace(String id, Task.State state, Duration idle) {
        states.put(id, state);
        updated.put(id, now.minus(idle));
    }

    @Test void lowDiskPurgesFinishedTasksThenStripsOldestIdleUntilTarget() {
        workspace("1", Task.State.PR_CREATED, Duration.ofHours(2));
        workspace("2", Task.State.AWAIT_HUMAN, Duration.ofHours(5));
        workspace("3", Task.State.FAILED, Duration.ofHours(1));
        workspace("4", Task.State.STAGE_RUNNING, Duration.ofDays(3));
        workspace("5", Task.State.RESUME_QUEUED, Duration.ofDays(3));
        workspace("6", Task.State.AWAIT_HUMAN, Duration.ofDays(9));
        free.set(400L << 20);
        List<Set<String>> purgeSkips = new ArrayList<>();
        reclaimer.register(skip -> { purgeSkips.add(skip); return 0; }, id -> true);

        long after = reclaimer.ensureSpace(tmp, "6");

        assertThat(purgeSkips).containsExactly(Set.of("6"));
        // 最久沒動的先刪；執行中、排隊中與呼叫者自己不碰；回到 2 GB 就停。
        assertThat(stripped).containsExactly("2", "1");
        assertThat(after).isGreaterThanOrEqualTo(WorkspaceReclaimer.TARGET_FREE_BYTES);
    }

    @Test void enoughSpaceOrDisabledDoesNothing() {
        workspace("1", Task.State.PR_CREATED, Duration.ofDays(3));
        free.set(5L << 30);
        reclaimer.ensureSpace(tmp, null);
        ReflectionTestUtils.setField(props, "workspaceReclaim", "off");
        free.set(1L << 20);
        reclaimer.ensureSpace(tmp, null);
        reclaimer.maintain(null);
        assertThat(stripped).isEmpty();
    }

    @Test void idleSweepStripsOnlyTasksIdleLongerThanConfiguredAndAtMostHourly() {
        free.set(50L << 30);
        workspace("1", Task.State.PR_CREATED, Duration.ofHours(30));
        workspace("2", Task.State.AWAIT_HUMAN, Duration.ofHours(3));
        workspace("3", Task.State.IMPL_RUNNING, Duration.ofHours(48));
        workspace("4", Task.State.AWAIT_PR_APPROVAL, Duration.ofHours(48));
        reclaimer.register(skip -> 0, id -> !"4".equals(id)); // 4 視為歷史模式任務

        reclaimer.maintain(null);
        assertThat(stripped).containsExactly("1");

        workspace("2", Task.State.AWAIT_HUMAN, Duration.ofHours(30));
        reclaimer.maintain(null); // 一小時內不重掃
        assertThat(stripped).containsExactly("1");

        ReflectionTestUtils.setField(props, "workspaceReclaimIdleHours", 0L);
        reclaimer.clock = () -> now.plus(Duration.ofHours(2));
        reclaimer.maintain(null);
        assertThat(stripped).containsExactly("1");
    }

    @Test void skipsParentsReadByRunningDialoguesAndTasksResumedMeanwhile() {
        workspace("1", Task.State.PR_CREATED, Duration.ofDays(2));
        workspace("2", Task.State.AWAIT_HUMAN, Duration.ofDays(2));
        workspace("9", Task.State.DIRECT_RUNNING, Duration.ZERO);
        when(store.findUnfinished()).thenReturn(List.of(task("9", Task.State.DIRECT_RUNNING)));
        when(store.direct("9")).thenReturn(Optional.of(new DirectRequest("9", null, "1", "ctx")));
        // 2 在列出候選後、刪除前被續跑。
        when(store.find("2")).thenReturn(Optional.of(task("2", Task.State.AWAIT_HUMAN)), Optional.of(task("2", Task.State.RESUME_QUEUED)));
        free.set(100L << 20);
        List<Set<String>> purgeSkips = new ArrayList<>();
        reclaimer.register(skip -> { purgeSkips.add(skip); return 0; }, id -> true);

        reclaimer.ensureSpace(tmp, null);

        assertThat(purgeSkips).containsExactly(Set.of("1"));
        assertThat(stripped).isEmpty();
    }

    @Test void idleStatesExcludeRunningAndQueued() {
        assertThat(WorkspaceReclaimer.idle(Task.State.PR_CREATED)).isTrue();
        assertThat(WorkspaceReclaimer.idle(Task.State.WAITING_QUOTA)).isTrue();
        assertThat(WorkspaceReclaimer.idle(Task.State.QUEUED)).isFalse();
        assertThat(WorkspaceReclaimer.idle(Task.State.TRIAGE_QUEUED)).isFalse();
        assertThat(WorkspaceReclaimer.idle(Task.State.QA_RUNNING)).isFalse();
    }
}
