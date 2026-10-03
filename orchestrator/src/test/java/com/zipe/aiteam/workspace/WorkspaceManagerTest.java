package com.zipe.aiteam.workspace;

import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.task.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WorkspaceManager 的行為驗證。
 *
 * 用真的 git repository，不用 mock —— 這個類別的價值全在於它跟 git 的互動是否正確，
 * 把 git 換成 mock 等於把要驗證的東西換掉。
 */
class WorkspaceManagerTest {

    @TempDir Path tmp;

    private Path origin;        // 模擬 GitHub 的 bare repo
    private Path repo;          // 主 repo（AI_TEAM_REPO）
    private Path workspaces;    // AI_TEAM_WORKSPACES
    private WorkspaceManager manager;
    private AiTeamProperties props;

    @BeforeEach
    void setUp() throws Exception {
        origin = tmp.resolve("origin.git");
        repo = tmp.resolve("repo");
        workspaces = tmp.resolve("workspaces");
        Files.createDirectories(workspaces);

        // 建一個有內容的 bare origin，讓 clone 之後真的會有 origin/HEAD
        Path seed = tmp.resolve("seed");
        Files.createDirectories(seed);
        run(seed, "git", "init", "--quiet", "--initial-branch=master");
        run(seed, "git", "config", "user.email", "seed@test");
        run(seed, "git", "config", "user.name", "seed");
        Files.writeString(seed.resolve("README.md"), "初始內容\n", StandardCharsets.UTF_8);
        run(seed, "git", "add", ".");
        run(seed, "git", "commit", "--quiet", "-m", "初始 commit");
        run(tmp, "git", "clone", "--quiet", "--bare", seed.toString(), origin.toString());
        run(tmp, "git", "clone", "--quiet", origin.toString(), repo.toString());

        props = new AiTeamProperties();
        ReflectionTestUtils.setField(props, "repoPath", repo.toString());
        ReflectionTestUtils.setField(props, "workspacesPath", workspaces.toString());
        ReflectionTestUtils.setField(props, "gitTimeoutSeconds", 120L);
        ReflectionTestUtils.setField(props, "gitUserName", "AI Team");
        ReflectionTestUtils.setField(props, "gitUserEmail", "ai-team@localhost");
        ReflectionTestUtils.setField(props, "baseBranch", "");
        ReflectionTestUtils.setField(props, "branchPrefix", "feature/ai-team-task-");
        manager = new WorkspaceManager(props);
    }

    @Test
    void provision_應建立獨立的_clone_並切到任務分支() throws Exception {
        Path ws = manager.provision(task("1001"));

        assertThat(ws).isEqualTo(workspaces.resolve("task-1001"));
        assertThat(ws.resolve("README.md")).exists();

        // .git 必須是目錄而不是 worktree 那種指標檔 —— 這正是改用 clone 的原因
        assertThat(ws.resolve(".git")).isDirectory();

        assertThat(out(ws, "git", "branch", "--show-current")).isEqualTo("feature/ai-team-task-1001");
    }

    @Test
    void 承接任務的工作區從原任務未推送的commit接著做而且基準包含原任務成果() throws Exception {
        Path parent = manager.provision(task("1101"));
        Files.writeString(parent.resolve("Mail.java"), "原任務的修正\n");
        run(parent, "git", "add", "Mail.java"); run(parent, "git", "commit", "-qm", "原任務修正");
        String parentHead = manager.headSha("1101");

        Path ws = manager.provisionFrom(task("1102"), repo, null, "1101", "feature/ai-team-task-1101", parentHead);

        assertThat(manager.headSha("1102")).isEqualTo(parentHead);
        assertThat(ws.resolve("Mail.java")).hasContent("原任務的修正\n");
        assertThat(out(ws, "git", "branch", "--show-current")).isEqualTo("feature/ai-team-task-1102");
        assertThat(manager.clean("1102")).isTrue();
        assertThat(manager.origin("1102")).isEqualTo(origin.toString());
        // 原任務的提交要算在「自基準以來」裡，PR 才會包含它們。
        assertThat(manager.commitsSinceBase("1102")).anyMatch(c -> c.contains("原任務修正"));
        assertThat(out(ws, "git", "config", "--get", "ai-team.continuation-of")).isEqualTo("1101");
    }

