package com.zipe.aiteam.runtime;

import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.sandbox.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真實雙層沙箱；不呼叫模型、不使用正式任務工作區。 */
@EnabledIfEnvironmentVariable(named="AI_TEAM_CODEX_SANDBOX_SMOKE", matches="true")
class CodexSandboxVmTest {
    Path tmp;
    ExecutionSandbox sandbox = new ExecutionSandbox(null);
    Path bin = Path.of(System.getenv().getOrDefault("AI_TEAM_CODEX_BIN", "codex"));
    @BeforeEach void init() throws Exception {
        Path root = Path.of("target/codex-sandbox-smoke").toAbsolutePath(); Files.createDirectories(root);
        tmp = Files.createTempDirectory(root, "run-");
        Files.createDirectory(tmp.resolve(".git"));
        Files.createDirectory(tmp.resolve("target"));
        Files.createDirectories(tmp.resolve("src/main"));
        Files.createDirectories(tmp.resolve("src/test"));
    }
    SandboxPolicy policy() { return new SandboxPolicy(List.of(tmp.resolve("target"), tmp.resolve("src/test")), List.of(), 512, 100, 128); }
    int probe(String script) throws Exception {
        Path output = tmp.resolve("probe.log");
        Process p = new ProcessBuilder(sandbox.wrap("ai-team-nested-test-" + UUID.randomUUID(), policy(), tmp,
                List.of(bin.toString(), "sandbox", "-P", ":workspace", "-C", tmp.toString(), "--", "/bin/sh", "-ec", script)))
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try { p.getOutputStream().close(); assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue(); return p.exitValue(); }
        finally { if (p.isAlive()) p.destroyForcibly(); }
    }
    @Test void reproducesMissingMountThenAllowsTestsWithoutRelaxingReadonlyBoundary() throws Exception {
        assertThat(probe("pwd")).isNotZero();
        assertThat(Files.readString(tmp.resolve("probe.log"))).contains("bwrap:", ".agents", "Read-only file system");
        CodexSandboxPreparation.prepare(tmp);
        assertThat(probe("touch target/probe src/test/probe; "
                + "if touch src/main/forbidden .agents/forbidden .codex/forbidden .git/forbidden root-forbidden 2>/dev/null; then exit 3; fi; "
                + "test ! -e src/main/forbidden; test ! -e .agents/forbidden; test ! -e .codex/forbidden; test ! -e .git/forbidden; test ! -e root-forbidden")).isZero();
        assertThat(tmp.resolve("target/probe")).exists();
        assertThat(tmp.resolve("src/test/probe")).exists();
    }
    /**
     * 受保護路徑改派（2026-10-03）：Claude Code 寫不了 `.claude/`，改派 Codex 實作前先確認在實作節點的外層政策
     * （整個工作區可寫、釘住 .git/config 與 .git/hooks）下，Codex 內層沙箱可以寫 `.claude/rules/`，但 `.git` 仍不可寫
     * （所以改派的實作由系統核對後代為提交）。
     */
    @Test void workspaceImplementerCanWriteClaudeRulesButNotGit() throws Exception {
        Files.createDirectories(tmp.resolve(".git/hooks")); Files.writeString(tmp.resolve(".git/config"), "");
        Files.createDirectories(tmp.resolve(".claude/rules"));
        CodexSandboxPreparation.prepare(tmp);
        var workspacePolicy = new SandboxPolicy(List.of(tmp), List.of(tmp.resolve(".git/config"), tmp.resolve(".git/hooks")), 512, 100, 128);
        Path output = tmp.resolve("claude-probe.log");
        Process p = new ProcessBuilder(sandbox.wrap("ai-team-claude-path-test-" + UUID.randomUUID(), workspacePolicy, tmp,
                List.of(bin.toString(), "sandbox", "-P", ":workspace", "-C", tmp.toString(), "--", "/bin/sh", "-ec",
                        "echo changed > .claude/rules/api.md; mkdir -p .claude/skills/x; echo s > .claude/skills/x/SKILL.md; "
                        + "if touch .git/forbidden .git/hooks/forbidden 2>/dev/null; then exit 3; fi; "
                        + "test ! -e .git/forbidden; test ! -e .git/hooks/forbidden; echo claude-rules-ok")))
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try { p.getOutputStream().close(); assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue(); }
        finally { if (p.isAlive()) p.destroyForcibly(); }
        assertThat(Files.readString(output)).as(Files.readString(output)).contains("claude-rules-ok");
        assertThat(p.exitValue()).isZero();
        assertThat(tmp.resolve(".claude/rules/api.md")).hasContent("changed\n");
        assertThat(tmp.resolve(".claude/skills/x/SKILL.md")).exists();
    }
    @Test void runtimePreflightFailsBeforeModelAndPassesAfterPreparation() throws Exception {
        var props = mock(AiTeamProperties.class); when(props.codexBin()).thenReturn(bin); when(props.unitPrefix()).thenReturn("ai-team-");
        var runtime = new CodexRuntime(props, sandbox);
        var req = new AgentRuntime.RuntimeRequest("probe", "qa", tmp, tmp.resolve("role.md"), "unused", List.of(), null,
                null, 1, Duration.ofSeconds(30), policy(), "gpt-5.6-sol");
        assertThatThrownBy(() -> runtime.checkSandbox(req, tmp.resolve("runtime-probe.log")))
                .hasMessageContaining("尚未呼叫模型").hasMessageContaining("Read-only file system");
        CodexSandboxPreparation.prepare(tmp);
        runtime.checkSandbox(req, tmp.resolve("runtime-probe.log"));
        String evidence = Files.readString(tmp.resolve("runtime-probe.log"));
        assertThat(evidence).contains(tmp.toString());
    }
    @Test void localNetworkProfileAllowsOnlyIsolatedTcpAndDeclaredWrites() throws Exception {
        CodexSandboxPreparation.prepare(tmp);
        Path cache = Files.createDirectory(tmp.resolve("cache"));
        var localPolicy = new SandboxPolicy(List.of(tmp.resolve("target"), cache), List.of(), 512, 100, 128, true);
        var req = new AgentRuntime.RuntimeRequest("probe", "qa", tmp, tmp.resolve("role.md"), "unused", List.of(), null,
                null, 1, Duration.ofSeconds(30), localPolicy, "gpt-5.6-sol");
        List<String> command = new ArrayList<>(List.of(bin.toString(), "sandbox"));
        command.addAll(CodexLocalTestPolicy.sandboxArguments(req, List.of()));
        String network = CodexLocalTestPolicy.TCP_PROBE + ";const r=net.connect({host:'203.0.113.1',port:443});"
                + "r.on('connect',()=>process.exit(40));r.on('error',()=>console.log('direct-denied'));"
                + "r.setTimeout(1500,()=>{console.log('direct-denied');r.destroy()});"
                + "fetch('https://example.com').then(r=>{if(r.ok)process.exit(41);console.log('proxy-denied')})"
                + ".catch(()=>console.log('proxy-denied'))";
        command.addAll(List.of("-C", tmp.toString(), "--", "sh", "-ec",
                "touch target/allowed cache/allowed; if touch src/main/denied .codex/denied root-denied 2>/dev/null; then exit 42; fi; "
                + "test ! -e src/main/denied; test ! -e .codex/denied; test ! -e root-denied; node -e \"$1\"", "probe", network));
        Path output = tmp.resolve("network.log");
        Process p = new ProcessBuilder(sandbox.wrap("ai-team-network-test-" + UUID.randomUUID(), localPolicy, tmp, command))
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try { p.getOutputStream().close(); assertThat(p.waitFor(25, TimeUnit.SECONDS)).isTrue(); assertThat(p.exitValue()).isZero(); }
        finally { if(p.isAlive()) p.destroyForcibly(); }
        assertThat(Files.readString(output)).contains("local-tcp-ok", "direct-denied", "proxy-denied");
    }
    /**
     * 真實 CLI 參數契約，零模型呼叫：exec 與 sandbox 的參數天生不對稱。
     * 之前預檢用 sandbox 通過、派工用 exec 卻以結束碼 2 失敗（#1014）。
     */
    @Test void execAcceptsItsOwnArgumentsAndStillRejectsSandboxOnlyFlag() throws Exception {
        Path plain = Files.createTempDirectory(Path.of("target/codex-sandbox-smoke").toAbsolutePath(), "not-a-repo-");
        var localPolicy = new SandboxPolicy(List.of(plain.resolve("target")), List.of(), 512, 100, 128, true);
        var req = new AgentRuntime.RuntimeRequest("probe", "qa", plain, plain.resolve("role.md"), "unused", List.of(), null,
                null, 1, Duration.ofSeconds(30), localPolicy, "gpt-5.6-sol");

        // 非 git 目錄：codex exec 會停在信任目錄檢查，也就是設定與參數都已經被接受，
        // 但還沒有送出任何模型請求。
        String accepted = cli(plain, exec(CodexLocalTestPolicy.execArguments(req, List.of()), plain));
        assertThat(accepted).doesNotContain("unexpected argument", "unknown configuration field",
                "refers to undefined profile", "does not set `default_permissions`");
        assertThat(accepted).contains("Not inside a trusted directory");

        // resume 會重新解析子指令選項；權限設定必須放在 resume 後。
        // 用不存在的 session 停在本機查找，因此不會發出模型請求。
        String resumed = cli(plain, resume(CodexLocalTestPolicy.execArguments(req, List.of())));
        assertThat(resumed).doesNotContain("unexpected argument", "unknown configuration field",
                "refers to undefined profile", "does not set `default_permissions`");

        // 反向回歸：sandbox 專用旗標交給 exec 必須仍被拒絕，避免又把兩邊參數混用。
        String rejected = cli(plain, exec(CodexLocalTestPolicy.sandboxArguments(req, List.of()), plain));
        assertThat(rejected).contains("unexpected argument");
        assertThat(RuntimeUsageError.looksLikeUsageError(2, "", rejected)).isTrue();
    }
    private List<String> exec(List<String> policyArgs, Path cwd) {
        List<String> cmd = new ArrayList<>(List.of(bin.toString(), "exec", "--strict-config"));
        cmd.addAll(policyArgs);
        cmd.addAll(List.of("-C", cwd.toString(), "contract-probe"));
        return cmd;
    }
    private List<String> resume(List<String> policyArgs) {
        List<String> cmd = new ArrayList<>(List.of(bin.toString(), "exec", "resume", "--strict-config"));
        cmd.addAll(policyArgs);
        cmd.addAll(List.of("00000000-0000-4000-8000-000000000000", "contract-probe"));
        return cmd;
    }
    private String cli(Path cwd, List<String> command) throws Exception {
        Path output = cwd.resolve("cli-" + UUID.randomUUID() + ".log");
        Process p = new ProcessBuilder(command).directory(cwd.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            p.getOutputStream().close();
            assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(p.exitValue()).isNotZero();
        } finally { if (p.isAlive()) p.destroyForcibly(); }
        return Files.readString(output);
    }
}
