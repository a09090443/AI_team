/*
 * 參考實作 — 用來說明 architecture-review.md 的 B2/B3/B4/I1/I2/I3 修正點。
 * 這不是可直接部署的成品：省略了設定注入、指標、重試策略與完整的錯誤分類。
 *
 * 多 runtime 前提下，這個類別應實作 AgentRuntime 介面（見 AgentRuntime.java），
 * 成為眾多 adapter 之一，並宣告自己的 Capabilities：
 *   APPEND_SYSTEM_PROMPT, STRUCTURED_OUTPUT, SESSION_RESUME,
 *   MAX_TURNS, TOOL_PERMISSIONS, COST_REPORTING, WORKSPACE_ISOLATION
 *
 * 注意：下方的 --permission-mode 與 --allowedTools 是 Claude Code 專屬旗標，
 * 屬於縱深防禦的第二層。主要的安全邊界由 Orchestrator 的 ExecutionSandbox
 * 提供（專用 user 或容器、唯讀掛載、網路白名單），因為不是每個 CLI
 * 都有細粒度權限控制 —— 詳見 multi-runtime.md。
 *
 * 對照原文（第 25 節）修正了：
 *   B3  分離 stdout / stderr，不再用 redirectErrorStream(true)
 *   B4  --system-prompt（取代）改為 --append-system-prompt-file（附加）
 *   B2  明確傳入 --permission-mode 與 --allowedTools
 *   I1  用參數陣列建構指令，完全不經 bash -c
 *   I2  以 --json-schema 取得已驗證的結構化輸出
 *   I3  支援 --resume 續接 session
 */