    @Test
    void 變更清單把改名的兩端都列出來且中文檔名照原字() throws Exception {
        // 純文件判定靠這份清單：程式檔改名搬進 docs/ 若只列新路徑，就會被誤判成純文件而略過建置測試。
        Path ws = manager.provision(task("1201"));
        Files.createDirectories(ws.resolve("src"));
        Files.writeString(ws.resolve("src/App.java"), "class App {}\n");
        run(ws, "git", "add", "."); run(ws, "git", "commit", "-qm", "加程式");
        String base = manager.headSha("1201");
        run(ws, "git", "config", "--local", "ai-team.base-sha", base);
        Files.createDirectories(ws.resolve("docs"));
        run(ws, "git", "mv", "src/App.java", "docs/App.java");
        Files.writeString(ws.resolve("docs/操作手冊.md"), "內容\n");
        run(ws, "git", "add", "."); run(ws, "git", "commit", "-qm", "搬家");

        assertThat(manager.changedPathsSinceBase("1201"))
                .contains("src/App.java", "docs/App.java", "docs/操作手冊.md");
    }

    @Test
    void 原任務HEAD已變動時拒絕承接() throws Exception {
        manager.provision(task("1103"));
        String stale = manager.headSha("1103");
        Path parent = workspaces.resolve("task-1103");
        Files.writeString(parent.resolve("x.txt"), "後來又改了\n");
        run(parent, "git", "add", "x.txt"); run(parent, "git", "commit", "-qm", "後續提交");

        assertThatThrownBy(() -> manager.provisionFrom(task("1104"), repo, null, "1103", "feature/ai-team-task-1103", stale))
                .hasMessageContaining("已變動");
    }

    @Test
    void 原任務工作區不存在時拒絕承接() {
        assertThatThrownBy(() -> manager.provisionFrom(task("1106"), repo, null, "1105", "feature/ai-team-task-1105", "abc"))
                .hasMessageContaining("不存在");
    }

    @Test
    void 限寫稽核不能只看最後diff而漏掉中途修改後還原的commit() throws Exception {
        Path ws = manager.provision(task("1042"));
        String before = manager.headSha("1042");
        String original = Files.readString(ws.resolve("README.md"));
        Files.writeString(ws.resolve("README.md"), "不允許的中途修改\n");
        run(ws, "git", "add", "README.md"); run(ws, "git", "commit", "-qm", "中途修改");
        Files.writeString(ws.resolve("README.md"), original);
        run(ws, "git", "add", "README.md"); run(ws, "git", "commit", "-qm", "還原");
        assertThat(manager.changedPathsBetween("1042", before, manager.headSha("1042"))).isEmpty();
        assertThat(manager.auditPaths("1042", before)).contains("README.md");
        Files.createDirectories(ws.resolve(" tests"));
        Files.writeString(ws.resolve(" tests/file.txt"), "leading-space");
        assertThat(manager.auditPaths("1042", before)).contains(" tests/file.txt");
    }

    @Test
    void provision_的_origin_應指向真正的遠端而非本機主_repo() throws Exception {
        Path ws = manager.provision(task("1002"));

        // clone 出來預設會指向本機路徑，沒改掉的話之後推分支會推到主 repo
        assertThat(out(ws, "git", "remote", "get-url", "origin"))
                .isEqualTo(origin.toString())
                .isNotEqualTo(repo.toString());
    }

    @Test
    void provision_應以遠端最新的預設分支為基準而非落後的本機分支() throws Exception {
        // 在 origin 推進一個 commit，但不更新主 repo 的本機 master
        Path other = tmp.resolve("other");
        run(tmp, "git", "clone", "--quiet", origin.toString(), other.toString());
        run(other, "git", "config", "user.email", "other@test");
        run(other, "git", "config", "user.name", "other");
        Files.writeString(other.resolve("NEW.md"), "後來才加的\n", StandardCharsets.UTF_8);
        run(other, "git", "add", ".");
        run(other, "git", "commit", "--quiet", "-m", "後續 commit");
        run(other, "git", "push", "--quiet", "origin", "master");

        String localMaster = out(repo, "git", "rev-parse", "master");
        Path ws = manager.provision(task("1003"));
        String wsHead = out(ws, "git", "rev-parse", "HEAD");

        assertThat(ws.resolve("NEW.md")).as("應該包含 origin 上較新的 commit").exists();
        assertThat(wsHead).as("不該停留在落後的本機 master").isNotEqualTo(localMaster);
    }

