package com.zipe.aiteam.task;

import com.zipe.aiteam.flow.FlowEngine;
import com.zipe.aiteam.publish.GitHubIssues;
import com.zipe.aiteam.publish.GitHubRepo;
import com.zipe.aiteam.slack.SlackNotifier;
import com.zipe.aiteam.slack.SlackNotifier.Identity;
import com.zipe.aiteam.store.TaskStore;
import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.element.ButtonElement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IssueCommandsTest {
    @Test void parsesListAndHandleCommands() {
        assertThat(IssueCommands.parse("issues").list()).isTrue();
        assertThat(IssueCommands.parse("列出 issue").list()).isTrue();
        var handle = IssueCommands.parse("處理 issue #12 先看後端");
        assertThat(handle.number()).isEqualTo(12);
        assertThat(handle.note()).isEqualTo("先看後端");
        assertThat(IssueCommands.parse("處理Issue 7").number()).isEqualTo(7);
        var url = IssueCommands.parse("處理 <https://github.com/acme/api/issues/34|acme/api#34> 優先");
        assertThat(url.owner()).isEqualTo("acme");
        assertThat(url.repo()).isEqualTo("api");
        assertThat(url.number()).isEqualTo(34);
        assertThat(url.note()).isEqualTo("優先");
    }

    @Test void taskCommandsAndOrdinaryRequestsAreNotIssueCommands() {
        assertThat(IssueCommands.parse("處理 #12")).isNull();
        assertThat(IssueCommands.parse("幫我看 issues 列表怎麼做")).isNull();
        assertThat(IssueCommands.parse("處理 https://gitlab.com/acme/api/issues/3")).isNull();
    }

    @Test void listBlocksHaveOneUniqueButtonPerAvailableIssue() {
        var blocks = IssueCommands.blocks("*acme/api*", List.of("• #3 a", "• #5 b"), "按下面", List.of(3, 5));
        var actions = (ActionsBlock) blocks.get(blocks.size() - 1);
        var buttons = actions.getElements().stream().map(e -> (ButtonElement) e).toList();
        assertThat(buttons).extracting(ButtonElement::getValue).containsExactly("3", "5");
        assertThat(buttons).extracting(ButtonElement::getActionId).doesNotHaveDuplicates()
                .allMatch(id -> IssueCommands.ACTION_PATTERN.matcher(id).matches());
        assertThat(buttons.get(0).getText().getText()).isEqualTo("處理 #3");
    }

    @Test void buttonOrAgentRequestOpensNewThreadAndReportsBackInOriginal() throws Exception {
        TaskOrchestrator orchestrator = mock(TaskOrchestrator.class);
        TaskStore store = mock(TaskStore.class);
        SlackNotifier slack = mock(SlackNotifier.class);
        GitHubIssues issues = mock(GitHubIssues.class);
        FlowEngine engine = mock(FlowEngine.class);
        var commands = new IssueCommands(orchestrator, store, slack, issues);
        commands.flowEngine = engine;
        var repo = new GitHubRepo("acme", "api");
        when(engine.githubIssuesEnabled()).thenReturn(true);
        when(engine.repository()).thenReturn("/repo");
        when(issues.repositoryOf(any())).thenReturn(repo);
        when(store.unfinishedTaskIds()).thenReturn(List.of());
        when(issues.read(repo, 12)).thenReturn(new GitHubIssues.Issue(12, "匯出失敗", "500", "amy", "open", List.of(), "https://github.com/acme/api/issues/12"));
        when(issues.comments(repo, 12)).thenReturn(List.of());
        when(slack.post(eq(Identity.SYSTEM), eq("C1"), isNull(), anyString())).thenReturn("200.1");
        Task task = new Task("1050", "C1", "200.1", "U1", "x");
        when(orchestrator.createFromIssue(eq("C1"), eq("200.1"), eq("U1"), anyString(), any())).thenReturn(task);

        commands.run(new IssueCommands.Command(12, null, null, "先看後端"), "C1", "100.1", "U1", true);

        verify(orchestrator).createFromIssue(eq("C1"), eq("200.1"), eq("U1"), contains("使用者補充：先看後端"), any());
        verify(slack).post(eq(Identity.SYSTEM), eq("C1"), eq("100.1"), contains("建立任務 #1050"));
    }

    @Test void typedCommandStaysInFreeThreadButMovesOutOfTakenThread() {
        TaskStore store = mock(TaskStore.class);
        var commands = new IssueCommands(mock(TaskOrchestrator.class), store, mock(SlackNotifier.class), mock(GitHubIssues.class));
        when(store.inThread("C1", "100.1")).thenReturn(List.of());
        assertThat(commands.threadTaken("C1", "100.1")).isFalse();
        Task question = new Task("1042", "C1", "100.1", "U1", "可以取得 issue 嗎");
        when(store.inThread("C1", "100.1")).thenReturn(List.of(question));
        when(store.direct("1042")).thenReturn(java.util.Optional.empty());
        when(store.flow("1042")).thenReturn(java.util.Optional.empty());
        assertThat(commands.threadTaken("C1", "100.1")).isTrue();
    }
}
