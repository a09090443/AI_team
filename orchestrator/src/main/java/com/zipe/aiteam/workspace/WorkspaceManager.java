package com.zipe.aiteam.workspace;

import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 每個任務的隔離工作區。
 *
 * <h2>為什麼是獨立 clone 而不是 git worktree</h2>
 *
 * 原設計寫的是 worktree，實測後改掉。
 * worktree 的 .git 只是一個指向主 repo .git/worktrees 的指標檔，
 * index 與新物件都寫在主 repo 底下。沙箱只開放工作區可寫時，
 * git add 會直接失敗：index.lock: Read-only file system。
 *
 * 要讓 worktree 能動，就得把主 repo 的 .git 也開放可寫 ——
 * 那等於讓 agent 有能力改寫 master 的 ref、刪分支、弄壞整個 repo。
 * 換成獨立 clone 後，工作區完全自給自足，主 repo 連 .git 都是唯讀的。
 *
 * 代價是每個任務約 9 MB、0.3 秒，對 7 MB 的 repo 完全可以接受。
 *
 * <h2>這個類別不在沙箱裡執行</h2>
 *
 * 它是 orchestrator 自己的準備工作，需要主 repo 之外的寫入能力（clone、fetch）。
 * agent 拿到的是準備好的結果，不是準備的過程。
 */
