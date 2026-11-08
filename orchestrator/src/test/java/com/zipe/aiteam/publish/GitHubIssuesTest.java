package com.zipe.aiteam.publish;

import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.workspace.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitHubIssuesTest {
    AiTeamProperties props = mock(AiTeamProperties.class);
    GitHubIssues issues;
    List<String> sent = new ArrayList<>();
    GitHubIssues.Response next;
    GitHubRepo repo = new GitHubRepo("acme", "web.app");

    @BeforeEach void setup() {
        when(props.hasGithubToken()).thenReturn(true);
        when(props.githubToken()).thenReturn("secret-token");
        issues = new GitHubIssues(props, mock(WorkspaceManager.class));
        issues.transport = (method, url, body) -> { sent.add(method + " " + url + (body == null ? "" : " " + body)); return next; };
    }

    @Test void parsesGithubRemotesIncludingDotsInRepoNames() {
        assertThat(GitHubRepo.parse("https://github.com/acme/web.app.git")).isEqualTo(new GitHubRepo("acme", "web.app"));
        assertThat(GitHubRepo.parse("git@github.com:acme/api")).isEqualTo(new GitHubRepo("acme", "api"));
        assertThat(GitHubRepo.parse("https://gitlab.com/acme/api.git")).isNull();
    }

    @Test void listingSkipsPullRequests() throws Exception {
        next = new GitHubIssues.Response(200, """
                [{"number":3,"title":"壞掉","body":"b","user":{"login":"amy"},"state":"open","labels":[{"name":"bug"}],"html_url":"u3"},
                 {"number":4,"title":"PR","pull_request":{},"user":{"login":"bob"},"state":"open","labels":[],"html_url":"u4"}]""");
        var open = issues.listOpen(repo, 20);
        assertThat(open).extracting(GitHubIssues.Issue::number).containsExactly(3);
        assertThat(open.getFirst().labels()).containsExactly("bug");
        assertThat(sent.getFirst()).startsWith("GET https://api.github.com/repos/acme/web.app/issues?state=open");
    }

    @Test void readingAPullRequestNumberIsRejected() {
        next = new GitHubIssues.Response(200, "{\"number\":4,\"pull_request\":{}}");
        assertThatThrownBy(() -> issues.read(repo, 4)).hasMessageContaining("是 PR");
    }

    @Test void commentsCarryTheSystemMarkerAndCloseUsesCompletedReason() throws Exception {
        next = new GitHubIssues.Response(201, "{\"id\":55}");
        assertThat(issues.comment(repo, 3, "請問")).isEqualTo(55L);
        assertThat(sent.getLast()).startsWith("POST https://api.github.com/repos/acme/web.app/issues/3/comments").contains(GitHubIssues.MARKER.replace("\"", ""));
        next = new GitHubIssues.Response(200, "{}");
        issues.close(repo, 3);
        assertThat(sent.getLast()).startsWith("PATCH https://api.github.com/repos/acme/web.app/issues/3").contains("\"state_reason\":\"completed\"");
    }

    @Test void failuresNameThePermissionAndNeverLeakTheToken() {
        next = new GitHubIssues.Response(403, "denied secret-token");
        assertThatThrownBy(() -> issues.comments(repo, 3))
                .hasMessageContaining("Issues: Read and write").hasMessageNotContaining("secret-token");
    }

    @Test void missingTokenFailsBeforeCallingGithub() {
        when(props.hasGithubToken()).thenReturn(false);
        assertThatThrownBy(() -> issues.read(repo, 3)).hasMessageContaining("AI_TEAM_GITHUB_TOKEN");
        assertThat(sent).isEmpty();
    }
}
