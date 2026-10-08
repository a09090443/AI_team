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
    /**
     * @param docOnlyChanges 非 null 表示系統依 git diff 判定為純文件變更、依配置略過建置測試；此時 commands 為空，
     *                       這裡列出判定所依據的變更檔案。null 為一般收據。
     */
    public record Receipt(String head, String baseline, String configuration, String startedAt, String completedAt,
                          List<CommandResult> commands, List<String> docOnlyChanges) {
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
        // 純文件變更：由系統查得的變更檔案判定，不採信 agent 自陳；判定不了（查不到基準等）就照常全跑。
        if (spec.docOnlyPaths() != null) {
            List<String> changed;
            try { changed = workspaces.changedPathsSinceBase(task.id()); }
            catch (Exception e) { changed = List.of(); }
            if (DocOnlyChanges.qualifies(spec.docOnlyPaths(), changed)) {
                String now = Instant.now().toString();
                if (!head.equals(workspaces.headSha(task.id())) || !workspaces.clean(task.id())) throw new IllegalStateException("驗證期間來源工作區變更，收據作廢");
                return new Receipt(head, flow.baselineRevision, flow.snapshot.revision(), now, now, List.of(), List.copyOf(changed));
            }
        }
        Path root = props.homePath().resolve("verification-runs").resolve(task.id()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path run = Files.createTempDirectory(root, "run-");
        Path writable = Files.createDirectory(run.resolve("work"));
        Path home = Files.createDirectory(writable.resolve("home"));
        Path repo = writable.resolve("repo");
        Instant started = Instant.now();
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
        var policy = new SandboxPolicy(List.of(writable), List.copyOf(pins), 4096, 200, 256);
        List<CommandResult> results = new ArrayList<>();
        for (List<String> argv : spec.commands()) {
            Path log = run.resolve("check-" + results.size() + ".log");
            try { execute(task.id(), argv, repo, home, policy, log, deadline); }
            catch (CommandExit exit) { throw classify(exit); }
            results.add(new CommandResult(List.copyOf(argv), 0, log.toString(), hash(log)));
        }
        if (!head.equals(workspaces.headSha(task.id())) || !workspaces.clean(task.id())) throw new IllegalStateException("驗證期間來源工作區變更，收據作廢");
        return new Receipt(head, flow.baselineRevision, flow.snapshot.revision(), started.toString(), Instant.now().toString(), List.copyOf(results));
    }
    public String blockingReason(FlowRun flow, String head) {
        Receipt receipt = flow.independentEvidence;
        var spec = flow.snapshot.definition().project().verification();
        if (receipt == null || spec == null) return "缺少系統獨立驗證收據";
        if (!Objects.equals(head, receipt.head()) || !Objects.equals(flow.baselineRevision, receipt.baseline())
                || !Objects.equals(flow.snapshot.revision(), receipt.configuration())) return "獨立驗證的 commit／需求／配置版本已失效";
        if (receipt.docOnly())
            return receipt.commands() != null && receipt.commands().isEmpty()
                    && DocOnlyChanges.qualifies(spec.docOnlyPaths(), receipt.docOnlyChanges())
                    ? null : "純文件收據不符配置或含非文件變更";
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
    void execute(List<String> argv, Path cwd, Path home, SandboxPolicy policy, Path log, long deadline) throws Exception {
        execute(null, argv, cwd, home, policy, log, deadline);
    }
    /** unit 名帶任務編號，Slack 的「中斷這一輪」才能只停這個任務的驗證（見 TaskUnits）。 */
    void execute(String taskId, List<String> argv, Path cwd, Path home, SandboxPolicy policy, Path log, long deadline) throws Exception {
        long seconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime()));
        if (System.nanoTime() >= deadline) throw new IllegalStateException("獨立驗證超時；紀錄：" + log.getParent());
        String unit = props.unitPrefix() + "check-" + (taskId == null ? "" : taskId + "-") + UUID.randomUUID();
        List<String> isolated = new ArrayList<>(List.of("env", "-i", "PATH=" + System.getenv("PATH"), "HOME=" + home,
                "LANG=C.UTF-8", "CI=true", "MAKEFLAGS=-j1", "JOBS=1", "GIT_CONFIG_NOSYSTEM=1", "GIT_CONFIG_GLOBAL=/dev/null", "GIT_TERMINAL_PROMPT=0"));
        // JVM 的 user.home 不會隨 HOME 改變；否則 Maven 仍讀寫真實帳號的 .m2。
        isolated.add("JAVA_TOOL_OPTIONS=-Duser.home=\"" + home.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        String java = System.getenv("JAVA_HOME"); if (java != null) isolated.add("JAVA_HOME=" + java);
        isolated.addAll(argv);
        List<String> command = new ArrayList<>(sandbox.wrap(unit, policy, cwd, isolated));
        int delimiter = command.indexOf("--");
        command.addAll(delimiter, List.of("-p", "RuntimeMaxSec=" + seconds, "-p", "KillMode=control-group"));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        sanitize(builder);
        Process process = com.zipe.aiteam.sandbox.ArgumentListTooLong.start(builder);
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() >= deadline || Files.size(log) > 8_000_000) throw new IllegalStateException("獨立驗證超時或輸出超過 8 MB；紀錄：" + log);
            }
            if (Files.size(log) > 8_000_000) throw new IllegalStateException("獨立驗證輸出超過 8 MB；紀錄：" + log);
            if (process.exitValue() != 0) throw new CommandExit(argv, process.exitValue(), log);
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
