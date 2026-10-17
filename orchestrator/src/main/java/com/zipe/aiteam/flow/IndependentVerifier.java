package com.zipe.aiteam.flow;

import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.sandbox.*;
import com.zipe.aiteam.task.Task;
import com.zipe.aiteam.workspace.WorkspaceManager;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 系統執行固定驗證 argv，從最終 commit 的全新 clone 取得獨立收據；不執行模型提供的指令。 */
@Component
public class IndependentVerifier {
    public record CommandResult(List<String> argv, int exitCode, String log, String sha256) {}
    public record FileEvidence(String base, String head, String planDigest, List<WorkspaceManager.FileIdentity> files,
                               String log, String sha256) {}
    /**
     * @param docOnlyChanges 非 null 表示系統依 git diff 判定為純文件變更、依配置略過建置測試；此時 commands 為空，
     *                       這裡列出判定所依據的變更檔案。null 為一般收據。
     */
    /**
     * @param decisions    2026-10-17 起的收據：與配置指令一一對應的執行／略過／重用決定（見 {@link VerificationPlan}）；
     *                     null 為舊收據，沿用 commands 逐一核對。commands 此時只列本輪實際執行的結果。
     * @param changedFiles 判定所依據的變更檔案（最多 500 個）；查不到時為 null。
     * @param taskId       發布前重算變更與指紋所需的任務編號。
     */
    public record Receipt(String head, String baseline, String configuration, String startedAt, String completedAt,
                          List<CommandResult> commands, List<String> docOnlyChanges, FileEvidence fileComparison,
                          List<VerificationPlan.Decision> decisions, List<String> changedFiles, String taskId) {
        public Receipt(String head, String baseline, String configuration, String startedAt, String completedAt,
                       List<CommandResult> commands, List<String> docOnlyChanges, FileEvidence fileComparison) {
            this(head, baseline, configuration, startedAt, completedAt, commands, docOnlyChanges, fileComparison, null, null, null);
        }
        public Receipt(String head, String baseline, String configuration, String startedAt, String completedAt,
                       List<CommandResult> commands, List<String> docOnlyChanges) {
            this(head, baseline, configuration, startedAt, completedAt, commands, docOnlyChanges, null);
        }
        public Receipt(String head, String baseline, String configuration, String startedAt, String completedAt,
                       List<CommandResult> commands) {
            this(head, baseline, configuration, startedAt, completedAt, commands, null);
        }
        public boolean docOnly() { return docOnlyChanges != null; }
    }

    /**
     * 管理者配置的檢查指令執行完畢但結束碼不為 0：產品程式或測試沒有通過，屬於實作的責任，
     * 不是驗證機制故障。clone、checkout、逾時、輸出過大與環境缺件（相依套件抓不到、磁碟滿等）
     * 仍以一般例外回報，交由真人判斷。
     */
    public static final class CheckFailed extends IllegalStateException {
        private final List<String> argv; private final int exitCode; private final String log; private final String summary;
        CheckFailed(List<String> argv, int exitCode, String log, String summary) {
            super("獨立驗證失敗，exit=" + exitCode + "；指令=" + argv + "；紀錄：" + log);
            this.argv = List.copyOf(argv); this.exitCode = exitCode; this.log = log; this.summary = summary;
        }
        public List<String> argv() { return argv; }
        public int exitCode() { return exitCode; }
        public String log() { return log; }
        public String summary() { return summary; }
    }

    /**
     * 獨立驗證超過 verification.timeoutSeconds 被中止：不是測試失敗，不交回實作重做。
     * #1044：每輪約 28 分鐘貼著 1800 秒上限，systemd 的 RuntimeMaxSec 先砍掉 npm test，非零結束被當成測試失敗，
     * 派回實作多跑一整輪。
     */
    public static final class VerificationTimeout extends IllegalStateException {
        VerificationTimeout(List<String> argv, Path log) {
            super("獨立驗證超過時間上限（verification.timeoutSeconds），在指令 " + argv
                    + " 執行中被中止；這不是測試失敗，系統不會交回實作。紀錄：" + log);
        }
    }

    /** 非零結束時剩餘時間已不到這個值，就視為被時間上限砍掉；RuntimeMaxSec 以整秒向下取整，可能早約一秒。 */
    static final long TIMEOUT_MARGIN_NANOS = TimeUnit.SECONDS.toNanos(3);
    static boolean timedOut(long deadline, long now) { return now >= deadline - TIMEOUT_MARGIN_NANOS; }