package com.example.aiteam.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ClaudeCodeRunner {

    private final Path claudeBinary;   // 絕對路徑，不依賴 PATH
    private final Path logRoot;
    private final ObjectMapper mapper = new ObjectMapper();

    public ClaudeCodeRunner(Path claudeBinary, Path logRoot) {
        this.claudeBinary = claudeBinary;
        this.logRoot = logRoot;
    }

    public AgentResult run(AgentInvocation inv) throws AgentExecutionException {
        List<String> cmd = buildCommand(inv);
        Path stderrLog = logRoot.resolve(inv.taskId()).resolve(inv.role() + ".stderr.log");

        try {
            Files.createDirectories(stderrLog.getParent());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(inv.workingDirectory().toFile());

            // B3：stderr 導向檔案。這同時解決兩件事 ——
            //  (1) stdout 保持純淨的 JSON，啟動期警告不會污染解析
            //  (2) 避開「兩條 pipe 只讀一條」造成的子行程死鎖
            pb.redirectError(ProcessBuilder.Redirect.appendTo(stderrLog.toFile()));

            // 訂閱帳號認證：不設 ANTHROPIC_API_KEY，讓 claude 讀取既有的 OAuth 憑證。
            // 注意此時不能使用 --bare（--bare 不讀 OAuth 憑證）。
            pb.environment().remove("ANTHROPIC_API_KEY");

            Process process = pb.start();

            // prompt 已作為 argv 元素傳入（見 buildCommand），此處僅關閉 stdin，
            // 否則 claude 會持續等待輸入而不結束。
            // 若 prompt 過大（接近 argv 長度上限），改為此處寫入 stdin 並省略 argv 中的 prompt；
            // 官方對 piped stdin 的上限是 10MB。
            try (Writer stdin = new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8)) {
                // 無內容，直接關閉
            }

            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getInputStream().readAllBytes(),
                            StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });

            boolean finished = process.waitFor(
                    inv.timeout().toSeconds(), TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new AgentExecutionException(
                        "Claude Code 逾時（" + inv.timeout() + "），已強制終止。"
                        + " stderr: " + stderrLog);
            }

            String stdout = stdoutFuture.get(10, TimeUnit.SECONDS);
            int exitCode = process.exitValue();

            if (exitCode != 0) {
                throw new AgentExecutionException(
                        "Claude Code 以 exit code " + exitCode + " 結束。"
                        + " stderr: " + stderrLog
                        + " stdout: " + truncate(stdout, 2000));
            }

            return parse(stdout);

        } catch (AgentExecutionException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentExecutionException("執行被中斷", e);
        } catch (Exception e) {
            throw new AgentExecutionException("無法執行 Claude Code", e);
        }
    }

    /**
     * I1：以參數陣列建構指令。ProcessBuilder(List) 直接把每個元素當成 argv[]，
     * 不做任何 shell 解析，因此 Slack 文字不需要跳脫，也沒有命令注入風險。
     *
     * 注意這消除的是「shell 注入」。Slack 文字作為 prompt 仍是「prompt 注入」面，
     * 那由 --permission-mode、工具白名單與觸發者授權處理。
     */
    private List<String> buildCommand(AgentInvocation inv) {
        List<String> cmd = new ArrayList<>();
        cmd.add(claudeBinary.toString());
        cmd.add("-p");
        cmd.add(inv.prompt());

        // B4：附加而非取代。保留 Claude Code 內建的工具使用與檔案編輯指引。
        cmd.add("--append-system-prompt-file");
        cmd.add(inv.systemPromptFile().toString());

        cmd.add("--output-format");
        cmd.add("json");

        // B2：-p 的內建起始權限模式是 Manual，headless 無人可回應提示。
        // dontAsk 只放行 permissions.allow 規則與唯讀指令集，其餘直接拒絕。
        cmd.add("--permission-mode");
        cmd.add("dontAsk");

        if (!inv.allowedTools().isEmpty()) {
            cmd.add("--allowedTools");
            cmd.add(String.join(",", inv.allowedTools()));
        }

        // I6：訂閱帳號下唯一的單次用量護欄（--max-budget-usd 需要 API key）
        cmd.add("--max-turns");
        cmd.add(String.valueOf(inv.maxTurns()));

        // I2：結構化輸出。Schema 無效時 claude 會直接以錯誤退出，
        // 不會靜默退回非結構化文字。
        if (inv.jsonSchema() != null) {
            cmd.add("--json-schema");
            cmd.add(inv.jsonSchema());
        }

        // I3：續接同一個 Slack thread 的前文
        if (inv.resumeSessionId() != null) {
            cmd.add("--resume");
            cmd.add(inv.resumeSessionId());
        }

        return cmd;
    }

    private AgentResult parse(String stdout) throws AgentExecutionException {
        try {
            JsonNode root = mapper.readTree(stdout);
            return new AgentResult(
                    root.path("session_id").asText(null),
                    root.path("result").asText(""),
                    root.get("structured_output"),          // 未使用 --json-schema 時為 null
                    root.path("total_cost_usd").asDouble(0),
                    root.path("is_error").asBoolean(false)
            );
        } catch (Exception e) {
            throw new AgentExecutionException(
                    "無法解析 Claude Code 的 JSON 輸出：" + truncate(stdout, 2000), e);
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }

    // ---------------------------------------------------------------

    public record AgentInvocation(
            String taskId,
            String role,                 // pm / architecture / backend / qa
            Path workingDirectory,       // Backend 應指向隔離的 worktree
            Path systemPromptFile,
            String prompt,
            List<String> allowedTools,
            String jsonSchema,           // nullable
            String resumeSessionId,      // nullable
            int maxTurns,
            Duration timeout
    ) {}

    public record AgentResult(
            String sessionId,            // 存入 thread_ts ↔ session_id 對應表
            String resultText,
            JsonNode structuredOutput,
            double totalCostUsd,         // 訂閱帳號下為估算值，僅供觀測
            boolean isError
    ) {}

    public static class AgentExecutionException extends Exception {
        public AgentExecutionException(String message) { super(message); }
        public AgentExecutionException(String message, Throwable cause) { super(message, cause); }
    }
}