@Component
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    /** 目錄前綴。listTaskIds() 靠它辨認工作區，也是刪除防護的一部分。 */
    private static final String DIR_PREFIX = "task-";

    /**
     * 基準分支記在工作區的 local git config，供之後開 PR 時決定 merge 目標。
     * 存在工作區而不是記憶體，是為了讓服務重啟後仍然知道 PR 要合併到哪。
     */
    public static final String BASE_BRANCH_CONFIG_KEY = "ai-team.base-branch";

    /**
     * 基準的 commit SHA。
     *
     * 分支名稱不夠用：工作區是從主 repo clone 出來的，只會有 master 與
     * origin/master，**沒有 develop 這個 ref** —— `git log develop..HEAD` 會直接失敗。
     * 開 PR 需要分支名稱（GitHub 要），算「多了哪些 commit」需要 SHA，兩個都要存。
     */
    public static final String BASE_SHA_CONFIG_KEY = "ai-team.base-sha";

    private final AiTeamProperties props;

    public WorkspaceManager(AiTeamProperties props) {
        this.props = props;
    }

    // ------------------------------------------------------------------ 位置

    public Path pathFor(String taskId) {
        return props.workspacesPath().resolve(DIR_PREFIX + taskId);
    }

    public String branchFor(String taskId) {
        return props.branchPrefix() + taskId;
    }

    // ------------------------------------------------------------------ 建立

    /**
     * 為任務準備一個乾淨的工作區，回傳其路徑。
     *
     * 重新開始時先封存原工作區（含未提交修改）再 clone。
     * 額度續跑不呼叫 provision，必須沿用原現場。
     *
     * 分支基準取自主 repo 的 refs/remotes/origin 之下的預設分支，
     * 不是本機分支 —— 本機分支可能落後。取基準前會先 fetch，
     * fetch 失敗只警告不中斷（可能只是暫時離線），此時以稍舊的基準繼續。
     */
    public Path provision(Task task) throws WorkspaceException {
        return provision(task, props.repoPath(), null);
    }

    public Path provision(Task task, Path repository, String configuredBase) throws WorkspaceException {
        if (!task.id().matches("[0-9]+")) throw new WorkspaceException("無效的任務 ID，拒絕移動工作區");
        Path repo = repository;
        Path ws = pathFor(task.id());

        if (Files.exists(ws)) {
            try {
                Path archive = props.workspacesPath().toAbsolutePath().resolveSibling("workspace-archives");
                Files.createDirectories(archive);
                Files.move(ws, archive.resolve("task-" + task.id() + "-" + java.util.UUID.randomUUID()));
            } catch (java.io.IOException e) {
                throw new WorkspaceException("無法封存原工作區，拒絕重新開始", e);
            }
        }

        try {
            Files.createDirectories(props.workspacesPath());
        } catch (IOException e) {
            throw new WorkspaceException("無法建立工作區根目錄：" + props.workspacesPath(), e);
        }

        // 只 fetch，不碰主 repo 的本機分支與工作樹 —— 主 repo 對這個系統而言是唯讀的基準。
        try {
            git(repo, "fetch", "--quiet", "origin");
        } catch (WorkspaceException e) {
            log.warn("fetch 主 repo 失敗，將以本機既有的 origin 基準建立工作區：{}", e.getMessage());
        }

        String base = configuredBase == null ? detectBaseBranch(repo) : configuredBase;

        git(props.workspacesPath(), "clone", "--no-hardlinks", "--quiet",
                repo.toString(), ws.toString());

        // 從主 repo 取最新的 origin 基準分支，確保起點不是 clone 當下的本機分支。
        git(ws, "fetch", "--quiet", repo.toString(), "refs/remotes/origin/" + base);
        git(ws, "checkout", "--quiet", "-b", branchFor(task.id()), "FETCH_HEAD");

        // clone 出來的 origin 指向本機路徑，改回真正的遠端，之後推分支才有意義。
        String originUrl = gitOutput(repo, "remote", "get-url", "origin");
        git(ws, "remote", "set-url", "origin", originUrl);

        // 寫在工作區的 local config：不需要也不應該改動使用者的 ~/.gitconfig。
        git(ws, "config", "user.name", props.gitUserName());
        git(ws, "config", "user.email", props.gitUserEmail());
        git(ws, "config", BASE_BRANCH_CONFIG_KEY, base);
        git(ws, "config", BASE_SHA_CONFIG_KEY, gitOutput(ws, "rev-parse", "HEAD"));

        String head = gitOutput(ws, "rev-parse", "--short", "HEAD");
        log.info("任務 {} 的工作區已就緒：{}（分支 {}，基準 {} @ {}）",
                task.id(), ws, branchFor(task.id()), base, head);
        return ws;
    }

    // ------------------------------------------------------------------ 清除

    /**
     * 刪除某任務的工作區。不存在時安靜返回。
     *
     * 這是這個類別唯一的破壞性操作，刪除前要通過兩道防護：
     * 路徑必須真的位於 workspaces 之下，且目錄名必須是 task- 開頭。
     * 少了它們，一個設錯的 AI_TEAM_WORKSPACES 就足以刪掉不該刪的東西。
     */
    /**
     * 承接任務的工作區：照一般流程從基準分支建立，再把原任務分支的 HEAD 帶進來接著做。
     *
     * 原任務的提交通常只存在它的本機工作區（還沒推送），所以不能只靠主 repo；
     * 若只用 provision，新任務會從基準分支重新開始，完全看不到原任務的成果。
     * 基準 commit 改為原任務分支與基準分支的分岔點，之後 PR 與「自基準以來的提交」才會包含原任務的成果。
     */
    public Path provisionFrom(Task task, Path repository, String configuredBase,
                              String parentId, String parentBranch, String parentHead) throws WorkspaceException {
        if (parentId == null || !parentId.matches("[0-9]+")) throw new WorkspaceException("無效的原任務 ID，拒絕承接");
        if (parentBranch == null || parentBranch.isBlank() || parentHead == null || parentHead.isBlank())
            throw new WorkspaceException("缺少原任務的分支或 commit，拒絕承接");
        Path parent = pathFor(parentId);
        if (!Files.isDirectory(parent.resolve(".git"))) throw new WorkspaceException("原任務工作區不存在，無法承接：" + parent);

        Path ws = provision(task, repository, configuredBase);
        git(ws, "fetch", "--quiet", parent.toString(), "refs/heads/" + parentBranch);
        String fetched = gitOutput(ws, "rev-parse", "FETCH_HEAD");
        if (!fetched.equals(parentHead))
            throw new WorkspaceException("原任務分支的最新 commit 已變動，拒絕承接：預期 " + parentHead + "，實際 " + fetched);
        git(ws, "reset", "--hard", "--quiet", parentHead);

        String base = gitOutput(ws, "config", "--get", BASE_BRANCH_CONFIG_KEY);
        git(ws, "fetch", "--quiet", repository.toString(), "refs/remotes/origin/" + base);
        git(ws, "config", BASE_SHA_CONFIG_KEY, gitOutput(ws, "merge-base", parentHead, "FETCH_HEAD"));
        git(ws, "config", "ai-team.continuation-of", parentId);
        log.info("任務 {} 承接任務 {}：工作區從 {} 的 {} 開始", task.id(), parentId, parentBranch, parentHead);
        return ws;
    }

    public void discard(String taskId) throws WorkspaceException {
        Path ws = pathFor(taskId).toAbsolutePath().normalize();
        Path root = props.workspacesPath().toAbsolutePath().normalize();

        if (!ws.startsWith(root) || ws.equals(root)) {
            throw new WorkspaceException("拒絕刪除：" + ws + " 不在工作區根目錄 " + root + " 之下");
        }
        if (!ws.getFileName().toString().startsWith(DIR_PREFIX)) {
            throw new WorkspaceException("拒絕刪除：" + ws + " 不像工作區目錄（應為 "
                    + DIR_PREFIX + " 開頭）");
        }
        if (!Files.exists(ws)) return;

        try {
            deleteRecursively(ws);
            log.info("已刪除工作區 {}", ws);
        } catch (IOException e) {
            throw new WorkspaceException("刪除工作區失敗：" + ws, e);
        }
    }

    /** 目前磁碟上存在的工作區對應的任務編號。啟動清理用。 */
    public List<String> listTaskIds() {
        Path root = props.workspacesPath();
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(DIR_PREFIX))
                    .map(n -> n.substring(DIR_PREFIX.length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("列出工作區失敗：{}", e.toString());
            return List.of();
        }
    }

    // ------------------------------------------------------------------ git

    /**
     * 決定開分支的基準。
     *
     * 順序：明確設定 > develop > origin 的預設分支 > 主 repo 目前的分支。
     *
     * develop 優先的理由：走 gitflow 的專案，變更不該以 master 為基準。
     * zipe-spring-boot-starters 的 AGENTS.md 明訂「feature → develop → master，
     * 不可直接進 master」—— 從 master 開分支會做出一個永遠合不進去的 PR。
     * 這是慣例而非通則，所以可用 AI_TEAM_BASE_BRANCH 覆寫，並且一定記錄選了什麼。
     */
    private String detectBaseBranch(Path repo) throws WorkspaceException {
        String configured = props.baseBranch();
        if (!configured.isEmpty()) {
            log.info("開分支基準：{}（來自 AI_TEAM_BASE_BRANCH）", configured);
            return configured;
        }
        if (remoteBranchExists(repo, "develop")) {
            log.info("開分支基準：develop（遠端有 develop，視為 gitflow 專案）");
            return "develop";
        }
        try {
            String ref = gitOutput(repo, "symbolic-ref", "--short", "refs/remotes/origin/HEAD");
            String base = ref.startsWith("origin/") ? ref.substring("origin/".length()) : ref;
            log.info("開分支基準：{}（origin 的預設分支）", base);
            return base;
        } catch (WorkspaceException e) {
            String fallback = gitOutput(repo, "rev-parse", "--abbrev-ref", "HEAD");
            log.warn("讀不到 origin 的 HEAD，改用主 repo 目前的分支 {} 當基準", fallback);
            return fallback;
        }
    }

    private boolean remoteBranchExists(Path repo, String branch) {
        try {
            gitOutput(repo, "rev-parse", "--verify", "--quiet", "refs/remotes/origin/" + branch);
            return true;
        } catch (WorkspaceException e) {
            return false;
        }
    }

    private void git(Path cwd, String... args) throws WorkspaceException {
        gitOutput(cwd, args);
    }

    private String gitOutput(Path cwd, String... args) throws WorkspaceException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));

        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);
            p = pb.start();

            String out;
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (!List.of(args).contains("-z")) out = out.trim();
            }
            if (!p.waitFor(props.gitTimeout().toSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new WorkspaceException("git 逾時：" + String.join(" ", cmd));
            }
            if (p.exitValue() != 0) {
                throw new WorkspaceException(
                        "git 失敗（結束碼 " + p.exitValue() + "）：" + String.join(" ", cmd)
                      + System.lineSeparator() + out);
            }
            return out;
        } catch (WorkspaceException e) {
            throw e;
        } catch (InterruptedException e) {
            if (p != null) p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new WorkspaceException("git 執行被中斷：" + String.join(" ", cmd), e);
        } catch (IOException e) {
            throw new WorkspaceException("無法執行 git：" + String.join(" ", cmd), e);
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        try (Stream<Path> walk = Files.walk(p)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path f : paths) {
                // clone 出來的 .git 內有唯讀的物件檔，不先解除唯讀會刪不掉
                f.toFile().setWritable(true);
                Files.deleteIfExists(f);
            }
        }
    }

    public static class WorkspaceException extends Exception {
        public WorkspaceException(String message) { super(message); }
        public WorkspaceException(String message, Throwable cause) { super(message, cause); }
    }

    // ------------------------------------------------------------------ 變更稽核

    public String headSha(String taskId) throws WorkspaceException {
        return gitOutput(pathFor(taskId), "rev-parse", "HEAD");
    }

    public String origin(String taskId) throws WorkspaceException { return gitOutput(pathFor(taskId), "remote", "get-url", "origin"); }
    public String currentBranch(String taskId) throws WorkspaceException { return gitOutput(pathFor(taskId), "branch", "--show-current"); }
    public boolean clean(String taskId) throws WorkspaceException { return gitOutput(pathFor(taskId), "status", "--porcelain").isBlank(); }
    public String baseBranch(String taskId) throws WorkspaceException { return gitOutput(pathFor(taskId), "config", "--get", BASE_BRANCH_CONFIG_KEY); }
    public List<String> auditPaths(String taskId, String before) throws WorkspaceException {
        String committed = gitOutput(pathFor(taskId), "diff", "--no-renames", "--name-only", "-z", before, "HEAD");
        String dirty = gitOutput(pathFor(taskId), "diff", "--no-renames", "--name-only", "-z", "HEAD");
        String untracked = gitOutput(pathFor(taskId), "ls-files", "--others", "--exclude-standard", "-z");
        List<String> history = gitOutput(pathFor(taskId), "rev-list", "--max-count=101", before + "..HEAD").lines().filter(s -> !s.isBlank()).toList();
        if (history.size() > 100) throw new WorkspaceException("單階段新增 commit 超過稽核上限 100，禁止略過查核");
        List<String> changes = new ArrayList<>(List.of(committed, dirty, untracked));
        for (String commit : history) changes.add(gitOutput(pathFor(taskId), "diff-tree", "--root", "-m", "--no-commit-id", "--no-renames", "--name-only", "-r", "-z", commit));
        return changes.stream().flatMap(s -> java.util.Arrays.stream(s.split("\u0000"))).filter(s -> !s.isEmpty()).distinct().toList();
    }

    /**
     * 兩個 commit 之間實際變動的檔案路徑。
     *
     * 這是沙箱之外的第二道防線，存在的理由是實測發現的一個漏洞：
     * 沙箱把 src/main 釘成唯讀，但 .git 必須可寫（QA 要 commit 自己補的測試）。
     * `git checkout <舊commit> -- src/main` 會因為寫不了工作樹而報錯，
     * **但 index 已經被改掉了** —— 接著 commit 就能把產品程式碼的舊版本送進 PR，
     * 全程沒有寫過 src/main 一個位元組。
     *
     * 檔案系統擋不住索引，所以由 orchestrator 事後核對實際進了哪些路徑。
     */
    public List<String> changedPathsBetween(String taskId, String fromSha, String toSha)
            throws WorkspaceException {
        String out = gitOutput(pathFor(taskId), "diff", "--name-only", fromSha + ".." + toSha);
        return out.lines().filter(s -> !s.isBlank()).toList();
    }

    /**
     * 工作區裡相對於基準分支多出來的 commit（最新在前）。
     *
     * 用來判斷「agent 是不是其實已經把事情做完了」。
     * 這在回報階段失敗時特別重要：程式碼可能已經寫好並 commit，
     * 只是最後那份結構化輸出沒通過驗證 —— 那不該讓整輪工作被丟掉。
     */
    public List<String> commitsSinceBase(String taskId) throws WorkspaceException {
        Path ws = pathFor(taskId);
        String baseSha = gitOutput(ws, "config", "--local", "--get", BASE_SHA_CONFIG_KEY);
        if (baseSha.isBlank()) {
            throw new WorkspaceException("工作區沒有記錄基準 commit，無法判斷有哪些新 commit");
        }
        String out = gitOutput(ws, "log", "--oneline", baseSha + "..HEAD");
        return out.lines().filter(s -> !s.isBlank()).toList();
    }

    /**
     * 工作區相對建立時基準 commit 已提交的變更檔案，由系統查得而非 agent 自陳；給驗收者對照，也用來判定純文件變更。
     * `--no-renames`：把程式檔改名搬進 docs/ 時，刪除端（原程式路徑）也會列出，不會被誤判成純文件。
     * `core.quotePath=false`：中文檔名照原字輸出，不被轉成八進位跳脫而對不上路徑規則。
     */
    public List<String> changedPathsSinceBase(String taskId) throws WorkspaceException {
        Path ws = pathFor(taskId);
        String baseSha = gitOutput(ws, "config", "--local", "--get", BASE_SHA_CONFIG_KEY);
        if (baseSha.isBlank()) throw new WorkspaceException("工作區沒有記錄基準 commit，無法列出變更檔案");
        return gitOutput(ws, "-c", "core.quotePath=false", "diff", "--name-only", "--no-renames", baseSha + "..HEAD")
                .lines().filter(s -> !s.isBlank()).toList();
    }

    public record FileIdentity(String path, String before, String after) {
        public boolean unchanged() { return before.equals(after); }
    }
    /** 只讀 Git 物件，不讀檔案內容、不執行 diff driver，路徑一律按字面值解讀。 */
    public List<FileIdentity> compareFiles(String taskId, String base, String head, List<String> paths) throws WorkspaceException {
        if (base == null || head == null || !base.matches("[0-9a-f]{40,64}") || !head.matches("[0-9a-f]{40,64}"))
            throw new WorkspaceException("缺少有效的實作前 Git 版本，不能以目前版本補作比較基準");
        Path ws = pathFor(taskId);
        for (String ref : List.of(base, head))
            if (!"commit".equals(gitOutput(ws, "--no-replace-objects", "cat-file", "-t", ref))) throw new WorkspaceException("比較基準不是 Git commit");
        if (paths.isEmpty() || paths.size() > 100) throw new WorkspaceException("檔案比對須指定 1 到 100 個檔案");
        List<FileIdentity> result = new ArrayList<>();
        for (String path : paths) {
            if (!com.zipe.aiteam.flow.FileComparison.safePath(path)) throw new WorkspaceException("無效比較路徑：" + path);
            String before = fileIdentity(ws, base, path, true);
            if (before.isEmpty()) throw new WorkspaceException("實作前版本沒有指定檔案，不能證明保持不變：" + path);
            result.add(new FileIdentity(path, before, fileIdentity(ws, head, path, false)));
        }
        return List.copyOf(result);
    }
    private String fileIdentity(Path ws, String ref, String path, boolean requireRegular) throws WorkspaceException {
        String entry = gitOutput(ws, "--no-replace-objects", "--literal-pathspecs", "ls-tree", "-z", ref, "--", path);
        if (entry.isEmpty()) return "";
        int tab = entry.indexOf('\t');
        if (tab < 0 || (requireRegular && !(entry.startsWith("100644 blob ") || entry.startsWith("100755 blob "))))
            throw new WorkspaceException("比較路徑必須是一般版控檔案，不接受目錄、symlink 或子模組：" + path);
        return entry.substring(0, tab);
    }

    // ------------------------------------------------------------------ 未提交的修改

    /** 相對 HEAD 修改、刪除或新增（未追蹤、未忽略）的檔案，排序後回傳；乾淨時為空。 */
    public List<String> uncommittedPaths(String taskId) throws WorkspaceException {
        Path ws = pathFor(taskId);
        String dirty = gitOutput(ws, "diff", "--no-renames", "--name-only", "-z", "HEAD");
        String untracked = gitOutput(ws, "ls-files", "--others", "--exclude-standard", "-z");
        return java.util.stream.Stream.of(dirty, untracked)
                .flatMap(s -> java.util.Arrays.stream(s.split("\u0000")))
                .filter(s -> !s.isEmpty()).distinct().sorted().toList();
    }

    /**
     * 未提交修改的內容指紋：HEAD、已追蹤檔的完整差異、每個未追蹤檔的內容雜湊。
     * 停止訊息的「保存並承接」按鈕綁定這個值：訊息貼出後內容若又變動，按鈕就失效，
     * 避免使用者同意的是 A、系統實際保存的是 B。
     */
    public String uncommittedFingerprint(String taskId) throws WorkspaceException {
        Path ws = pathFor(taskId);
        StringBuilder material = new StringBuilder(headSha(taskId)).append('\n')
                .append(gitOutput(ws, "diff", "--no-renames", "--binary", "HEAD")).append('\n');
        String untracked = gitOutput(ws, "ls-files", "--others", "--exclude-standard", "-z");
        for (String path : untracked.split("\u0000")) {
            if (path.isEmpty()) continue;
            material.append(path).append('\u0000').append(gitOutput(ws, "hash-object", "--", path)).append('\n');
        }
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new WorkspaceException("無法計算未提交修改的指紋", e);
        }
    }

    /**
     * 以工作區設定的身分 commit 指定路徑（含刪除與新增），回傳新的 HEAD。
     * 停用 repository 自帶的 hooks：這是系統代使用者保存，不讓專案腳本在 orchestrator 身分下執行。
     */
    public String commitPaths(String taskId, List<String> paths, String message) throws WorkspaceException {
        if (paths == null || paths.isEmpty()) throw new WorkspaceException("沒有要保存的檔案");
        Path ws = pathFor(taskId);
        List<String> add = new ArrayList<>(List.of("add", "-A", "--"));
        add.addAll(paths);
        git(ws, add.toArray(String[]::new));
        git(ws, "-c", "core.hooksPath=/dev/null", "commit", "--no-verify", "-m", message);
        return headSha(taskId);
    }

    static final String EXCLUDE_BEGIN = "# >>> ai-team 操作輸出目錄（系統管理，勿手改）";
    static final String EXCLUDE_END = "# <<< ai-team 操作輸出目錄";

    /**
     * 把固定操作宣告的輸出目錄寫進本工作區的 .git/info/exclude（只在本機，不會被 commit），回傳實際排除的目錄。
     * #1044：QA 把 docs-site 複製到 target/qa-docs 建置，產品 repo 沒忽略 target/，寫入範圍稽核把副本的
     * package-lock.json 當成修改受保護檔案；「保存並承接」也會把副本 commit 進去。不要求每個專案自己改 .gitignore。
     * 目錄底下有任何被追蹤的檔案就不排除（fail closed），避免把真正的原始碼從稽核與提交裡藏起來。
     * 每次呼叫重寫整個管理區塊，可重複執行；使用者自己在 exclude 寫的其他內容保留。
     */
    public List<String> excludeOutputDirs(String taskId, java.util.Collection<String> dirs) throws WorkspaceException {
        Path ws = pathFor(taskId);
        List<String> excluded = untrackedOutputDirs(ws, dirs);
        try {
            Path file = ws.resolve(".git/info/exclude");
            Files.createDirectories(file.getParent());
            List<String> kept = new ArrayList<>();
            boolean inside = false;
            for (String line : Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.<String>of()) {
                if (line.equals(EXCLUDE_BEGIN)) inside = true;
                else if (line.equals(EXCLUDE_END)) inside = false;
                else if (!inside) kept.add(line);
            }
            if (!excluded.isEmpty()) {
                kept.add(EXCLUDE_BEGIN);
                excluded.forEach(d -> kept.add("/" + d + "/"));
                kept.add(EXCLUDE_END);
            }
            Files.write(file, kept, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new WorkspaceException("無法更新工作區的 .git/info/exclude", e);
        }
        return List.copyOf(excluded);
    }

    /**
     * 宣告的操作輸出目錄中，底下沒有任何 git 追蹤檔的安全相對目錄（排序後回傳）。排除稽核與沙箱略過唯讀釘選共用這個判斷。
     * 每次都重新問 git，不讀 .git/info/exclude：限寫節點可以寫 .git，不能讓 agent 改那個檔來解除保護。
     */
    public List<String> untrackedOutputDirs(Path ws, java.util.Collection<String> dirs) throws WorkspaceException {
        List<String> result = new ArrayList<>();
        for (String dir : new java.util.TreeSet<>(dirs)) {
            if (dir == null || !dir.matches("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*")
                    || java.util.Arrays.stream(dir.split("/")).anyMatch(s -> s.equals(".") || s.equals(".."))
                    || dir.startsWith(".git")) continue;
            if (!gitOutput(ws, "ls-files", "-z", "--", dir).isEmpty()) continue;
            result.add(dir);
        }
        return List.copyOf(result);
    }

    /** 工作區是否存在且是 git repository。 */
    public boolean exists(String taskId) {
        return Files.isDirectory(pathFor(taskId).resolve(".git"));
    }
}
