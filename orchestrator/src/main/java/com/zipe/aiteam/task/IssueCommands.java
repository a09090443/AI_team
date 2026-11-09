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
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.element.BlockElement;
import jakarta.annotation.PostConstruct;
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
 * {@code issues} 列出開啟中的 issue（每張附「處理」按鈕）；{@code 處理 issue #12 [補充]}（或貼 issue 網址）讀取 issue 並建立任務。
 * 需求整理與直接對話的 agent 也可在報告的 github_issue_request 請系統列出或接手（{@link GitHubIssueFlow.Executor}）。
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
    /** 清單上的「處理」按鈕；同一則訊息的 action_id 不可重複，所以每顆加序號。value 只放 issue 編號，repo 由配置決定。 */
    public static final String ACTION = "ai_team_issue_handle";
    public static final Pattern ACTION_PATTERN = Pattern.compile("^" + ACTION + "(_\\d+)?$");

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
        submit(command, channel, threadTs, user, false);
        return true;
    }

    /** 清單上的「處理 #N」按鈕；呼叫端已檢查白名單。任務一律開在新討論串，清單串可以繼續按別張。 */
    public void button(String user, String value, String channel, String threadTs) {
        if (value == null || !value.matches("\\d{1,9}")) return;
        submit(new Command(Integer.parseInt(value), null, null, null), channel, threadTs, user, true);
    }

    /** 流程引擎轉交的 agent 請求：以任務提出者身分執行；接單一律開新討論串（原串已有任務）。 */
    @PostConstruct void register() {
        if (flowEngine != null) flowEngine.issueExecutor = (request, channel, threadTs, user) -> submit(
                new Command(request.list() ? 0 : request.number(), null, null, request.note()), channel, threadTs, user, true);
    }

    private void submit(Command command, String channel, String threadTs, String user, boolean newThread) {
        worker.execute(() -> {
            try { run(command, channel, threadTs, user, newThread); }
            catch (Exception e) {
                log.error("GitHub issue 指令失敗：{}", Diagnostics.describe(e));
                slack.post(Identity.SYSTEM, channel, threadTs, "GitHub issue 指令處理失敗：" + Diagnostics.clip(Diagnostics.safe(e.getMessage()), 300));
            }
        });
    }

    void run(Command command, String channel, String threadTs, String user, boolean newThread) throws PublishException {
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
        // 一個討論串只放一個工作任務：原串已有任務（或來自按鈕／agent 請求）時在頻道開新討論串。
        String taskThread = threadTs;
        if (newThread || threadTaken(channel, threadTs)) {
            taskThread = slack.post(Identity.SYSTEM, channel, null, "*GitHub issue " + repo + "#" + issue.number() + "：*"
                    + Diagnostics.clip(issue.title().replace('\n', ' '), 150) + "\n" + issue.url()
                    + "\n由 <@" + user + "> 指定處理；之後請在這個討論串操作。");
            if (taskThread == null) throw new IllegalStateException("無法在頻道建立新任務的討論串");
        }
        Task task = orchestrator.createFromIssue(channel, taskThread, user, text, new GitHubIssueFlow.Link(repo, issue));
        if (task == null) return;
        slack.post(Identity.SYSTEM, channel, taskThread, "已依 GitHub issue " + issue.url() + " 建立任務 #" + task.id()
                + "。需求不清楚時會直接在 issue 留言提問，PR 合併後自動關閉 issue。");
        if (!taskThread.equals(threadTs))
            slack.post(Identity.SYSTEM, channel, threadTs, "已在頻道開新討論串，依 issue " + repo + "#" + issue.number()
                    + " 建立任務 #" + task.id() + "。");
    }

    /** 與 TaskOrchestrator.submit 相同的判斷：討論串已有非對話的工作任務。 */
    boolean threadTaken(String channel, String threadTs) {
        return store.inThread(channel, threadTs).stream()
                .anyMatch(t -> store.direct(t.id()).isEmpty() && store.flow(t.id()).map(f -> !f.dialogue).orElse(true));
    }

    private void list(GitHubRepo repo, String channel, String threadTs) throws PublishException {
        List<GitHubIssues.Issue> open = issues.listOpen(repo, LIST_LIMIT);
        if (open.isEmpty()) { slack.post(Identity.SYSTEM, channel, threadTs, repo + " 目前沒有開啟中的 issue。"); return; }
        List<String> lines = new ArrayList<>();
        List<Integer> available = new ArrayList<>();
        for (var issue : open) {
            String owner = activeTaskFor(repo, issue.number());
            if (owner == null) available.add(issue.number());
            lines.add("• <" + issue.url() + "|#" + issue.number() + "> " + Diagnostics.clip(issue.title().replace('\n', ' '), 80)
                    + "（" + issue.author() + "）" + (owner == null ? "" : "　處理中：任務 #" + owner));
        }
        String header = "*" + repo + " 開啟中的 issue（最新 " + open.size() + " 筆）*";
        String footer = "按下面的「處理 #編號」建立任務（會在頻道開新討論串），或 " + slack.mention(Identity.SYSTEM)
                + " 處理 issue #編號（可在後面加補充說明）。";
        String text = header + "\n" + String.join("\n", lines) + "\n\n" + footer;
        if (available.isEmpty() || slack.post(Identity.SYSTEM, channel, threadTs, text, blocks(header, lines, footer, available)) == null)
            slack.post(Identity.SYSTEM, channel, threadTs, text);
    }

    /** section 文字上限 3000 字元，清單分段放；一個 actions block 最多 25 顆按鈕（清單上限 20）。 */
    static List<LayoutBlock> blocks(String header, List<String> lines, String footer, List<Integer> numbers) {
        List<LayoutBlock> blocks = new ArrayList<>();
        StringBuilder chunk = new StringBuilder(header);
        for (String line : lines) {
            if (chunk.length() + line.length() + 1 > 2900) { blocks.add(section(chunk.toString())); chunk = new StringBuilder(); }
            if (!chunk.isEmpty()) chunk.append('\n');
            chunk.append(line);
        }
        if (!chunk.isEmpty()) blocks.add(section(chunk.toString()));
        blocks.add(section(footer));
        List<BlockElement> buttons = new ArrayList<>();
        for (int i = 0; i < Math.min(numbers.size(), LIST_LIMIT); i++) {
            int index = i, number = numbers.get(i);
            buttons.add(com.slack.api.model.block.element.BlockElements.button(b -> b.actionId(ACTION + "_" + index)
                    .value(String.valueOf(number)).text(com.slack.api.model.block.composition.BlockCompositions.plainText("處理 #" + number))));
        }
        blocks.add(com.slack.api.model.block.Blocks.actions(a -> a.elements(buttons)));
        return blocks;
    }

    private static LayoutBlock section(String text) {
        return com.slack.api.model.block.Blocks.section(s -> s.text(com.slack.api.model.block.composition.BlockCompositions.markdownText(text)));
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
