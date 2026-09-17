package com.zipe.aiteam.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.sandbox.ExecutionSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Claude Code 的 AgentRuntime 實作。
 *
 * 所有旗標用法皆已於 Phase 0 實機驗證（見 docs/phase0-report.md，13 項通過）：
 *   --append-system-prompt-file  附加而非取代系統提示
 *   --permission-mode dontAsk    headless 下不會卡在權限提示
 *   --json-schema                回傳已驗證的 structured_output
 *   --resume                     保有前一輪 context
 *   --output-format json         stdout 為乾淨 JSON（stderr 另外導向檔案）
 */
@Component
public class ClaudeCodeRuntime implements AgentRuntime {
    @Override public boolean permitsCommand(List<String> tools, List<String> argv) {
        if (tools == null || argv == null || argv.isEmpty()) return false;
        String command = String.join(" ", argv);
        return tools.stream().anyMatch(rule -> {
            if (rule.equals("Bash(" + command + ")")) return true;
            if (!rule.startsWith("Bash(") || !rule.endsWith(" *)")) return false;
            String prefix = rule.substring(5, rule.length() - 3);
            return command.startsWith(prefix + " ");
        });
    }

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeRuntime.class);

    private final AiTeamProperties props;
    private final ExecutionSandbox sandbox;
    private final ObjectMapper mapper = new ObjectMapper();

    public ClaudeCodeRuntime(AiTeamProperties props, ExecutionSandbox sandbox) {
        this.props = props;
        this.sandbox = sandbox;
    }

    @Override
    public String id() {
        return "claude-code";
    }

    /** claude 的 session 記錄、憑證更新與外掛快取都在這裡。 */
    @Override
    public List<Path> statePaths(Path home) {
        return List.of(home.resolve(".claude"), home.resolve(".claude.json"));
    }

    /** settings.json 可定義 hook、plugins 可塞進會被載入的程式碼，兩者釘回唯讀。 */
    @Override
    public List<Path> escalationPins(Path home) {
        return List.of(
                home.resolve(".claude/settings.json"),
                home.resolve(".claude/plugins"));
    }

    @Override
    public Set<Capability> capabilities() {
        return EnumSet.of(
                Capability.APPEND_SYSTEM_PROMPT,
                Capability.STRUCTURED_OUTPUT,
                Capability.SESSION_RESUME,
                Capability.MAX_TURNS,
                Capability.TOOL_PERMISSIONS,
                Capability.COST_REPORTING,
                Capability.MODEL_SELECTION);
    }

    @Override
    public RuntimeResult execute(RuntimeRequest req) throws RuntimeExecutionException {
        return execute(req, session -> {});
    }

    @Override
    public RuntimeResult execute(RuntimeRequest req, java.util.function.Consumer<String> sessionStarted)
            throws RuntimeExecutionException {
        // 安全邊界在 runtime 之外：這裡交出指令，由 ExecutionSandbox 決定它能碰什麼。
        // 加上時間戳避免與殘留的同名 unit 相撞（重做時 task+role 會重複）。
        String unit = "ai-team-" + req.taskId() + "-" + req.role()
                + "-" + (System.currentTimeMillis() / 1000);
        List<String> cli = buildCommand(req);
        String session = req.resumeSessionId();
        if (session == null || session.isBlank()) {
            session = java.util.UUID.randomUUID().toString();
            cli.add("--session-id");
            cli.add(session);
        }
        sessionStarted.accept(session);
        List<String> cmd = sandbox.wrap(unit, req.sandboxPolicy(), req.workingDirectory(), cli);
        Path logDir = props.logsPath().resolve(req.taskId());
        Path stderrLog = logDir.resolve(req.role() + ".stderr.log");
        // stdout 也要落檔。失敗時把它塞進例外訊息會被截斷，
        // 而被截掉的往往正是 subtype 與 num_turns 這些說明「為什麼失敗」的欄位。
        Path stdoutLog = logDir.resolve(req.role() + ".stdout.json");

        Process process = null;
        try {
            Files.createDirectories(stderrLog.getParent());
            SessionCapture.preserve(stdoutLog);
            SessionCapture.preserve(stderrLog);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            // 不設 pb.directory()：那只會設到 systemd-run 自己身上。
            // 受限行程的 cwd 由 ExecutionSandbox 的 --working-directory 帶入。

            // stdout、stderr 分開落檔，完成或逾時都保留證據。
            pb.redirectError(ProcessBuilder.Redirect.appendTo(stderrLog.toFile()));
            // 即時保留 stdout，逾時也有部分事件可追查，且不會卡住 pipe。
            pb.redirectOutput(stdoutLog.toFile());

            // 訂閱帳號認證：不可設 ANTHROPIC_API_KEY，讓 claude 讀既有 OAuth 憑證。
            pb.environment().remove("ANTHROPIC_API_KEY");

            log.info("[{}] 執行 {} ：模型 {}，{} 個參數，逾時 {}",
                    req.taskId(), req.role(),
                    req.model() == null || req.model().isBlank() ? "(CLI 預設)" : req.model(),
                    cmd.size(), req.timeout());

            process = pb.start();

            // 提示從 stdin 送入，寫完即關閉讓 claude 開始處理。
            // 放在 argv 會受 Linux 單一參數 131072 位元組上限限制：#1016 分流節點的提示 131772 位元組，
            // systemd-run 直接以 Argument list too long 拒絕，任何重試都一樣失敗。
            // 已實測 Claude Code 2.1.252：441KB 提示經 systemd-run --pipe 由 stdin 送入可正常讀取。
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(req.prompt().getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }

            if (!process.waitFor(req.timeout().toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                QuotaExceeded.check(id(), Files.readString(stdoutLog, StandardCharsets.UTF_8), stdoutLog.toString());
                throw new RuntimeExecutionException(
                        "Claude Code 超過設定逾時 " + req.timeout() + "，已送出強制終止。"
                        + "耗時根因與 commit 狀態尚未確認。stdout：" + stdoutLog + "；stderr：" + stderrLog);
            }

            String stdout = Files.readString(stdoutLog, StandardCharsets.UTF_8);
            int exit = process.exitValue();
            QuotaExceeded.check(id(), stdout, stdoutLog.toString());

            if (exit != 0) {
                throw new RuntimeExecutionException(describeFailure(exit, stdout, stdoutLog));
            }
            return parse(stdout, stderrLog);

        } catch (RuntimeExecutionException e) {
            throw e;
        } catch (InterruptedException e) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new RuntimeExecutionException("執行被中斷", e);
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            throw new RuntimeExecutionException("無法執行 Claude Code；stdout=" + stdoutLog
                    + "；stderr=" + stderrLog, e);
        }
    }

    /**
     * 以參數陣列建構指令，完全不經 shell。
     * 這消除的是 shell 注入；prompt 注入由權限模式與觸發者白名單處理。
     */
    private List<String> buildCommand(RuntimeRequest req) {
        List<String> cmd = new ArrayList<>();
        cmd.add(props.claudeBin().toString());
        // 提示不放 argv，由 execute 經 stdin 送入（見該處說明）。
        cmd.add("-p");

        cmd.add("--append-system-prompt-file");
        cmd.add(req.systemPromptFile().toString());

        cmd.add("--output-format");
        cmd.add("json");

        cmd.add("--permission-mode");
        cmd.add("dontAsk");

        if (req.allowedTools() != null && !req.allowedTools().isEmpty()) {
            cmd.add("--allowedTools");
            cmd.add(String.join(",", req.allowedTools()));
        }

        cmd.add("--max-turns");
        cmd.add(String.valueOf(req.maxTurns()));

        // 空字串一定要整個略過旗標，不能傳空值 —— 實測傳無效模型名會直接結束碼 1。
        // 這是好事：打錯名稱會大聲失敗，不會安靜退回預設。
        if (req.model() != null && !req.model().isBlank()) {
            cmd.add("--model");
            cmd.add(req.model());
        }

        if (req.jsonSchema() != null && !req.jsonSchema().isBlank()) {
            cmd.add("--json-schema");
            cmd.add(req.jsonSchema());
        }
        if (req.resumeSessionId() != null && !req.resumeSessionId().isBlank()) {
            cmd.add("--resume");
            cmd.add(req.resumeSessionId());
        }
        return cmd;
    }

    /**
     * 把非零結束碼翻成人看得懂的原因。
     *
     * Claude Code 在回合數用盡時也是結束碼 1（`subtype: error_max_turns`），
     * 與真正的錯誤無法從結束碼區分。這兩者的處置完全不同：
     * 前者要調高上限或縮小任務，後者要看診斷紀錄。
     */
    String describeFailure(int exit, String stdout, Path stdoutLog) {
        String subtype = "";
        int turns = -1;
        String observed = "";
        try {
            JsonNode root = mapper.readTree(stdout);
            subtype = root.path("subtype").asText("");
            turns = root.path("num_turns").asInt(-1);
            observed = "供應商結果：" + Diagnostics.clip(root.path("result").asText(), 1200)
                    + "\n" + Diagnostics.clip(root.path("errors").toString(), 700)
                    + Diagnostics.toolFailures(root.get("permission_denials"));
        } catch (Exception ignored) {
            // 連 JSON 都解不出來，退回原始輸出
        }

        if ("error_max_turns".equals(subtype)) {
            return "已確認：Claude Code 回報 error_max_turns，結束碼 " + exit
                 + "，已用回合數=" + (turns < 0 ? "未提供" : turns) + "。"
                 + "為何耗盡回合尚未確認；是否已 commit 必須另查 Git。"
                 + "\n完整輸出：" + stdoutLog + "\n原始錯誤／工具紀錄：" + observed;
        }
        if (!subtype.isBlank()) {
            return "Claude Code 結束碼 " + exit + "（subtype=" + subtype + "）。完整輸出：" + stdoutLog
                    + "\n原始錯誤／工具紀錄：" + observed;
        }
        return "Claude Code 結束碼 " + exit + "。完整輸出：" + stdoutLog
             + "；開頭：" + truncate(stdout, 500);
    }

    private RuntimeResult parse(String stdout, Path stderrLog) throws RuntimeExecutionException {
        try {
            JsonNode root = mapper.readTree(stdout);
            JsonNode structured = root.get("structured_output");
            Double cost = root.hasNonNull("total_cost_usd")
                    ? root.get("total_cost_usd").asDouble() : null;
            return new RuntimeResult(
                    root.path("session_id").asText(null),
                    root.path("result").asText(""),
                    structured,
                    cost,
                    root.path("is_error").asBoolean(false),
                    stderrLog,
                    modelsOf(root),
                    root.get("permission_denials"));
        } catch (Exception e) {
            throw new RuntimeExecutionException(
                    "無法解析 Claude Code 的 JSON 輸出：" + truncate(stdout, 1500), e);
        }
    }

    /** 從 modelUsage 取出實際用過的模型名稱。 */
    private static String modelsOf(JsonNode root) {
        JsonNode usage = root.get("modelUsage");
        if (usage == null || !usage.isObject()) return "";
        List<String> names = new ArrayList<>();
        usage.fieldNames().forEachRemaining(names::add);
        return String.join(", ", names);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
