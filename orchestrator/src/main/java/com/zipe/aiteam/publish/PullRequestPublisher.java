package com.zipe.aiteam.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 推分支並開 PR —— 管線唯一需要人工核准才會發生的動作。
 *
 * 這一步把結果送到 repository 之外，是整條管線裡唯一不可逆、且對外可見的動作。
 * 因此它不在自動流程裡，只由 Slack 上的核准按鈕觸發（見 docs/interaction-model.md）。
 *
 * 不在沙箱內執行：它需要 GitHub token，而 token 絕不能進到 agent 的執行環境。
 */
@Component
public class PullRequestPublisher {

    private static final Logger log = LoggerFactory.getLogger(PullRequestPublisher.class);

    /** 支援 https 與 ssh 兩種 origin 寫法，取出 owner/repo。 */
    private static final Pattern REMOTE =
            Pattern.compile("github[.]com[:/]([^/]+)/([^/.]+)(?:[.]git)?/?$");

    private final AiTeamProperties props;
    private final WorkspaceManager workspaces;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public PullRequestPublisher(AiTeamProperties props, WorkspaceManager workspaces) {
        this.props = props;
        this.workspaces = workspaces;
    }

    public record Result(String branch, String prUrl, int prNumber) {}

    /**
     * 推分支並開 PR。
     *
     * @param taskId 任務編號，用來定位工作區與分支
     * @param title  PR 標題
     * @param body   PR 內文
     */
    public Result publish(String taskId, String title, String body) throws PublishException {
        return publish(taskId, title, body, workspaces.branchFor(taskId));
    }

    public Result publish(String taskId, String title, String body, String approvedBranch) throws PublishException {
        if (!props.hasGithubToken()) {
            throw new PublishException(
                    "未設定 AI_TEAM_GITHUB_TOKEN，無法推分支或開 PR。"
                  + "請在 ~/ai-team/env.sh 加入該變數後重啟服務"
                  + "（classic token 需 repo scope；fine-grained 需 Contents:write 與 Pull requests:write）。");
        }

        Path ws = workspaces.pathFor(taskId);
        if (!java.nio.file.Files.isDirectory(ws.resolve(".git"))) {
            throw new PublishException("工作區不存在或不是 git repository：" + ws
                    + "（可能已被啟動清理刪除，需要重跑任務）");
        }

        String branch = approvedBranch;
        String base = readBaseBranch(ws);
        String origin = git(ws, "remote", "get-url", "origin");
        Repo repo = parseRemote(origin);

        push(ws, branch);
        log.info("任務 {} 的分支 {} 已推送至 {}/{}", taskId, branch, repo.owner(), repo.name());

        return openPullRequest(repo, branch, base, title, body);
    }

    // ------------------------------------------------------------------ git

    /**
     * 推分支。
     *
     * token 透過環境變數與一個臨時的 credential helper 傳入，
     * 不寫進 .git/config、也不出現在指令參數裡 ——
     * 前者會把 token 留在工作區檔案中，後者會出現在 ps 的輸出。
     */
    private void push(Path ws, String branch) throws PublishException {
        String helper = "!f() { echo username=x-access-token; "
                      + "echo password=$AI_TEAM_GITHUB_TOKEN; }; f";
        gitWithToken(ws, helper, "push", "--set-upstream", "origin", branch);
    }

    /** provision 時寫入的基準分支。讀不到就是工作區不完整，不猜。 */
    private String readBaseBranch(Path ws) throws PublishException {
        String base = git(ws, "config", "--local", "--get", WorkspaceManager.BASE_BRANCH_CONFIG_KEY);
        if (base.isBlank()) {
            throw new PublishException("工作區沒有記錄基準分支（"
                    + WorkspaceManager.BASE_BRANCH_CONFIG_KEY + "），無法決定 PR 要合併到哪裡。");
        }
        return base;
    }

    private record Repo(String owner, String name) {}

    private Repo parseRemote(String url) throws PublishException {
        Matcher m = REMOTE.matcher(url.trim());
        if (!m.find()) {
            throw new PublishException("認不得的 origin 位址，只支援 GitHub：" + url);
        }
        return new Repo(m.group(1), m.group(2));
    }

    private String git(Path cwd, String... args) throws PublishException {
        return runGit(cwd, null, args);
    }

    private void gitWithToken(Path cwd, String helper, String... args) throws PublishException {
        List<String> full = new ArrayList<>(List.of("-c", "credential.helper=", "-c",
                "credential.helper=" + helper));
        full.addAll(List.of(args));
        runGit(cwd, props.githubToken(), full.toArray(new String[0]));
    }

    private String runGit(Path cwd, String token, String... args) throws PublishException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));

        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);
            if (token != null) {
                pb.environment().put("AI_TEAM_GITHUB_TOKEN", token);
                // 不要讓 git 因為沒有 tty 而跳出互動式提示卡住
                pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            }
            p = pb.start();

            String out;
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!p.waitFor(props.gitTimeout().toSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new PublishException("git 逾時：" + redact(String.join(" ", cmd)));
            }
            if (p.exitValue() != 0) {
                throw new PublishException("git 失敗（結束碼 " + p.exitValue() + "）："
                        + redact(String.join(" ", cmd)) + System.lineSeparator() + redact(out));
            }
            return out;
        } catch (PublishException e) {
            throw e;
        } catch (InterruptedException e) {
            if (p != null) p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new PublishException("git 執行被中斷", e);
        } catch (IOException e) {
            throw new PublishException("無法執行 git", e);
        }
    }

    /** 錯誤訊息會進 log 與 Slack，token 不能跟著出去。 */
    private String redact(String s) {
        if (s == null) return "";
        String token = props.githubToken();
        return token.isEmpty() ? s : s.replace(token, "***");
    }

    // ------------------------------------------------------------------ GitHub API

    private Result openPullRequest(Repo repo, String head, String base, String title, String body)
            throws PublishException {

        ObjectNode payload = mapper.createObjectNode();
        payload.put("title", title);
        payload.put("head", head);
        payload.put("base", base);
        payload.put("body", body);

        String url = "https://api.github.com/repos/" + repo.owner() + "/" + repo.name() + "/pulls";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + props.githubToken())
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 201) {
                JsonNode created = mapper.readTree(resp.body());
                return new Result(head,
                        created.path("html_url").asText(""),
                        created.path("number").asInt());
            }

            // 分支推上去了但 PR 開不了，是最容易讓人困惑的狀態 —— 訊息要說清楚。
            throw new PublishException("開 PR 失敗（HTTP " + resp.statusCode() + "）。"
                    + "分支 " + head + " 已經推上去了，可以手動開 PR。"
                    + "GitHub 回應：" + redact(abbreviate(resp.body())));

        } catch (PublishException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishException("呼叫 GitHub API 時被中斷", e);
        } catch (IOException e) {
            throw new PublishException("呼叫 GitHub API 失敗：" + e, e);
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() <= 600 ? s : s.substring(0, 600) + "…";
    }

    public static class PublishException extends Exception {
        public PublishException(String message) { super(message); }
        public PublishException(String message, Throwable cause) { super(message, cause); }
    }
}
