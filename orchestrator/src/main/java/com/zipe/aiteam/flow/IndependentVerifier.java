package com.zipe.aiteam.flow;

import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.sandbox.*;
import com.zipe.aiteam.task.Task;
import com.zipe.aiteam.workspace.WorkspaceManager;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 系統執行固定驗證 argv，從最終 commit 的全新 clone 取得獨立收據；不執行模型提供的指令。 */
@Component
public class IndependentVerifier {
    public record CommandResult(List<String> argv, int exitCode, String log, String sha256) {}
    public record Receipt(String head, String baseline, String configuration, String startedAt, String completedAt,
                          List<CommandResult> commands) {}
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
        Path root = props.homePath().resolve("verification-runs").resolve(task.id()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path run = Files.createTempDirectory(root, "run-");
        Path writable = Files.createDirectory(run.resolve("work"));
        Path home = Files.createDirectory(writable.resolve("home"));
        Path repo = writable.resolve("repo");
        Instant started = Instant.now();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(spec.timeoutSeconds());
        var setupPolicy = new SandboxPolicy(List.of(writable), List.of(), 2048, 200, 256);
        execute(List.of("git", "-c", "core.hooksPath=/dev/null", "clone", "--no-local", "--no-checkout", "--",
                workspaces.pathFor(task.id()).toAbsolutePath().toString(), repo.toString()), writable, home, setupPolicy, run.resolve("clone.log"), deadline);
        execute(List.of("git", "-c", "core.hooksPath=/dev/null", "checkout", "--detach", head), repo, home, setupPolicy, run.resolve("checkout.log"), deadline);
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
            execute(argv, repo, home, policy, log, deadline);
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
        long seconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime()));
        if (System.nanoTime() >= deadline) throw new IllegalStateException("獨立驗證超時；紀錄：" + log.getParent());
        String unit = "ai-team-check-" + UUID.randomUUID();
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
        Process process = builder.start();
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() >= deadline || Files.size(log) > 8_000_000) throw new IllegalStateException("獨立驗證超時或輸出超過 8 MB；紀錄：" + log);
            }
            if (Files.size(log) > 8_000_000 || process.exitValue() != 0)
                throw new IllegalStateException("獨立驗證失敗，exit=" + process.exitValue() + "；指令=" + argv + "；紀錄：" + log);
        } finally {
            // 停止整個 unit，不只結束 systemd-run 用戶端；RuntimeMaxSec 是第二道上限。
            ProcessBuilder stop = new ProcessBuilder("systemctl", "--user", "stop", unit).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            sanitize(stop);
            try { Process stopping = stop.start(); if (!stopping.waitFor(5, TimeUnit.SECONDS)) stopping.destroyForcibly(); }
            finally { if (process.isAlive()) process.destroyForcibly(); }
        }
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