    /** 出現這些字樣表示是環境缺件，交給實作也修不好。 */
    private static final List<String> ENVIRONMENT_SIGNATURES = List.of(
            "Could not resolve dependencies", "Non-resolvable", "Could not transfer artifact",
            "Could not find artifact", "UnknownHostException", "No space left on device",
            "unable to create native thread", "command not found", "Failed to start transient");

    static boolean environmental(String log) {
        return ENVIRONMENT_SIGNATURES.stream().anyMatch(log::contains);
    }

    /** 指令執行完畢但結束碼不為 0；是否算程式失敗由 classify 依紀錄內容判定。 */
    static class CommandExit extends IllegalStateException {
        final List<String> argv; final int exitCode; final Path log;
        CommandExit(List<String> argv, int exitCode, Path log) {
            super("獨立驗證失敗，exit=" + exitCode + "；指令=" + argv + "；紀錄：" + log);
            this.argv = List.copyOf(argv); this.exitCode = exitCode; this.log = log;
        }
    }

    /** 配置檢查指令的非零結束：看得出是環境缺件就原樣回報給人，其餘視為程式或測試失敗。 */
    static IllegalStateException classify(CommandExit exit) {
        String text;
        try { text = new String(Files.readAllBytes(exit.log), StandardCharsets.UTF_8); }
        catch (Exception e) { return exit; }
        if (environmental(text)) return exit;
        return new CheckFailed(exit.argv, exit.exitCode, exit.log.toString(), summarize(text));
    }
    private final AiTeamProperties props;
    private final ExecutionSandbox sandbox;
    private final WorkspaceManager workspaces;
    public IndependentVerifier(AiTeamProperties props, ExecutionSandbox sandbox, WorkspaceManager workspaces) {
        this.props = props; this.sandbox = sandbox; this.workspaces = workspaces;
    }
    public Receipt verify(Task task, FlowRun flow) throws Exception {
        sandbox.requireReadyForCodeChanges();
        if (!task.id().matches("[0-9]+")) throw new IllegalStateException("無效任務 ID");
        String head = workspaces.headSha(task.id());
        if (!head.matches("[0-9a-f]{40,64}") || !workspaces.clean(task.id())) throw new IllegalStateException("獨立驗證需要已提交且乾淨的最終版本");
        var spec = flow.snapshot.definition().project().verification();
        if (spec == null) throw new IllegalStateException("缺少管理者配置的驗證指令");
        FileEvidence fileEvidence = compareFiles(task, flow, head);
        // 純文件變更：由系統查得的變更檔案判定，不採信 agent 自陳；判定不了（查不到基準等）就照常全跑。
        if (spec.docOnlyPaths() != null) {
            List<String> changed;
            try { changed = workspaces.changedPathsSinceBase(task.id()); }
            catch (Exception e) { changed = List.of(); }
            if (DocOnlyChanges.qualifies(spec.docOnlyPaths(), changed)) {
                String now = Instant.now().toString();
                if (!head.equals(workspaces.headSha(task.id())) || !workspaces.clean(task.id())) throw new IllegalStateException("驗證期間來源工作區變更，收據作廢");
                return new Receipt(head, flow.baselineRevision, flow.snapshot.revision(), now, now, List.of(), List.copyOf(changed), fileEvidence);
            }
        }
        // 依變更檔案決定每個指令要執行、略過或重用（VerificationPlan）；任何查不到的情況都全跑。
        List<String> changed;
        try { changed = workspaces.changedPathsSinceBase(task.id()); }
        catch (Exception e) { changed = null; }
        var selection = VerificationPlan.select(spec, changed);
        List<String> prints = VerificationPlan.fingerprints(spec, workspaces.treeEntries(task.id(), head));
        List<VerificationPlan.Decision> decisions = new ArrayList<>();
        for (int i = 0; i < spec.commands().size(); i++) {
            List<String> argv = List.copyOf(spec.commands().get(i));
            if (!selection.needs(i)) {
                decisions.add(new VerificationPlan.Decision(i, argv, VerificationPlan.Action.SKIP,
                        "變更檔案都不在這個指令的範圍內（純文件、QA 專屬或屬於其他指令）", List.of(), prints.get(i), null, null, null));
                continue;
            }
            List<String> matched = selection.fullRun() ? List.of() : List.copyOf(selection.matched().get(i));
            VerificationPlan.HistoryEntry previous = reusable(flow, argv, prints.get(i));
            if (previous != null) decisions.add(new VerificationPlan.Decision(i, argv, VerificationPlan.Action.REUSE,
                    "相同輸入指紋已於 commit " + previous.head() + " 通過", matched, prints.get(i), previous.log(), previous.sha256(), previous.head()));
            else decisions.add(new VerificationPlan.Decision(i, argv, VerificationPlan.Action.RUN,
                    selection.fullRun() ? selection.fullRunReason() : "範圍內有變更", matched, prints.get(i), null, null, null));
        }
        List<String> changedFiles = changed == null ? null : List.copyOf(changed.subList(0, Math.min(500, changed.size())));
        Instant started = Instant.now();
        if (decisions.stream().noneMatch(d -> d.action() == VerificationPlan.Action.RUN)) {
            if (!head.equals(workspaces.headSha(task.id())) || !workspaces.clean(task.id())) throw new IllegalStateException("驗證期間來源工作區變更，收據作廢");
            return new Receipt(head, flow.baselineRevision, flow.snapshot.revision(), started.toString(), Instant.now().toString(),
                    List.of(), null, fileEvidence, List.copyOf(decisions), changedFiles, task.id());
        }
        Path root = props.homePath().resolve("verification-runs").resolve(task.id()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path run = Files.createTempDirectory(root, "run-");
        Path writable = Files.createDirectory(run.resolve("work"));
        Path home = Files.createDirectory(writable.resolve("home"));
        Path repo = writable.resolve("repo");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(spec.timeoutSeconds());
        var setupPolicy = new SandboxPolicy(List.of(writable), List.of(), 2048, 200, 256);
        execute(task.id(), List.of("git", "-c", "core.hooksPath=/dev/null", "clone", "--no-local", "--no-checkout", "--",
                workspaces.pathFor(task.id()).toAbsolutePath().toString(), repo.toString()), writable, home, setupPolicy, run.resolve("clone.log"), deadline);
        execute(task.id(), List.of("git", "-c", "core.hooksPath=/dev/null", "checkout", "--detach", head), repo, home, setupPolicy, run.resolve("checkout.log"), deadline);
        List<Path> pins = new ArrayList<>(); pins.add(repo.resolve(".git"));
        // 全新 checkout 只有版控檔；釘住每個檔案，避免測試指令先改產品／測試再回報成功。
        try (var files = Files.walk(repo)) {
            for (Path path : files.toList()) {
                if (Files.isSymbolicLink(path)) throw new IllegalStateException("獨立驗證不接受 symlink，需管理者評估：" + repo.relativize(path));
                if (!path.startsWith(repo.resolve(".git")) && Files.isRegularFile(path)) pins.add(path);
            }
        }
        var policy = new SandboxPolicy(List.of(writable, dependencyCache()), List.copyOf(pins), 4096, 200, 256);
        List<CommandResult> results = new ArrayList<>();
        for (int i = 0; i < decisions.size(); i++) {
            VerificationPlan.Decision decision = decisions.get(i);
            if (decision.action() != VerificationPlan.Action.RUN) continue;
            Path log = run.resolve("check-" + i + ".log");
            try { execute(task.id(), decision.argv(), repo, home, policy, log, deadline); }
            catch (CommandExit exit) { throw classify(exit); }
            CommandResult result = new CommandResult(decision.argv(), 0, log.toString(), hash(log));
            results.add(result);
            decisions.set(i, new VerificationPlan.Decision(i, decision.argv(), decision.action(), decision.reason(), decision.matched(),
                    decision.fingerprint(), result.log(), result.sha256(), null));
            // 每通過一個就記下來：後面的指令失敗時，修正後的下一輪仍可重用輸入沒變的這個結果。
            remember(flow, new VerificationPlan.HistoryEntry(decision.argv(), decision.fingerprint(), flow.snapshot.revision(), head, result.log(), result.sha256()));
        }
        if (!head.equals(workspaces.headSha(task.id())) || !workspaces.clean(task.id())) throw new IllegalStateException("驗證期間來源工作區變更，收據作廢");
        return new Receipt(head, flow.baselineRevision, flow.snapshot.revision(), started.toString(), Instant.now().toString(),
                List.copyOf(results), null, fileEvidence, List.copyOf(decisions), changedFiles, task.id());
    }
    /** 同一配置版本、同一指令、相同輸入指紋、紀錄檔仍在且雜湊相符的歷史結果；找不到回 null。 */
    VerificationPlan.HistoryEntry reusable(FlowRun flow, List<String> argv, String fingerprint) {
        if (flow.verificationHistory == null) return null;
        for (int i = flow.verificationHistory.size() - 1; i >= 0; i--) {
            var entry = flow.verificationHistory.get(i);
            if (entry == null || !argv.equals(entry.argv()) || !fingerprint.equals(entry.fingerprint())
                    || !flow.snapshot.revision().equals(entry.configuration())) continue;
            if (logIntact(entry.log(), entry.sha256())) return entry;
        }
        return null;
    }
    static void remember(FlowRun flow, VerificationPlan.HistoryEntry entry) {
        if (flow.verificationHistory == null) flow.verificationHistory = new ArrayList<>();
        flow.verificationHistory.add(entry);
        while (flow.verificationHistory.size() > VerificationPlan.HISTORY_LIMIT) flow.verificationHistory.removeFirst();
    }
    private boolean logIntact(String log, String sha256) {
        try {
            Path root = props.homePath().resolve("verification-runs").toRealPath();
            Path path = Path.of(log);
            return path.toRealPath().startsWith(root) && hash(path).equals(sha256);
        } catch (Exception e) { return false; }
    }
    FileEvidence compareFiles(Task task, FlowRun flow, String head) throws Exception {
        List<String> paths = FileComparison.paths(flow);
        if (paths.isEmpty()) return null;
        List<WorkspaceManager.FileIdentity> files = workspaces.compareFiles(task.id(), flow.comparisonBase, head, paths);
        Path root = props.homePath().resolve("verification-runs").resolve(task.id());
        Files.createDirectories(root);
        Path log = Files.createTempFile(root, "file-comparison-", ".json");
        Files.writeString(log, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(files));
        List<String> changed = files.stream().filter(f -> !f.unchanged()).map(WorkspaceManager.FileIdentity::path).toList();
        if (!changed.isEmpty()) throw new CheckFailed(List.of(FileComparison.OPERATION), 1, log.toString(),
                "指定舊檔與實作前版本不同，請恢復原檔後重驗，不要刪除驗收條件：" + changed);
        return new FileEvidence(flow.comparisonBase, head, FileComparison.planDigest(flow), files, log.toString(), hash(log));
    }
    public String blockingReason(FlowRun flow, String head) {
        Receipt receipt = flow.independentEvidence;
        var spec = flow.snapshot.definition().project().verification();
        if (receipt == null || spec == null) return "缺少系統獨立驗證收據";
        if (!Objects.equals(head, receipt.head()) || !Objects.equals(flow.baselineRevision, receipt.baseline())
                || !Objects.equals(flow.snapshot.revision(), receipt.configuration())) return "獨立驗證的 commit／需求／配置版本已失效";
        String fileBlock = fileComparisonReason(flow, receipt, head);
        if (fileBlock != null) return fileBlock;
        if (receipt.docOnly())
            return receipt.commands() != null && receipt.commands().isEmpty()
                    && DocOnlyChanges.qualifies(spec.docOnlyPaths(), receipt.docOnlyChanges())
                    ? null : "純文件收據不符配置或含非文件變更";
        if (receipt.decisions() != null) return decisionReason(receipt, spec, head);
        if (receipt.commands() == null || receipt.commands().size() != spec.commands().size()) return "獨立驗證缺少必要指令結果";
        try {
            Path root = props.homePath().resolve("verification-runs").toRealPath();
            for (int i = 0; i < receipt.commands().size(); i++) {
                CommandResult result = receipt.commands().get(i);
                Path log = Path.of(result.log());
                if (result.exitCode() != 0 || !spec.commands().get(i).equals(result.argv())) return "獨立驗證指令未通過或不符配置";
                if (!log.toRealPath().startsWith(root) || !hash(log).equals(result.sha256())) return "獨立驗證紀錄遺失或雜湊不符";
            }
        } catch (Exception e) { return "無法核實獨立驗證紀錄：" + e.getClass().getSimpleName(); }
        return null;
    }
    /**
     * 新收據的發布前核對：從工作區重新算變更清單與輸入指紋，不只相信收據。
     * 略過的指令重算後必須仍不需要跑；執行與重用的指令指紋要相同、紀錄檔要在且雜湊相符。
     */
    String decisionReason(Receipt receipt, TeamDefinition.Verification spec, String head) {
        var decisions = receipt.decisions();
        if (decisions.size() != spec.commands().size() || receipt.taskId() == null || !receipt.taskId().matches("[0-9]+"))
            return "獨立驗證缺少必要指令決定";
        try {
            List<String> changed;
            try { changed = workspaces.changedPathsSinceBase(receipt.taskId()); }
            catch (Exception e) { changed = null; }
            var selection = VerificationPlan.select(spec, changed);
            List<String> prints = VerificationPlan.fingerprints(spec, workspaces.treeEntries(receipt.taskId(), head));
            for (int i = 0; i < decisions.size(); i++) {
                var d = decisions.get(i);
                if (d == null || d.index() != i || d.action() == null || !spec.commands().get(i).equals(d.argv()))
                    return "獨立驗證決定與配置指令不符";
                if (!prints.get(i).equals(d.fingerprint())) return "獨立驗證的輸入指紋已失效：" + String.join(" ", d.argv());
                if (d.action() == VerificationPlan.Action.SKIP) {
                    if (selection.needs(i)) return "略過的指令重算後需要執行：" + String.join(" ", d.argv());
                } else if (d.log() == null || d.sha256() == null || !logIntact(d.log(), d.sha256())) {
                    return "獨立驗證紀錄遺失或雜湊不符：" + String.join(" ", d.argv());
                }
            }
        } catch (Exception e) { return "無法核實獨立驗證決定：" + e.getClass().getSimpleName(); }
        return null;
    }
    String fileComparisonReason(FlowRun flow, Receipt receipt, String head) {
        try {
            List<String> paths = FileComparison.paths(flow);
            if (paths.isEmpty()) return null;
            FileEvidence e = receipt.fileComparison();
            if (e == null || !Objects.equals(e.base(), flow.comparisonBase) || !Objects.equals(e.head(), head)
                    || !Objects.equals(e.planDigest(), FileComparison.planDigest(flow))) return "缺少有效的指定檔案比對收據";
            if (e.files() == null || !paths.equals(e.files().stream().map(WorkspaceManager.FileIdentity::path).toList())
                    || e.files().stream().anyMatch(f -> f.before().isEmpty() || !f.unchanged())) return "指定檔案比對未通過或未完整覆蓋";
            Path log = Path.of(e.log());
            if (!log.toRealPath().startsWith(props.homePath().resolve("verification-runs").toRealPath())
                    || !hash(log).equals(e.sha256())
                    || !new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(log))
                        .equals(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(e.files()))) return "指定檔案比對紀錄遺失或內容不符";
            return null;
        } catch (Exception e) { return "無法核實指定檔案比對：" + e.getClass().getSimpleName(); }
    }
    /**
     * 獨立驗證專用的相依套件下載快取（Gradle、npm、Maven），各實例一份，跨任務沿用。
     * #1044：每次都用空的 HOME，Gradle 第一個指令就花 16～19 分鐘重新下載與準備相依套件。
     * 只有系統驗證指令可寫；agent 的沙箱不含這個目錄。被驗證的專案程式在執行期間仍可寫入它，這是換取速度的已知取捨。
     */
    Path dependencyCache() throws java.io.IOException {
        Path cache = props.homePath().resolve("verification-cache").toAbsolutePath().normalize();
        Files.createDirectories(cache);
        if (!cache.toRealPath().equals(cache)) throw new IllegalStateException("獨立驗證快取路徑含 symlink，拒絕使用：" + cache);
        return cache;
    }
    /** 驗證指令的快取環境變數；只在政策確實把快取設為可寫時加入，clone／checkout 不使用。 */
    static List<String> cacheEnvironment(Path cache, SandboxPolicy policy) {
        if (cache == null || !policy.writablePaths().contains(cache) || cache.toString().matches(".*[\\s\"'\\\\].*")) return List.of();
        return List.of("GRADLE_USER_HOME=" + cache.resolve("gradle"), "npm_config_cache=" + cache.resolve("npm"),
                "MAVEN_OPTS=-Dmaven.repo.local=" + cache.resolve("m2/repository"));
    }
    void execute(List<String> argv, Path cwd, Path home, SandboxPolicy policy, Path log, long deadline) throws Exception {
        execute(null, argv, cwd, home, policy, log, deadline);
    }
    /** unit 名帶任務編號，Slack 的「中斷這一輪」才能只停這個任務的驗證（見 TaskUnits）。 */
    void execute(String taskId, List<String> argv, Path cwd, Path home, SandboxPolicy policy, Path log, long deadline) throws Exception {
        long seconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime()));
        if (System.nanoTime() >= deadline) throw new VerificationTimeout(argv, log.getParent());
        String unit = props.unitPrefix() + "check-" + (taskId == null ? "" : taskId + "-") + UUID.randomUUID();
        List<String> isolated = new ArrayList<>(List.of("env", "-i", "PATH=" + System.getenv("PATH"), "HOME=" + home,
                "LANG=C.UTF-8", "CI=true", "MAKEFLAGS=-j1", "JOBS=1", "GIT_CONFIG_NOSYSTEM=1", "GIT_CONFIG_GLOBAL=/dev/null", "GIT_TERMINAL_PROMPT=0"));
        // JVM 的 user.home 不會隨 HOME 改變；否則 Maven 仍讀寫真實帳號的 .m2。
        isolated.add("JAVA_TOOL_OPTIONS=-Duser.home=\"" + home.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        String java = System.getenv("JAVA_HOME"); if (java != null) isolated.add("JAVA_HOME=" + java);
        isolated.addAll(cacheEnvironment(props.homePath().resolve("verification-cache").toAbsolutePath().normalize(), policy));
        isolated.addAll(argv);
        List<String> command = new ArrayList<>(sandbox.wrap(unit, policy, cwd, isolated));
        int delimiter = command.indexOf("--");
        command.addAll(delimiter, List.of("-p", "RuntimeMaxSec=" + seconds, "-p", "KillMode=control-group"));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        sanitize(builder);
        Process process = com.zipe.aiteam.sandbox.ArgumentListTooLong.start(builder);
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() >= deadline) throw new VerificationTimeout(argv, log);
                if (Files.size(log) > 8_000_000) throw new IllegalStateException("獨立驗證輸出超過 8 MB；紀錄：" + log);
            }
            if (Files.size(log) > 8_000_000) throw new IllegalStateException("獨立驗證輸出超過 8 MB；紀錄：" + log);
            if (process.exitValue() != 0) {
                if (timedOut(deadline, System.nanoTime())) throw new VerificationTimeout(argv, log);
                throw new CommandExit(argv, process.exitValue(), log);
            }
        } finally {
            // 停止整個 unit，不只結束 systemd-run 用戶端；RuntimeMaxSec 是第二道上限。
            ProcessBuilder stop = new ProcessBuilder("systemctl", "--user", "stop", unit).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            sanitize(stop);
            try { Process stopping = stop.start(); if (!stopping.waitFor(5, TimeUnit.SECONDS)) stopping.destroyForcibly(); }
            finally { if (process.isAlive()) process.destroyForcibly(); }
        }
    }
    /**
     * 從建置紀錄挑出失敗的測試與編譯錯誤，讓負責修的 agent 不必先翻兩千行 log。
     * 挑不到時退回最後 40 行。
     */
    static String summarize(String log) {
        List<String> picked = new ArrayList<>();
        String[] lines = log.split("\\R");
        for (String line : lines) {
            if (line.contains("<<< FAILURE!") || line.contains("<<< ERROR!") || line.startsWith("[ERROR]   ")
                    || line.contains("COMPILATION ERROR") || line.matches("\\[ERROR\\] .*\\.(java|kt|ts|js):.*")
                    || line.matches("\\[ERROR\\] Tests run:.*"))
                if (!picked.contains(line)) picked.add(line);
            if (picked.size() >= 40) break;
        }
        if (picked.isEmpty())
            picked = Arrays.asList(lines).subList(Math.max(0, lines.length - 40), lines.length);
        String joined = String.join("\n", picked);
        return joined.length() > 3000 ? joined.substring(0, 3000) + "\n…（其餘見完整紀錄）" : joined;
    }
    private static void sanitize(ProcessBuilder builder) {
        var inherited = new HashMap<>(builder.environment()); builder.environment().clear();
        for (String key : List.of("PATH", "HOME", "LANG", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS"))
            if (inherited.containsKey(key)) builder.environment().put(key, inherited.get(key));
    }
    static String hash(Path file) throws Exception {
        if (!Files.isRegularFile(file) || Files.size(file) > 8_500_000) throw new IllegalStateException("證據檔案不存在或過大");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
