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

    @Test void comparesCommittedObjectsAndRejectsMissingBaselineOrUnsafePaths() throws Exception {
        Path ws = manager.provision(task("1001"));
        String base = manager.headSha("1001");
        assertThat(manager.compareFiles("1001", base, base, List.of("README.md"))).allMatch(WorkspaceManager.FileIdentity::unchanged);
        Files.writeString(ws.resolve("README.md"), "changed\n");
        run(ws, "git", "add", "README.md");
        run(ws, "git", "commit", "--quiet", "-m", "change");
        assertThat(manager.compareFiles("1001", base, manager.headSha("1001"), List.of("README.md"))).noneMatch(WorkspaceManager.FileIdentity::unchanged);
        run(ws, "git", "mv", "README.md", "中文 空白.md");
        run(ws, "git", "commit", "--quiet", "-m", "rename");
        String renamed = manager.headSha("1001");
        assertThat(manager.compareFiles("1001", base, renamed, List.of("README.md")).getFirst().after()).isEmpty();
        assertThat(manager.compareFiles("1001", renamed, renamed, List.of("中文 空白.md"))).allMatch(WorkspaceManager.FileIdentity::unchanged);
        assertThatThrownBy(() -> manager.compareFiles("1001", null, renamed, List.of("README.md"))).hasMessageContaining("實作前");
        assertThatThrownBy(() -> manager.compareFiles("1001", base, renamed, List.of("missing"))).hasMessageContaining("沒有指定檔案");
        for (String path : List.of("../README.md", ":(glob)*", ".git/config", "dir/*", "/tmp/a", "a\nb"))
            assertThatThrownBy(() -> manager.compareFiles("1001", base, renamed, List.of(path))).hasMessageContaining("無效");
    }

    @Test void fileComparisonDetectsModeAndBinaryChangesAndRejectsDirectories() throws Exception {
        Path ws = manager.provision(task("1001"));
        Files.createDirectories(ws.resolve("data"));
        Files.write(ws.resolve("data/file.bin"), new byte[]{0, 1, 2});
        run(ws, "git", "add", "."); run(ws, "git", "commit", "--quiet", "-m", "binary");
        String base = manager.headSha("1001");
        run(ws, "git", "update-index", "--chmod=+x", "README.md");
        run(ws, "git", "commit", "--quiet", "-m", "mode");
        assertThat(manager.compareFiles("1001", base, manager.headSha("1001"), List.of("README.md"))).noneMatch(WorkspaceManager.FileIdentity::unchanged);
        Files.write(ws.resolve("data/file.bin"), new byte[]{0, 3, 2});
        run(ws, "git", "add", "data/file.bin"); run(ws, "git", "commit", "--quiet", "-m", "binary change");
        assertThat(manager.compareFiles("1001", base, manager.headSha("1001"), List.of("data/file.bin"))).noneMatch(WorkspaceManager.FileIdentity::unchanged);
        assertThatThrownBy(() -> manager.compareFiles("1001", base, base, List.of("data"))).hasMessageContaining("一般版控檔案");
    }
    @Test void gitReplacementAndSymlinkCannotHideChangedFiles() throws Exception {
        Path ws = manager.provision(task("1001"));
        String base = manager.headSha("1001");
        Files.writeString(ws.resolve("README.md"), "changed\n");
        run(ws, "git", "add", "."); run(ws, "git", "commit", "--quiet", "-m", "change");
        String head = manager.headSha("1001");
        run(ws, "git", "replace", base, head);
        assertThat(manager.compareFiles("1001", base, head, List.of("README.md"))).noneMatch(WorkspaceManager.FileIdentity::unchanged);
        run(ws, "git", "replace", "-d", base);
        String blob = out(ws, "git", "rev-parse", "HEAD:README.md");
        run(ws, "git", "update-index", "--cacheinfo", "120000," + blob + ",README.md");
        run(ws, "git", "commit", "--quiet", "-m", "symlink mode");
        String linked = manager.headSha("1001");
        assertThat(manager.compareFiles("1001", base, linked, List.of("README.md"))).noneMatch(WorkspaceManager.FileIdentity::unchanged);
        assertThatThrownBy(() -> manager.compareFiles("1001", linked, linked, List.of("README.md"))).hasMessageContaining("一般版控檔案");
    }

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
    void 操作輸出目錄排除後不進稽核與提交_有追蹤檔的目錄不排除() throws Exception {
        Path ws = manager.provision(task("1301"));
        String before = manager.headSha("1301");
        Files.writeString(ws.resolve(".git/info/exclude"), "# 使用者自己的設定\n*.local\n");
        Files.createDirectories(ws.resolve("target/qa-docs/docs-site"));
        Files.writeString(ws.resolve("target/qa-docs/docs-site/package-lock.json"), "{}\n");
        Files.createDirectories(ws.resolve("docs"));
        Files.writeString(ws.resolve("docs/guide.md"), "已追蹤\n");
        run(ws, "git", "add", "docs/guide.md"); run(ws, "git", "commit", "-qm", "追蹤 docs");
        Files.writeString(ws.resolve("docs/new.md"), "新增的文件\n");

        assertThat(manager.excludeOutputDirs("1301", List.of("target/qa-docs", "docs", "../x", ".git", "missing")))
                .containsExactly("missing", "target/qa-docs");
        // 重複呼叫只保留一個管理區塊，使用者原有內容不動。
        manager.excludeOutputDirs("1301", List.of("target/qa-docs"));
        String exclude = Files.readString(ws.resolve(".git/info/exclude"));
        assertThat(exclude).contains("*.local").contains("/target/qa-docs/").doesNotContain("/missing/");
        assertThat(exclude.split(WorkspaceManager.EXCLUDE_BEGIN, -1)).hasSize(2);

        assertThat(manager.auditPaths("1301", before)).contains("docs/new.md")
                .noneMatch(p -> p.startsWith("target/"));
        assertThat(manager.uncommittedPaths("1301")).containsExactly("docs/new.md");

        manager.excludeOutputDirs("1301", List.of());
        assertThat(Files.readString(ws.resolve(".git/info/exclude"))).contains("*.local").doesNotContain("ai-team");
        assertThat(manager.uncommittedPaths("1301")).contains("target/qa-docs/docs-site/package-lock.json");
    }

    /** 在主 repo 提交並推到 origin，模擬 GitHub 上的基準分支前進（例如另一個 PR 被合併）。 */
    private String advanceBase(String file, String content) throws Exception {
        run(repo, "git", "config", "user.email", "base@test");
        run(repo, "git", "config", "user.name", "base");
        Files.writeString(repo.resolve(file), content, StandardCharsets.UTF_8);
        run(repo, "git", "add", file);
        run(repo, "git", "commit", "--quiet", "-m", "基準前進 " + file);
        run(repo, "git", "push", "--quiet", "origin", "master");
        return out(repo, "git", "rev-parse", "HEAD");
    }

    private String prCommit(Path ws) throws Exception {
        Files.writeString(ws.resolve("README.md"), "PR 的修改\n", StandardCharsets.UTF_8);
        Files.writeString(ws.resolve("pr.txt"), "pr\n", StandardCharsets.UTF_8);
        run(ws, "git", "add", ".");
        run(ws, "git", "commit", "--quiet", "-m", "PR 修改");
        return out(ws, "git", "rev-parse", "HEAD");
    }

    @Test
    void 已開PR同步_合併最新基準後只列真衝突_解完才通過核對() throws Exception {
        Path ws = manager.provision(task("1401"));
        String parent = prCommit(ws);
        advanceBase("README.md", "基準的修改\n");
        String baseSha = advanceBase("base.txt", "base\n");

        var merge = manager.mergeBase("1401", repo, "master", parent);

        assertThat(merge.upToDate()).isFalse();
        assertThat(merge.baseSha()).isEqualTo(baseSha);
        assertThat(merge.conflicts()).containsExactly("README.md");
        // 基準帶進來的檔案不算角色的修改，否則基準改過受保護路徑就會被誤判越權。
        assertThat(manager.pathsChangedFromAutoMerge("1401", parent, baseSha)).doesNotContain("base.txt", "pr.txt");
        assertThat(manager.mergeProblem("1401", parent, baseSha, merge.conflicts())).contains("MERGE_HEAD");

        Files.writeString(ws.resolve("README.md"), "PR 的修改\n基準的修改\n", StandardCharsets.UTF_8);
        run(ws, "git", "add", "README.md");
        run(ws, "git", "commit", "--quiet", "--no-edit");

        assertThat(manager.pathsChangedFromAutoMerge("1401", parent, baseSha)).containsExactly("README.md");
        assertThat(manager.mergeProblem("1401", parent, baseSha, merge.conflicts())).isNull();
        // 之後的驗收範圍是 PR 對新基準的實際差異。
        assertThat(manager.changedPathsSinceBase("1401")).containsExactlyInAnyOrder("README.md", "pr.txt");
    }

    @Test
    void 已開PR同步_殘留衝突標記或改了衝突檔以外的檔案都不通過() throws Exception {
        Path ws = manager.provision(task("1402"));
        String parent = prCommit(ws);
        String baseSha = advanceBase("README.md", "基準的修改\n");
        var merge = manager.mergeBase("1402", repo, "master", parent);
        run(ws, "git", "add", "README.md");
        run(ws, "git", "commit", "--quiet", "--no-edit");
        assertThat(manager.mergeProblem("1402", parent, baseSha, merge.conflicts())).contains("衝突標記").contains("README.md");

        Files.writeString(ws.resolve("README.md"), "解好了\n", StandardCharsets.UTF_8);
        Files.writeString(ws.resolve("pr.txt"), "順手改了別的\n", StandardCharsets.UTF_8);
        run(ws, "git", "add", ".");
        run(ws, "git", "commit", "--quiet", "--amend", "--no-edit");
        assertThat(manager.mergeProblem("1402", parent, baseSha, merge.conflicts())).contains("衝突檔以外").contains("pr.txt");

        // 合併 commit 之後又多一個 commit：HEAD 不再是兩個指定上層的合併 commit。
        Files.writeString(ws.resolve("extra.txt"), "x\n", StandardCharsets.UTF_8);
        run(ws, "git", "add", "extra.txt");
        run(ws, "git", "commit", "--quiet", "-m", "多一個 commit");
        assertThat(manager.mergeProblem("1402", parent, baseSha, merge.conflicts())).contains("合併 commit");
    }

    @Test
    void 已開PR同步_沒有衝突時由系統直接完成合併_工作區不是已發布commit時拒絕() throws Exception {
        Path ws = manager.provision(task("1403"));
        String parent = prCommit(ws);
        assertThat(manager.mergeBase("1403", repo, "master", parent).upToDate()).isTrue();

        String baseSha = advanceBase("base.txt", "base\n");
        assertThatThrownBy(() -> manager.mergeBase("1403", repo, "master", "0".repeat(40))).hasMessageContaining("已發布");

        var merge = manager.mergeBase("1403", repo, "master", parent);
        assertThat(merge.conflicts()).isEmpty();
        assertThat(manager.mergeProblem("1403", parent, baseSha, List.of())).isNull();
        assertThat(ws.resolve("base.txt")).exists();
    }

    @Test
    void 動手前同步_解完衝突後可接著做本輪工作_系統代為提交_放棄合併不通過() throws Exception {
        Path ws = manager.provision(task("1404"));
        run(ws, "git", "config", "core.autocrlf", "false");   // Windows 的換行警告會混進 git 輸出
        String parent = prCommit(ws);
        String baseSha = advanceBase("README.md", "基準的修改\n");
        var merge = manager.mergeBase("1404", repo, "master", parent);
        assertThat(manager.baseSha("1404")).isEqualTo(baseSha);

        // 還有衝突標記時不代為提交。
        assertThat(manager.finishMerge("1404", merge.conflicts(), "合併")).contains("衝突標記");
        // 角色改好衝突檔、又做了本輪工作，但沙箱不能寫 .git：系統代為提交合併 commit。
        Files.writeString(ws.resolve("README.md"), "PR 的修改\n基準的修改\n", StandardCharsets.UTF_8);
        Files.writeString(ws.resolve("feature.txt"), "本輪工作\n", StandardCharsets.UTF_8);
        assertThat(manager.finishMerge("1404", merge.conflicts(), "合併並實作")).isNull();
        assertThat(manager.clean("1404")).isTrue();
        assertThat(manager.syncProblem("1404", parent, baseSha, merge.conflicts())).isNull();
        // 合併 commit 之後的本輪 commit 不影響核對（與已開 PR 同步不同）。
        Files.writeString(ws.resolve("more.txt"), "x\n", StandardCharsets.UTF_8);
        run(ws, "git", "add", "more.txt");
        run(ws, "git", "commit", "--quiet", "-m", "本輪另一個 commit");
        assertThat(manager.syncProblem("1404", parent, baseSha, merge.conflicts())).isNull();
        assertThat(manager.changedPathsSinceBase("1404")).containsExactlyInAnyOrder("README.md", "pr.txt", "feature.txt", "more.txt");

        // 放棄合併、回到原 commit：基準不在歷史裡。
        run(ws, "git", "reset", "--quiet", "--hard", parent);
        assertThat(manager.syncProblem("1404", parent, baseSha, merge.conflicts())).contains(baseSha);
        assertThat(manager.finishMerge("1404", merge.conflicts(), "沒有合併中")).isNull();
    }

    @Test
    void 主repo遠端記錄_60秒內不重複fetch_失敗時回報過時() throws Exception {
        var first = manager.refreshOrigin(repo);
        assertThat(first.ok()).isTrue();
        Path other = tmp.resolve("other");
        run(tmp, "git", "clone", "--quiet", origin.toString(), other.toString());
        run(other, "git", "config", "user.email", "o@test"); run(other, "git", "config", "user.name", "o");
        Files.writeString(other.resolve("new.txt"), "n\n");
        run(other, "git", "add", "."); run(other, "git", "commit", "--quiet", "-m", "遠端前進");
        run(other, "git", "push", "--quiet", "origin", "master");
        String before = out(repo, "git", "rev-parse", "refs/remotes/origin/master");

        assertThat(manager.refreshOrigin(repo).at()).isEqualTo(first.at());
        assertThat(out(repo, "git", "rev-parse", "refs/remotes/origin/master")).isEqualTo(before);

        Path broken = tmp.resolve("broken");
        run(tmp, "git", "clone", "--quiet", origin.toString(), broken.toString());
        run(broken, "git", "remote", "set-url", "origin", tmp.resolve("missing.git").toString());
        var failed = manager.refreshOrigin(broken);
        assertThat(failed.ok()).isFalse();
        assertThat(failed.at()).isNull();
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
    void 承接任務建立後併入最新基準_沒有前進時不做事_前進時與原任務成果一起合併() throws Exception {
        Path parent = manager.provision(task("1111"));
        Files.writeString(parent.resolve("Mail.java"), "原任務的修正\n");
        run(parent, "git", "add", "Mail.java"); run(parent, "git", "commit", "-qm", "原任務修正");
        String parentHead = manager.headSha("1111");
        manager.provisionFrom(task("1112"), repo, null, "1111", "feature/ai-team-task-1111", parentHead);

        assertThat(manager.syncSuccessorBase("1112", repo, parentHead).upToDate()).isTrue();
        assertThat(manager.headSha("1112")).isEqualTo(parentHead);

        String baseSha = advanceBase("skill.md", "新 skill\n");
        var merge = manager.syncSuccessorBase("1112", repo, parentHead);

        assertThat(merge.conflicts()).isEmpty();
        Path ws = workspaces.resolve("task-1112");
        assertThat(ws.resolve("skill.md")).exists();
        assertThat(ws.resolve("Mail.java")).hasContent("原任務的修正\n");
        assertThat(out(ws, "git", "rev-parse", "HEAD^1")).isEqualTo(parentHead);
        assertThat(out(ws, "git", "rev-parse", "HEAD^2")).isEqualTo(baseSha);
        assertThat(manager.baseSha("1112")).isEqualTo(baseSha);
        assertThat(manager.clean("1112")).isTrue();
    }

    @Test
    void 承接任務建立後併入最新基準_有衝突時維持原任務commit留給實作節點() throws Exception {
        Path parent = manager.provision(task("1113"));
        Files.writeString(parent.resolve("README.md"), "原任務的修改\n");
        run(parent, "git", "add", "README.md"); run(parent, "git", "commit", "-qm", "原任務修正");
        String parentHead = manager.headSha("1113");
        manager.provisionFrom(task("1114"), repo, null, "1113", "feature/ai-team-task-1113", parentHead);
        String forkPoint = manager.baseSha("1114");
        advanceBase("README.md", "基準的修改\n");

        var merge = manager.syncSuccessorBase("1114", repo, parentHead);

        assertThat(merge.conflicts()).containsExactly("README.md");
        assertThat(manager.headSha("1114")).isEqualTo(parentHead);
        assertThat(manager.baseSha("1114")).isEqualTo(forkPoint);
        assertThat(manager.clean("1114")).isTrue();
        assertThat(workspaces.resolve("task-1114/.git/MERGE_HEAD")).doesNotExist();
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

    @Test
    void 未提交檔案的內容狀態_內容沒變就相同_改過或刪除就不同() throws Exception {
        Path ws = manager.provision(task("5002"));
        Files.writeString(ws.resolve("README.md"), "改過的內容\n", StandardCharsets.UTF_8);
        Files.writeString(ws.resolve("new.txt"), "新檔\n", StandardCharsets.UTF_8);

        var before = manager.uncommittedStates("5002");
        assertThat(before).containsOnlyKeys("README.md", "new.txt");
        assertThat(manager.contentStates("5002", before.keySet())).isEqualTo(before);

        Files.writeString(ws.resolve("README.md"), "再改一次\n", StandardCharsets.UTF_8);
        Files.delete(ws.resolve("new.txt"));
        var after = manager.contentStates("5002", before.keySet());
        assertThat(after.get("README.md")).isNotEqualTo(before.get("README.md"));
        assertThat(after.get("new.txt")).isEqualTo("-");
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
