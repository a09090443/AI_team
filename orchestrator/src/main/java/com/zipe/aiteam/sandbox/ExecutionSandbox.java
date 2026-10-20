package com.zipe.aiteam.sandbox;

import com.zipe.aiteam.config.AiTeamProperties;
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

/**
 * 執行沙箱：把一條指令包成受限的 systemd user unit。
 *
 * 為什麼邊界在這裡而不在 runtime：若安全依賴各 CLI 的權限旗標，
 * 系統安全性等於所有 runtime 中最弱的那一個（見 docs-site/docs/multi-runtime.md）。
 * ClaudeCodeRuntime 的 --allowedTools 仍然保留，但那是縱深防禦的第二層。
 *
 * 實作機制（皆於本機 VM 實測）：
 *   ProtectSystem=strict + ReadWritePaths  檔案系統唯讀，白名單才可寫
 *   ProtectHome=read-only                  家目錄可讀不可寫
 *   PrivateTmp=yes                         獨立 /tmp
 *   MemoryMax / CPUQuota / TasksMax         cgroup v2 資源上限
 *
 * 致命細節：前三項需要 unprivileged user namespace。若核心以 AppArmor 擋掉
 * （Ubuntu 24.04 起預設 kernel.apparmor_restrict_unprivileged_userns=1），
 * **systemd 不會報錯，只會靜默略過這些指令** —— 設定看起來有加，實際零防護。
 * 這就是 selfTest() 存在的唯一理由：把靜默失敗變成大聲失敗。
 */
@Component
public class ExecutionSandbox {

    private static final Logger log = LoggerFactory.getLogger(ExecutionSandbox.class);

    /**
     * 唯一會被帶進沙箱的環境變數。
     *
     * 這裡是白名單而不是黑名單，理由是 orchestrator 自己的環境裡有 Slack token
     * （start.sh 以 set -a source env.sh）。整包倒進去等於把「以 bot 身分發言」
     * 的能力交給一個會改程式碼的 agent。
     *
     * systemd user unit 不繼承呼叫端的環境，只有 HOME 與 LANG 由 user manager 提供；
     * 少了 PATH 與 JAVA_HOME，沙箱裡就找不到 mvn 與 java，而失敗會表現成
     * 「環境有問題」而不是「沙箱沒帶環境變數」。
     *
     * GRADLE_USER_HOME：Gradle 啟動時要寫整個 user home（native、daemon 登錄），只開放
     * ~/.gradle/caches 會以「Could not initialize native services」失敗；改指向已核准的
     * 快取路徑（例如 ~/.cache/gradle）並在團隊配置 cachePaths 宣告。系統獨立驗證另以 env -i
     * 與私有 HOME 執行，不受這個變數影響。
     */
    private static final List<String> ENV_PASSTHROUGH =
            List.of("PATH", "JAVA_HOME", "MAVEN_OPTS", "GRADLE_USER_HOME");

    /** 資源上限一律生效（不需 userns）；檔案系統隔離要等自檢通過才敢宣稱有效。 */
    private volatile boolean fsConfinementVerified = false;
    private volatile String fsConfinementFailure = "尚未自檢";

    /** 沙箱內是否找得到 mvn。找不到的話 Backend 一定失敗，不如提早拒絕。 */
    private volatile boolean toolchainVerified = false;
    private volatile String toolchainFailure = "尚未自檢";

    private final AiTeamProperties props;

    public ExecutionSandbox(AiTeamProperties props) {
        this.props = props;
    }

    // ------------------------------------------------------------------ 包裝

