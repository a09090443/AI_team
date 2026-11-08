package com.zipe.aiteam.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.publish.PullRequestPublisher.PublishException;
import com.zipe.aiteam.workspace.WorkspaceManager;
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

/**
 * 讀取 GitHub issue、在 issue 留言與關閉 issue（見 docs-site/docs/github-issues.md）。
 *
 * 只由系統程式在固定時機呼叫，不提供給 agent：token 不進執行環境，agent 也不能自行對外留言或關單。
 * issue 內文與留言是外部使用者寫的內容，呼叫端必須當成資料包裝後才交給 agent。
 */
@Component
public class GitHubIssues {
    /** 系統留言的標記：讀留言時用來排除自己的留言，避免把自己的提問當成回覆。 */
    public static final String MARKER = "<!-- ai-team -->";
    static final int MAX_COMMENTS = 30;

    public record Issue(int number, String title, String body, String author, String state, List<String> labels, String url) {}
    public record Comment(long id, String author, String association, String body, String createdAt) {
        public boolean fromSystem() { return body != null && body.contains(MARKER); }
    }
    /** 測試替換用；正式環境以 HttpClient 呼叫 GitHub REST API。 */
    public interface Transport { Response send(String method, String url, String body) throws IOException, InterruptedException; }
    public record Response(int status, String body) {}

    private final AiTeamProperties props;
    private final WorkspaceManager workspaces;
    private final ObjectMapper mapper = new ObjectMapper();
    Transport transport;

    public GitHubIssues(AiTeamProperties props, WorkspaceManager workspaces) {
        this.props = props; this.workspaces = workspaces;
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        this.transport = (method, url, body) -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + props.githubToken())
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28");
            if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
            else b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(resp.statusCode(), resp.body());
        };
    }

    /** 由服務配置的本機主 repo 讀 origin；不是 GitHub 時回 null。 */
    public GitHubRepo repositoryOf(Path localRepository) throws PublishException {
        try { return GitHubRepo.parse(workspaces.repositoryOrigin(localRepository)); }
        catch (Exception e) { throw new PublishException("讀不到主 repo 的 origin：" + e.getMessage(), e); }
    }

    /** 開啟中的 issue（GitHub 的 issues API 也會列出 PR，這裡排除）。 */
    public List<Issue> listOpen(GitHubRepo repo, int limit) throws PublishException {
        JsonNode items = call("GET", repo.apiBase() + "/issues?state=open&sort=created&direction=desc&per_page=" + Math.max(1, Math.min(limit, 50)), null, 200);
        List<Issue> out = new ArrayList<>();
        for (JsonNode item : items) if (!item.has("pull_request")) out.add(issue(item));
        return out;
    }

    public Issue read(GitHubRepo repo, int number) throws PublishException {
        JsonNode item = call("GET", repo.apiBase() + "/issues/" + number, null, 200);
        if (item.has("pull_request")) throw new PublishException("#" + number + " 是 PR，不是 issue。");
        return issue(item);
    }

    /** 最近的留言（最多 {@value #MAX_COMMENTS} 則，舊到新）。 */
    public List<Comment> comments(GitHubRepo repo, int number) throws PublishException {
        JsonNode items = call("GET", repo.apiBase() + "/issues/" + number + "/comments?per_page=100", null, 200);
        List<Comment> out = new ArrayList<>();
        for (JsonNode c : items) out.add(new Comment(c.path("id").asLong(), c.path("user").path("login").asText(""),
                c.path("author_association").asText(""), c.path("body").asText(""), c.path("created_at").asText("")));
        return out.size() <= MAX_COMMENTS ? out : out.subList(out.size() - MAX_COMMENTS, out.size());
    }

    /** 留言並回傳留言 id；內文自動加上系統標記。 */
    public long comment(GitHubRepo repo, int number, String body) throws PublishException {
        ObjectNode payload = mapper.createObjectNode().put("body", body + "\n\n" + MARKER);
        return call("POST", repo.apiBase() + "/issues/" + number + "/comments", payload.toString(), 201).path("id").asLong();
    }

    /** 以「已完成」關閉 issue；已關閉時 GitHub 照樣回 200。 */
    public void close(GitHubRepo repo, int number) throws PublishException {
        ObjectNode payload = mapper.createObjectNode().put("state", "closed").put("state_reason", "completed");
        call("PATCH", repo.apiBase() + "/issues/" + number, payload.toString(), 200);
    }

    private Issue issue(JsonNode item) {
        List<String> labels = new ArrayList<>();
        for (JsonNode l : item.path("labels")) labels.add(l.path("name").asText(""));
        return new Issue(item.path("number").asInt(), item.path("title").asText(""), item.path("body").asText(""),
                item.path("user").path("login").asText(""), item.path("state").asText(""), labels, item.path("html_url").asText(""));
    }

    private JsonNode call(String method, String url, String body, int expected) throws PublishException {
        if (!props.hasGithubToken()) throw new PublishException("未設定 AI_TEAM_GITHUB_TOKEN，無法存取 GitHub issue。");
        try {
            Response resp = transport.send(method, url, body);
            if (resp.status() != expected)
                throw new PublishException("GitHub issue API 失敗（HTTP " + resp.status() + "）：" + redact(abbreviate(resp.body()))
                        + (resp.status() == 403 || resp.status() == 404 ? "（token 需要 Issues: Read and write 權限）" : ""));
            return mapper.readTree(resp.body() == null || resp.body().isBlank() ? "{}" : resp.body());
        } catch (PublishException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishException("呼叫 GitHub issue API 時被中斷", e);
        } catch (IOException e) {
            throw new PublishException("呼叫 GitHub issue API 失敗：" + e.getClass().getSimpleName(), e);
        }
    }
    private String redact(String s) {
        String token = props.githubToken();
        return token.isEmpty() ? s : s.replace(token, "***");
    }
    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() <= 400 ? s : s.substring(0, 400) + "…";
    }
}