    @Test
    void provision_應設定工作區專屬的_commit_署名() throws Exception {
        Path ws = manager.provision(task("1004"));

        assertThat(out(ws, "git", "config", "--local", "user.name")).isEqualTo("AI Team");
        assertThat(out(ws, "git", "config", "--local", "user.email")).isEqualTo("ai-team@localhost");
    }

    @Test
    void provision_重新開始先封存未提交修改() throws Exception {
        Path ws = manager.provision(task("1005"));
        Files.writeString(ws.resolve("殘骸.txt"), "上一次失敗留下的\n", StandardCharsets.UTF_8);

        manager.provision(task("1005"));

        assertThat(ws.resolve("殘骸.txt")).as("重做時不該看到上一次的殘留檔案").doesNotExist();
        try (var entries = Files.list(workspaces.resolveSibling("workspace-archives"))) {
            Path archived = entries.findFirst().orElseThrow();
            assertThat(Files.readString(archived.resolve("殘骸.txt"))).contains("上一次失敗留下的");
            assertThat(archived.resolve(".git")).isDirectory();
        }
    }

    @Test
    void discard_應刪除工作區包含唯讀的_git_物件檔() throws Exception {
        Path ws = manager.provision(task("1006"));
        assertThat(ws).exists();

        manager.discard("1006");

        assertThat(ws).as("git 物件檔是唯讀的，刪不乾淨的話會留下殘骸").doesNotExist();
    }

    @Test
    void discard_對不存在的工作區應安靜返回() {
        assertThat(workspaces.resolve("task-9999")).doesNotExist();
        assertThatCode(() -> manager.discard("9999")).doesNotThrowAnyException();
    }

    @Test
    void discard_應拒絕逃逸出工作區根目錄的路徑() {
        // task-../../etc 正規化後會跑到 workspaces 之外，或是名稱不再以 task- 開頭
        assertThatThrownBy(() -> manager.discard("../../etc"))
                .isInstanceOf(WorkspaceManager.WorkspaceException.class)
                .hasMessageContaining("拒絕刪除");
    }

    @Test
    void listTaskIds_只認得工作區目錄() throws Exception {
        manager.provision(task("2001"));
        manager.provision(task("2002"));
        Files.createDirectories(workspaces.resolve("不是工作區"));
        Files.writeString(workspaces.resolve("task-檔案不是目錄"), "x", StandardCharsets.UTF_8);

        assertThat(manager.listTaskIds()).containsExactly("2001", "2002");
    }

    @Test
    void branchFor_與_pathFor_應與實際建立出來的一致() throws Exception {
        Path ws = manager.provision(task("3001"));

        assertThat(manager.pathFor("3001")).isEqualTo(ws);
        assertThat(manager.branchFor("3001"))
                .isEqualTo(out(ws, "git", "branch", "--show-current"));
    }

    @Test
    void provision_遠端有_develop_時應以_develop_為基準而非_master() throws Exception {
        // gitflow 專案從 master 開分支，會做出一個永遠合不進 develop 的 PR
        Path other = tmp.resolve("dev-maker");
        run(tmp, "git", "clone", "--quiet", origin.toString(), other.toString());
        run(other, "git", "config", "user.email", "dev@test");
        run(other, "git", "config", "user.name", "dev");
        run(other, "git", "checkout", "--quiet", "-b", "develop");
        Files.writeString(other.resolve("只在 develop.md"), "x", StandardCharsets.UTF_8);
        run(other, "git", "add", ".");
        run(other, "git", "commit", "--quiet", "-m", "develop 專有 commit");
        run(other, "git", "push", "--quiet", "origin", "develop");

        Path ws = manager.provision(task("4001"));

        assertThat(ws.resolve("只在 develop.md"))
                .as("應以 develop 為基準").exists();
    }

