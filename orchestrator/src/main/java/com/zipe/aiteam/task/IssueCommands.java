package com.zipe.aiteam.task;

import com.zipe.aiteam.flow.FlowEngine;
import com.zipe.aiteam.flow.GitHubIssueFlow;
import com.zipe.aiteam.publish.GitHubIssues;
import com.zipe.aiteam.publish.GitHubRepo;
import com.zipe.aiteam.publish.PullRequestPublisher.PublishException;
import com.zipe.aiteam.runtime.Diagnostics;
import com.zipe.aiteam.slack.SlackNotifier;
import com.zipe.aiteam.slack.SlackNotifier.Identity;
import com.zipe.aiteam.store.TaskStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slack 上的 GitHub issue 指令（見 docs-site/docs/github-issues.md）：
 * {@code issues} 列出開啟中的 issue；{@code 處理 issue #12 [補充]}（或貼 issue 網址）讀取 issue 並建立任務。
 *
 * 呼叫 GitHub 在自己的單一執行緒做：Slack 事件入口不連網，同時讓「重複接單」檢查不會互相競爭。
 */
@Component
public class IssueCommands {
    private static final Logger log = LoggerFactory.getLogger(IssueCommands.class);
    static final Pattern LIST = Pattern.compile("^(?:issues|列出\\s*issues?)\\s*$", Pattern.CASE_INSENSITIVE);
    static final Pattern HANDLE = Pattern.compile("^處理\\s*issue\\s*#?(\\d+)(?:\\s+([\\s\\S]+))?$", Pattern.CASE_INSENSITIVE);
    /** Slack 會把網址包成 <url> 或 <url|文字>。 */
    static final Pattern HANDLE_URL = Pattern.compile(
            "^處理\\s+<?https://github[.]com/([^/\\s>|]+)/([^/\\s>|]+)/issues/(\\d+)[^\\s>|]*(?:\\|[^>]*)?>?(?:\\s+([\\s\\S]+))?$");
    static final int LIST_LIMIT = 20;

    /** 解析結果：number 為 0 代表列出；owner/repo 只有貼網址時才有。 */
    record Command(int number, String owner, String repo, String note) {
        boolean list() { return number == 0; }
    }

    static Command parse(String text) {
        String value = text == null ? "" : text.trim();
        if (LIST.matcher(value).matches()) return new Command(0, null, null, null);
        Matcher m = HANDLE.matcher(value);
        if (m.matches()) return new Command(Integer.parseInt(m.group(1)), null, null, m.group(2));
        m = HANDLE_URL.matcher(value);
        if (m.matches()) return new Command(Integer.parseInt(m.group(3)), m.group(1), m.group(2), m.group(4));
        return null;
    }

    private final TaskOrchestrator orchestrator;
    private final TaskStore store;
    private final SlackNotifier slack;
    private final GitHubIssues issues;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    FlowEngine flowEngine;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "github-issues"); t.setDaemon(true); return t;
    });

    public IssueCommands(TaskOrchestrator orchestrator, TaskStore store, SlackNotifier slack, GitHubIssues issues) {
        this.orchestrator = orchestrator; this.store = store; this.slack = slack; this.issues = issues;
    }

    /** 是 issue 指令就排入處理並回 true；呼叫端已檢查白名單。 */
    public boolean handle(String text, String channel, String threadTs, String user) {
        Command command = parse(text);
        if (command == null) return false;
        worker.execute(() -> {
            try { run(command, channel, threadTs, user); }
            catch (Exception e) {
                log.error("GitHub issue 指令失敗：{}", Diagnostics.describe(e));
                slack.post(Identity.SYSTEM, channel, threadTs, "GitHub issue 指令處理失敗：" + Diagnostics.clip(Diagnostics.safe(e.getMessage()), 300));
            }
        });
        return true;
    }

    private void run(Command command, String channel, String threadTs, String user) throws PublishException {
        if (flowEngine == null || !flowEngine.githubIssuesEnabled()) {
            slack.post(Identity.SYSTEM, channel, threadTs, "本實例的團隊配置沒有開啟 GitHub issue 接單（githubIssues），未建立任務。");
            return;
        }
        GitHubRepo repo = issues.repositoryOf(Path.of(flowEngine.repository()));
        if (repo == null) {
            slack.post(Identity.SYSTEM, channel, threadTs, "本實例管理的 repo 不在 GitHub 上，無法讀取 issue。");
            return;
        }
        if (command.list()) { list(repo, channel, threadTs); return; }
        if (command.owner() != null && !(command.owner().equalsIgnoreCase(repo.owner()) && command.repo().equalsIgnoreCase(repo.name()))) {
            slack.post(Identity.SYSTEM, channel, threadTs, "這個 issue 不屬於本實例管理的 repo（" + repo + "），未建立任務。");
            return;
        }
        String existing = activeTaskFor(repo, command.number());
        if (existing != null) {
            slack.post(Identity.SYSTEM, channel, threadTs, "issue " + repo + "#" + command.number() + " 已由任務 #" + existing
                    + " 處理中，未重複建立；請到該任務的討論串操作。");
            return;
        }
        GitHubIssues.Issue issue = issues.read(repo, command.number());
        if (!"open".equals(issue.state())) {
            slack.post(Identity.SYSTEM, channel, threadTs, "issue " + repo + "#" + issue.number() + " 已關閉，未建立任務。");
            return;
        }
        String text = GitHubIssueFlow.requestText(command.note(), repo, issue, issues.comments(repo, issue.number()));
        Task task = orchestrator.createFromIssue(channel, threadTs, user, text, new GitHubIssueFlow.Link(repo, issue));
        if (task != null)
            slack.post(Identity.SYSTEM, channel, threadTs, "已依 GitHub issue " + issue.url() + " 建立任務 #" + task.id()
                    + "。需求不清楚時會直接在 issue 留言提問，PR 合併後自動關閉 issue。");
    }

    private void list(GitHubRepo repo, String channel, String threadTs) throws PublishException {
        List<GitHubIssues.Issue> open = issues.listOpen(repo, LIST_LIMIT);
        if (open.isEmpty()) { slack.post(Identity.SYSTEM, channel, threadTs, repo + " 目前沒有開啟中的 issue。"); return; }
        List<String> lines = new ArrayList<>();
        for (var issue : open) {
            String owner = activeTaskFor(repo, issue.number());
            lines.add("• <" + issue.url() + "|#" + issue.number() + "> " + Diagnostics.clip(issue.title().replace('\n', ' '), 80)
                    + "（" + issue.author() + "）" + (owner == null ? "" : "　處理中：任務 #" + owner));
        }
        slack.post(Identity.SYSTEM, channel, threadTs, "*" + repo + " 開啟中的 issue（最新 " + open.size() + " 筆）*\n"
                + String.join("\n", lines) + "\n\n要處理某一張，請 " + slack.mention(Identity.SYSTEM) + " 處理 issue #編號（可在後面加補充說明）。");
    }

    /** 同一張 issue 已有未結束的任務（含 PR 未合併）時回該任務編號。 */
    String activeTaskFor(GitHubRepo repo, int number) {
        for (String id : store.unfinishedTaskIds()) {
            var link = store.flow(id).map(f -> f.githubIssue).orElse(null);
            if (link != null && !link.closed && link.number == number
                    && link.owner.equalsIgnoreCase(repo.owner()) && link.repo.equalsIgnoreCase(repo.name())) {
                boolean succeeded = store.flow(id).map(f -> f.successor != null).orElse(false);
                if (!succeeded) return id;
            }
        }
        return null;
    }

    @PreDestroy public void stop() { worker.shutdownNow(); }
}
