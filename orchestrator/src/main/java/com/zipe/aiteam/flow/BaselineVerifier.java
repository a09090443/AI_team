package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.sandbox.ExecutionSandbox;
import com.zipe.aiteam.sandbox.SandboxPolicy;
import com.zipe.aiteam.workspace.WorkspaceManager;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 在基準分支（還沒有任何任務修改）的最新 commit 跑一次配置的獨立驗證指令。
 *
 * dynamic-api 接入時，配置的後端測試在 VM 上根本不可能通過（正式設定寫死 Windows 的 D:/tmp），
 * 卻要等到 #1001 跑了 36 分鐘、走到系統獨立驗證才發現。這裡在專案接入或部署新配置後就先跑一次，
 * 不通過就警告管理者，並把結果當成系統事實告訴之後的任務。只警告、不阻擋：修好基準問題的任務本身也要能跑。
 *
 * 執行環境與任務的獨立驗證相同（全新 checkout、釘住版控檔、systemd 沙箱、專用快取、共用總時限），
 * 差別是失敗後繼續跑其餘指令，好讓管理者一次看到全貌。結果以 JSON 存在 baseline-verification/，
 * 不寫資料庫、不改 FlowRun，JAR 回滾不受影響。
 *
 * 結果以「驗證鍵」加基準 commit 索引，而不是整份配置的 revision：只改提示詞、角色或流程時
 * 基準結果不會變，不需要再跑一輪 20～40 分鐘的全套驗證。驗證鍵見 {@link #verificationKey}。
 */
@Component
public class BaselineVerifier {
    /** systemd unit 與紀錄目錄用的編號；任務從 1001 起，0 不會撞號，TaskUnits.stop("0") 也能停掉它。 */
    public static final String ID = "0";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum Outcome { PASS, FAIL, ENVIRONMENT, TIMEOUT, SKIPPED, ERROR }

    public record Result(List<String> argv, Outcome outcome, Integer exitCode, String log, String summary) {}

    /**
     * error 非 null 表示沒能開始跑指令（例如沒有配置驗證指令、取不到基準分支）。
     * configuration 是執行當下的配置 revision，只供顯示；verificationKey 才是判斷要不要重跑的依據（舊結果沒有這個欄位）。
     */
    public record Report(String configuration, String branch, String sha, String startedAt, String completedAt,
                         List<Result> results, String error, String verificationKey) {
        public Report(String configuration, String branch, String sha, String startedAt, String completedAt,
                      List<Result> results, String error) {
            this(configuration, branch, sha, startedAt, completedAt, results, error, null);
        }
        public boolean passed() { return error == null && results != null && results.stream().allMatch(r -> r.outcome() == Outcome.PASS); }
        public List<Result> problems() {
            return results == null ? List.of() : results.stream().filter(r -> r.outcome() != Outcome.PASS).toList();
        }
    }

    private final AiTeamProperties props;
    private final TeamCatalog catalog;
    private final WorkspaceManager workspaces;
    private final IndependentVerifier verifier;
    private final ExecutionSandbox sandbox;

    public BaselineVerifier(AiTeamProperties props, TeamCatalog catalog, WorkspaceManager workspaces,
                            IndependentVerifier verifier, ExecutionSandbox sandbox) {
        this.props = props; this.catalog = catalog; this.workspaces = workspaces; this.verifier = verifier; this.sandbox = sandbox;
    }

    public boolean enabled() { return catalog.enabled(); }

    /** 目前配置版本與基準分支最新 commit；取不到時拋例外，由呼叫端決定要不要排程。 */
    public WorkspaceManager.BaseRef currentBase() throws Exception {
        var snapshot = catalog.snapshot();
        return workspaces.baseRef(Path.of(snapshot.repository()), snapshot.definition().project().baseBranch());
    }

    /** 目前驗證鍵與基準 commit 是否已有結果；啟動時用來決定要不要再跑。 */
    public boolean alreadyVerified(String sha) {
        return sha != null && Files.isRegularFile(file(currentKey(), sha));
    }

    /**
     * 會改變基準結果的輸入：驗證指令與總時限、自動續驗輪數、指令用到的工具（解析後的實際檔案、大小與修改時間）。
     * 提示詞、角色、流程節點、權限等不在內，改了也不重跑。
     */
    public static String verificationKey(TeamDefinition definition, String toolchain) {
        var spec = definition.project().verification();
        var autonomy = definition.project().autonomy();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("commands", spec == null ? null : spec.commands());
        input.put("timeoutSeconds", spec == null ? null : spec.timeoutSeconds());
        input.put("verificationContinuations", autonomy == null ? 0 : autonomy.verificationContinuations());
        input.put("toolchain", toolchain);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(MAPPER.writeValueAsBytes(input)));
        } catch (Exception e) { throw new IllegalStateException("無法計算基準驗證鍵", e); }
    }

    /** 驗證指令用到的工具（argv 第一個元素與 git）在 PATH 上解析到的實際檔案；升級工具會改變結果。 */
    static String toolchain(TeamDefinition definition, String path, String javaHome) {
        var spec = definition.project().verification();
        java.util.Set<String> tools = new LinkedHashSet<>(List.of("git"));
        if (spec != null && spec.commands() != null)
            for (List<String> argv : spec.commands()) if (!argv.isEmpty()) tools.add(argv.getFirst());
        StringBuilder out = new StringBuilder();
        for (String tool : tools) out.append(tool).append('=').append(describe(resolve(tool, path))).append('\n');
        out.append("JAVA_HOME=").append(javaHome == null ? "-" : describe(Path.of(javaHome).resolve("bin").resolve("java")));
        return out.toString();
    }

    private static Path resolve(String tool, String path) {
        if (tool.contains("/")) return Path.of(tool);
        if (path != null) for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue;
            Path candidate = Path.of(dir).resolve(tool);
            if (Files.isExecutable(candidate)) return candidate;
        }
        return null;
    }

    private static String describe(Path file) {
        if (file == null) return "MISSING";
        try {
            Path real = file.toRealPath();
            return real + ":" + Files.size(real) + ":" + Files.getLastModifiedTime(real).toMillis();
        } catch (Exception e) { return "MISSING"; }
    }

    private String currentKey() { return key(catalog.snapshot().definition()); }

    private static String key(TeamDefinition definition) {
        return verificationKey(definition, toolchain(definition, System.getenv("PATH"), System.getenv("JAVA_HOME")));
    }

    public Report run() throws Exception {
        var snapshot = catalog.snapshot();
        String revision = snapshot.revision();
        String key = currentKey();
        String started = Instant.now().toString();
        var spec = snapshot.definition().project().verification();
        if (spec == null || spec.commands() == null || spec.commands().isEmpty())
            return save(new Report(revision, null, null, started, Instant.now().toString(), List.of(),
                    "配置沒有 project.verification 驗證指令，不做基準驗證", key));
        WorkspaceManager.BaseRef base;
        try { base = currentBase(); }
        catch (Exception e) {
            return save(new Report(revision, snapshot.definition().project().baseBranch(), null, started, Instant.now().toString(),
                    List.of(), "取不到基準分支的最新 commit：" + e.getMessage(), key));
        }
        sandbox.requireReadyForCodeChanges();
        Path root = props.homePath().resolve("verification-runs").resolve(ID).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path run = Files.createTempDirectory(root, "baseline-");
        Path writable = Files.createDirectory(run.resolve("work"));
        Path home = Files.createDirectory(writable.resolve("home"));
        Path repo = writable.resolve("repo");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(spec.timeoutSeconds());
        var setup = new SandboxPolicy(List.of(writable), List.of(), 2048, 200, 256);
        try {
            verifier.execute(ID, List.of("git", "-c", "core.hooksPath=/dev/null", "init", "--quiet", repo.toString()),
                    writable, home, setup, run.resolve("init.log"), deadline);
            // 主 repo 的基準在 refs/remotes/origin 下，一般 clone 帶不過來；與開工作區相同，直接 fetch 那個 ref。
            verifier.execute(ID, List.of("git", "-c", "core.hooksPath=/dev/null", "fetch", "--quiet", "--no-tags", "--",
                    Path.of(snapshot.repository()).toAbsolutePath().toString(), "refs/remotes/origin/" + base.branch()),
                    repo, home, setup, run.resolve("fetch.log"), deadline);
            verifier.execute(ID, List.of("git", "-c", "core.hooksPath=/dev/null", "checkout", "--quiet", "--detach", base.sha()),
                    repo, home, setup, run.resolve("checkout.log"), deadline);
        } catch (Exception e) {
            return save(new Report(revision, base.branch(), base.sha(), started, Instant.now().toString(), List.of(),
                    "無法準備基準分支的全新 checkout：" + e.getMessage(), key));
        }
        var policy = new SandboxPolicy(List.of(writable, verifier.dependencyCache()), IndependentVerifier.pinsFor(repo), 4096, 200, 256);
        List<Result> results = new ArrayList<>();
        boolean outOfTime = false;
        // project.autonomy 授權時，總時限用完但這一輪有進展，就給剩下的指令一個新的總時限再跑（#1053：1800 秒跑不完五條）。
        var autonomy = snapshot.definition().project().autonomy();
        Map<String, Integer> rounds = new HashMap<>();
        int passedThisRound = 0; String continued = null;
        for (int i = 0; i < spec.commands().size(); i++) {
            List<String> argv = List.copyOf(spec.commands().get(i));
            if (outOfTime) { results.add(new Result(argv, Outcome.SKIPPED, null, null, "總時限已用完，未執行")); continue; }
            Path log = run.resolve("check-" + i + ".log");
            try {
                verifier.execute(ID, argv, repo, home, policy, log, deadline);
                results.add(new Result(argv, Outcome.PASS, 0, log.toString(), continued));
                passedThisRound++; continued = null;
            } catch (IndependentVerifier.VerificationTimeout timeout) {
                int round = AutonomousRecovery.claimVerificationContinuation(autonomy, rounds, "baseline", 0, passedThisRound);
                if (round > 0) {
                    deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(spec.timeoutSeconds());
                    passedThisRound = 0; i--;
                    continued = "前一輪用完總時限，依 project.autonomy 自動續驗第 " + round + " 輪後通過";
                    continue;
                }
                outOfTime = true;
                results.add(new Result(argv, Outcome.TIMEOUT, null, log.toString(), "超過 verification.timeoutSeconds，執行中被中止"));
            } catch (IndependentVerifier.CommandExit exit) {
                var classified = IndependentVerifier.classify(exit);
                results.add(classified instanceof IndependentVerifier.CheckFailed failed
                        ? new Result(argv, Outcome.FAIL, exit.exitCode, log.toString(), failed.summary())
                        : new Result(argv, Outcome.ENVIRONMENT, exit.exitCode, log.toString(), tail(log)));
            } catch (Exception e) {
                results.add(new Result(argv, Outcome.ERROR, null, log.toString(), e.getMessage()));
            }
        }
        return save(new Report(revision, base.branch(), base.sha(), started, Instant.now().toString(), List.copyOf(results), null, key));
    }

    /** 目前驗證鍵最新的一份結果。 */
    public Optional<Report> latest() {
        return latest(currentKey());
    }

    Optional<Report> latest(String key) {
        Path dir = directory();
        if (key == null || !Files.isDirectory(dir)) return Optional.empty();
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith(key + "-") && p.toString().endsWith(".json"))
                    .map(BaselineVerifier::read).flatMap(Optional::stream)
                    .max(Comparator.comparing(r -> r.completedAt() == null ? "" : r.completedAt()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 給 agent 的系統事實：任務快照的驗證指令最新的基準驗證有失敗時才有內容。
     * 讓 PM 從需求階段就知道「這些指令在還沒改任何東西時就過不了」，而不是等系統獨立驗證才發現。
     */
    public String notice(TeamDefinition definition) {
        return latest(key(definition)).filter(r -> !r.passed()).map(BaselineVerifier::agentNotice).orElse("");
    }

    static String agentNotice(Report report) {
        StringBuilder out = new StringBuilder("\n基準驗證結果（系統事實，非建議）：");
        if (report.error() != null) return out.append("基準驗證沒能執行：").append(report.error()).toString();
        out.append("基準分支 ").append(report.branch()).append(" 的 commit ").append(shortSha(report.sha()))
           .append(" 在還沒有任何修改時執行配置的驗證指令，結果如下：");
        for (Result r : report.problems())
            out.append("\n- ").append(String.join(" ", r.argv())).append("：").append(label(r.outcome()))
               .append(r.summary() == null ? "" : "；" + com.zipe.aiteam.runtime.Diagnostics.clip(r.summary(), 600));
        return out.append("\n這是既有問題，不是本任務造成的。本任務若不處理它，系統獨立驗證仍會在同一個指令失敗；")
                .append("需求階段就要說明是否納入範圍，必要時請需求負責人決定，不要等到驗證失敗才回報。").toString();
    }

    /** 給管理者看的 Slack 摘要。 */
    public static String slackSummary(Report report) {
        StringBuilder out = new StringBuilder();
        if (report.error() != null)
            return out.append("⚠️ 基準驗證沒能執行：").append(report.error()).append("\n配置版本 ").append(shortSha(report.configuration())).toString();
        out.append(report.passed() ? "✅ 基準驗證通過：" : "⚠️ 基準驗證未通過：")
           .append("基準分支 ").append(report.branch()).append(" 的 commit ").append(shortSha(report.sha()))
           .append("（配置版本 ").append(shortSha(report.configuration())).append("）");
        for (Result r : report.results())
            out.append("\n").append(r.outcome() == Outcome.PASS ? "・✅ " : "・❌ ").append(String.join(" ", r.argv()))
               .append("：").append(label(r.outcome())).append(r.exitCode() == null || r.exitCode() == 0 ? "" : "（結束碼 " + r.exitCode() + "）")
               .append(r.outcome() == Outcome.PASS || r.summary() == null ? "" : "\n　" + com.zipe.aiteam.runtime.Diagnostics.clip(r.summary(), 500).replace("\n", "\n　"))
               .append(r.log() == null || r.outcome() == Outcome.PASS ? "" : "\n　紀錄：" + r.log());
        if (!report.passed())
            out.append("\n\n這些指令在還沒有任何修改的基準分支上就沒有通過，任務的系統獨立驗證會在同樣的地方失敗。")
               .append("請管理者修正配置或環境，或另開任務修正 repo；修好後可送「基準驗證」重新確認。")
               .append("這不會阻擋任務，但之後的任務都會被告知這些已知失敗。");
        return out.toString();
    }

    private static String label(Outcome outcome) {
        return switch (outcome) {
            case PASS -> "通過";
            case FAIL -> "程式或測試失敗";
            case ENVIRONMENT -> "環境缺件（相依套件、磁碟或指令）";
            case TIMEOUT -> "逾時";
            case SKIPPED -> "未執行";
            case ERROR -> "執行錯誤";
        };
    }

    private static String shortSha(String value) {
        return value == null ? "未知" : value.substring(0, Math.min(12, value.length()));
    }

    private static String tail(Path log) {
        try { return IndependentVerifier.summarize(Files.readString(log, StandardCharsets.UTF_8)); }
        catch (Exception e) { return null; }
    }

    private Report save(Report report) throws Exception {
        Files.createDirectories(directory());
        Files.writeString(file(report.verificationKey(), report.sha()), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        return report;
    }

    private Path directory() {
        return props.homePath().resolve("baseline-verification").toAbsolutePath().normalize();
    }

    private Path file(String key, String sha) {
        if (key == null || !key.matches("[0-9a-f]{16,128}")) throw new IllegalArgumentException("無效驗證鍵");
        String commit = sha == null ? "none" : sha;
        if (!commit.matches("none|[0-9a-f]{40,64}")) throw new IllegalArgumentException("無效 commit");
        return directory().resolve(key + "-" + commit + ".json");
    }

    private static Optional<Report> read(Path path) {
        try { return Optional.of(MAPPER.readValue(Files.readString(path, StandardCharsets.UTF_8), Report.class)); }
        catch (Exception e) { return Optional.empty(); }
    }
}