    /**
     * 把 command 包成受限執行的指令，回傳值可直接交給 ProcessBuilder。
     *
     * @param unitName  供 systemctl 檢視與終結用的識別名，例如 "ai-team-1002-backend"
     * @param workDir   受限行程的工作目錄。**必須明確指定** —— systemd unit 不會繼承
     *                  呼叫端的 cwd，ProcessBuilder.directory() 只設得到 systemd-run
     *                  自己身上，漏了這個 agent 會安靜地跑在家目錄而不是工作區。
     */
    public List<String> wrap(String unitName, SandboxPolicy policy, Path workDir, List<String> command) {
        List<String> cmd = new ArrayList<>(List.of(
                "systemd-run", "--user", "--wait", "--pipe", "--quiet", "--collect",
                "--unit=" + unitName,
                "--working-directory=" + workDir.toAbsolutePath()));

        // ---- 環境 ----
        for (String name : ENV_PASSTHROUGH) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                cmd.add("--setenv=" + name + "=" + value);
            }
        }

        // ---- 檔案系統 ----
        cmd.add("-p"); cmd.add("ProtectSystem=strict");
        cmd.add("-p"); cmd.add("ProtectHome=read-only");
        cmd.add("-p"); cmd.add("PrivateTmp=yes");
        // 路徑一律加 "-" 前綴：systemd 對不存在的 ReadWritePaths／ReadOnlyPaths
        // 會讓整個 unit 起不來，而且什麼都不印 —— 又是一次靜默失敗。
        // 缺少的可寫路徑另外記警告，因為那代表 agent 等一下會寫不進去。
        for (Path p : policy.writablePaths()) {
            if (!Files.exists(p)) {
                log.warn("可寫路徑不存在，沙箱內將無法寫入：{}", p);
            }
            cmd.add("-p"); cmd.add("ReadWritePaths=" + pathProperty(p.toAbsolutePath().toString()));
        }
        // 釘回唯讀的例外必須在可寫路徑之後宣告；systemd 會依路徑深度套用，深者勝出。
        for (Path p : policy.readOnlyPins()) {
            cmd.add("-p"); cmd.add("ReadOnlyPaths=" + pathProperty(p.toAbsolutePath().toString()));
        }

        cmd.add("-p"); cmd.add("KillMode=control-group");
        cmd.add("-p"); cmd.add("TimeoutStopSec=3s");
        cmd.add("-p"); cmd.add("SendSIGKILL=yes");

        // ---- 資源 ----
        // MemorySwapMax=0：不准用 swap 撐過上限，否則 VM 會先被 swap 拖垮才觸發 OOM。
        cmd.add("-p"); cmd.add("MemoryMax=" + policy.memoryMaxMb() + "M");
        cmd.add("-p"); cmd.add("MemorySwapMax=0");
        cmd.add("-p"); cmd.add("CPUQuota=" + policy.cpuQuotaPct() + "%");
        cmd.add("-p"); cmd.add("TasksMax=" + policy.tasksMax());

        cmd.add("--");
        cmd.addAll(command);
        return cmd;
    }

    /**
     * systemd 的路徑清單以空白分隔：未加引號的「General rule.pdf」會被拆成兩段，整個 unit 以
     * 「Invalid ReadOnlyPaths」起不來（bestpartner #1012 的獨立驗證就是這樣失敗）。含空白時整段加雙引號（VM 實測確實唯讀）；
     * `\x20` 這類跳脫雖然不報錯，實測檔案**仍可寫入**，等於保護靜默失效，所以不用。
     * 雙引號、反斜線與控制字元無法安全表達，直接拒絕，不讓保護靜默失效。
     */
    static String pathProperty(String absolutePath) {
        String value = "-" + absolutePath;
        if (value.chars().anyMatch(c -> c == '"' || c == '\\' || Character.isISOControl(c)))
            throw new IllegalStateException("沙箱路徑含無法安全交給 systemd 的字元（雙引號、反斜線或控制字元），拒絕執行：" + absolutePath);
        return value.chars().anyMatch(Character::isWhitespace) ? "\"" + value + "\"" : value;
    }

    /**
     * 寫程式碼的階段執行前必須先問過這裡。
     *
     * 檢查兩件事，因為兩者都會讓 Backend 白跑一輪：
     * 隔離沒生效是安全問題，工具鏈找不到是能力問題。
     */
    public void requireReadyForCodeChanges() throws SandboxUnavailableException {
        if (!fsConfinementVerified) {
            throw new SandboxUnavailableException(
                    "檔案系統隔離未通過自檢，拒絕執行會修改程式碼的角色。原因：" + fsConfinementFailure);
        }
        if (!toolchainVerified) {
            throw new SandboxUnavailableException(
                    "沙箱內找不到必要工具，拒絕執行。原因：" + toolchainFailure);
        }
    }

    public boolean fsConfinementVerified() { return fsConfinementVerified; }
    public String  fsConfinementFailure()  { return fsConfinementFailure; }
    public boolean toolchainVerified()     { return toolchainVerified; }
    public String  toolchainFailure()      { return toolchainFailure; }

    // ------------------------------------------------------------------ 自檢

    /**
     * 啟動自檢：用真的越界寫入來驗證沙箱，而不是相信設定檔。
     *
     * 三個對照都必須成立才算通過 ——
     *   canary 在可寫白名單之外，必須寫不進去（沙箱有效）
     *   probe  在白名單之內，必須寫得進去（沒有嚴到讓 agent 無法工作）
     *   cwd    必須落在指定的工作目錄（--working-directory 真的有帶到）
     * 只驗第一項的話，「整個檔案系統都不可寫」這種壞掉的設定也會被判定為通過。
     */
    public void selfTest() {
        Path root = props.logsPath().resolve("sandbox-selftest");
        Path canary = root.resolve("canary.txt");
        Path rw = root.resolve("rw");
        Path probe = rw.resolve("probe.txt");
        Path cwdReport = rw.resolve("cwd.txt");
        Path toolReport = rw.resolve("tools.txt");

        try {
            deleteRecursively(root);
            Files.createDirectories(rw);
            Files.writeString(canary, "original", StandardCharsets.UTF_8);

            // 刻意放一個不存在的釘選路徑：驗證 "-" 前綴有效。
            // 少了它，乾淨環境下（例如還沒有 ~/.claude/settings.json）unit 會直接起不來。
            SandboxPolicy policy = new SandboxPolicy(
                    List.of(rw), List.of(root.resolve("no-such-pin")), 256, 100, 32);
            List<String> cmd = wrap(props.unitPrefix() + "selftest-" + System.currentTimeMillis(), policy, rw,
                    List.of("bash", "-c",
                            "echo tampered > '" + canary + "' 2>/dev/null;"
                          + "echo probed   > '" + probe  + "' 2>/dev/null;"
                          + "pwd          > '" + cwdReport + "' 2>/dev/null;"
                          + "{ for tool in " + String.join(" ", props.requiredBuildTools())
                          + "; do command -v \"$tool\" || echo MISSING:$tool; done; } > '" + toolReport + "' 2>/dev/null; exit 0"));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                fail("自檢逾時，systemd-run 沒有回應");
                return;
            }

            boolean canaryHeld = "original".equals(
                    Files.readString(canary, StandardCharsets.UTF_8).trim());
            boolean probeWritten = Files.exists(probe);
            String cwd = Files.exists(cwdReport)
                    ? Files.readString(cwdReport, StandardCharsets.UTF_8).trim() : "(未寫出)";

            if (!canaryHeld) {
                fail("沙箱外的檔案被寫入成功 —— ProtectSystem/ReadWritePaths 未生效。"
                   + "多半是 unprivileged user namespace 被 AppArmor 擋住："
                   + "請以 sudo 設定 kernel.apparmor_restrict_unprivileged_userns=0 後重啟服務。");
            } else if (!probeWritten) {
                fail("白名單內的路徑也寫不進去 —— 沙箱設定過嚴，agent 無法工作。檢查 ReadWritePaths=" + rw);
            } else if (!cwd.equals(rw.toAbsolutePath().toString())) {
                fail("工作目錄沒有帶到：預期 " + rw.toAbsolutePath() + "，實際 " + cwd
                   + " —— agent 會跑在錯誤的目錄。");
            } else {
                fsConfinementVerified = true;
                fsConfinementFailure = null;
                log.info("沙箱自檢通過：越界寫入被擋、白名單內可寫、工作目錄正確。");
            }

            // 工具鏈與隔離分開判斷：systemd user unit 不繼承呼叫端環境，
            // 少了 PATH／JAVA_HOME 沙箱裡就找不到 mvn，Backend 會白跑一輪才失敗。
            String toolPaths = Files.exists(toolReport)
                    ? Files.readString(toolReport, StandardCharsets.UTF_8).trim() : "";
            if (toolPaths.isEmpty() || toolPaths.contains("MISSING:")) {
                toolchainVerified = false;
                toolchainFailure = "必要工具=" + props.requiredBuildTools() + "，檢查结果=" + toolPaths
                        + "。確認 PATH 已包含對應工具，且在 ENV_PASSTHROUGH 白名單內。";
                log.error("沙箱工具鏈自檢未通過：{}", toolchainFailure);
            } else {
                toolchainVerified = true;
                toolchainFailure = null;
                log.info("沙箱工具鏈自檢通過：{}，位置 {}", props.requiredBuildTools(), toolPaths);
            }

            if (fsConfinementVerified && toolchainVerified) {
                log.info("會修改程式碼的角色已解鎖。");
            }
        } catch (Exception e) {
            fail("自檢本身執行失敗：" + e);
        } finally {
            try { deleteRecursively(root); } catch (IOException ignored) { }
        }
    }

    private void fail(String reason) {
        fsConfinementVerified = false;
        fsConfinementFailure = reason;
        log.error("沙箱自檢未通過。Backend／QA 將被拒絕執行。原因：{}", reason);
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(f -> {
                try { Files.deleteIfExists(f); } catch (IOException ignored) { }
            });
        }
    }

    public static class SandboxUnavailableException extends Exception {
        public SandboxUnavailableException(String message) { super(message); }
    }
}