    @Test
    void provision_明確設定的基準分支應優先於自動判斷() throws Exception {
        Path other = tmp.resolve("dev-maker2");
        run(tmp, "git", "clone", "--quiet", origin.toString(), other.toString());
        run(other, "git", "config", "user.email", "dev@test");
        run(other, "git", "config", "user.name", "dev");
        run(other, "git", "checkout", "--quiet", "-b", "develop");
        Files.writeString(other.resolve("只在 develop.md"), "x", StandardCharsets.UTF_8);
        run(other, "git", "add", ".");
        run(other, "git", "commit", "--quiet", "-m", "develop 專有 commit");
        run(other, "git", "push", "--quiet", "origin", "develop");

        ReflectionTestUtils.setField(props, "baseBranch", "master");
        Path ws = manager.provision(task("4002"));

        assertThat(ws.resolve("只在 develop.md"))
                .as("設定為 master 時不該拿到 develop 的內容").doesNotExist();
    }

    @Test
    void 未提交修改_列出修改刪除與新增_指紋隨內容改變_保存後工作區乾淨() throws Exception {
        Path ws = manager.provision(task("5001"));
        Files.writeString(ws.resolve("old.txt"), "會被刪掉\n", StandardCharsets.UTF_8);
        run(ws, "git", "add", "old.txt");
        run(ws, "git", "commit", "--quiet", "-m", "加入 old.txt");
        String before = manager.headSha("5001");

        Files.writeString(ws.resolve("README.md"), "改過的內容\n", StandardCharsets.UTF_8);
        Files.delete(ws.resolve("old.txt"));
        Files.createDirectories(ws.resolve("src/test"));
        Files.writeString(ws.resolve("src/test/NewTest.java"), "class NewTest {}\n", StandardCharsets.UTF_8);

        assertThat(manager.uncommittedPaths("5001")).containsExactly("README.md", "old.txt", "src/test/NewTest.java");
        String fingerprint = manager.uncommittedFingerprint("5001");
        assertThat(manager.uncommittedFingerprint("5001")).as("內容沒變，指紋要穩定").isEqualTo(fingerprint);

        // 只改未追蹤檔的內容，指紋也必須改變：按鈕綁定的是使用者看到的那一版。
        Files.writeString(ws.resolve("src/test/NewTest.java"), "class NewTest { int changed; }\n", StandardCharsets.UTF_8);
        assertThat(manager.uncommittedFingerprint("5001")).isNotEqualTo(fingerprint);

        String head = manager.commitPaths("5001", manager.uncommittedPaths("5001"), "保存未提交修改");

        assertThat(head).isNotEqualTo(before);
        assertThat(manager.clean("5001")).isTrue();
        assertThat(manager.uncommittedPaths("5001")).isEmpty();
        assertThat(out(ws, "git", "log", "-1", "--format=%an|%s")).isEqualTo("AI Team|保存未提交修改");
        assertThat(out(ws, "git", "show", "--name-status", "--format=", "HEAD"))
                .contains("M\tREADME.md").contains("D\told.txt").contains("A\tsrc/test/NewTest.java");
    }

    // ------------------------------------------------------------------ 輔助

    private static Task task(String id) {
        return new Task(id, "C123", "1.0", "U123", "測試需求");
    }

    private static void run(Path cwd, String... cmd) throws Exception {
        assertThat(exec(cwd, cmd).exit()).as("指令失敗：%s", String.join(" ", cmd)).isZero();
    }

    private static String out(Path cwd, String... cmd) throws Exception {
        Result r = exec(cwd, cmd);
        assertThat(r.exit()).as("指令失敗：%s%n%s", String.join(" ", cmd), r.output()).isZero();
        return r.output();
    }

    private record Result(int exit, String output) { }

    private static Result exec(Path cwd, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(List.of(cmd));
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output;
        try (var in = p.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("指令逾時：" + String.join(" ", cmd));
        }
        return new Result(p.exitValue(), output);
    }
}
