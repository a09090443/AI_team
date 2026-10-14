package com.zipe.aiteam.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * OpenAI Codex CLI 的 AgentRuntime 實作。
 *
 * 所有用法皆已於 VM 上實機驗證（7 項通過；現行參數見 docs-site/docs/codex-cli-argument-contract.md）：
 *   codex exec               非互動入口，ChatGPT 訂閱帳號下可用
 *   --json                   JSONL 事件流，thread.started 帶 thread_id
 *   --output-schema FILE     結構化輸出（吃檔案，不吃字串）
 *   codex exec resume ID     保有前一輪 context
 *   AGENTS.md                原生自動載入，不需任何指示
 *
 * 與 Claude Code 的三個缺口，由呼叫端降級補足：
 *   沒有 --append-system-prompt-file 到 角色提示前置進 prompt 本文
 *   沒有 --max-turns                 到 只能靠 timeout 控成本
 *   沒有細粒度工具白名單             到 只有三級 sandbox；真正的邊界本來就在 ExecutionSandbox
 */
@Component
public class CodexRuntime implements AgentRuntime {
    /** Codex 無細粒度 CLI 白名單；寫入需求仍須通過外層 SandboxPolicy 預檢。 */
    @Override public boolean permitsCommand(List<String> tools, List<String> argv) { return true; }

    private static final Logger log = LoggerFactory.getLogger(CodexRuntime.class);
    /** codex exec 與 exec resume 的 PROMPT 引數為 "-" 時從 stdin 讀取。 */
    static final String STDIN_PROMPT = "-";

    private final AiTeamProperties props;
    private final ExecutionSandbox sandbox;
    private final ObjectMapper mapper = new ObjectMapper();

    public CodexRuntime(AiTeamProperties props, ExecutionSandbox sandbox) {
        this.props = props;
        this.sandbox = sandbox;
    }

    @Override
    public String id() {
        return "codex";
    }

    /**
     * 少掉的三項是實測結果，不是還沒寫。宣告成有而實際沒有，
     * 會讓呼叫端跳過降級，失敗樣態是「安靜地少做事」而不是報錯。
     */
    @Override
    public Set<Capability> capabilities() {
        return EnumSet.of(
                Capability.STRUCTURED_OUTPUT,
                Capability.SESSION_RESUME,
                Capability.MODEL_SELECTION,
                Capability.NATIVE_AGENTS_MAP);
    }

    /** codex 的 session、憑證與多個 sqlite 狀態檔都在 ~/.codex。 */
    @Override
    public List<Path> statePaths(Path home) {
        return List.of(home.resolve(".codex"));
    }

    /** config.toml 可定義 hook 與 MCP、plugins 與 skills 會被載入，三者釘回唯讀。 */
    @Override
    public List<Path> escalationPins(Path home) {
        return List.of(
                home.resolve(".codex/config.toml"),
                home.resolve(".codex/plugins"),
                home.resolve(".codex/skills"));
    }

    @Override
    public RuntimeResult execute(RuntimeRequest req) throws RuntimeExecutionException {
        return execute(req, session -> {});
    }

    @Override
    public RuntimeResult execute(RuntimeRequest req, java.util.function.Consumer<String> sessionStarted)
            throws RuntimeExecutionException {
        Path logDir = props.logsPath().resolve(req.taskId());
        Path stderrLog = logDir.resolve(req.role() + ".codex.stderr.log");
        Path stdoutLog = logDir.resolve(req.role() + ".codex.jsonl");

        Process process = null;
        try {
            Files.createDirectories(logDir);
            SessionCapture.preserve(stdoutLog);
            SessionCapture.preserve(stderrLog);

            CodexSandboxPreparation.prepare(req.workingDirectory());
            checkSandbox(req, logDir.resolve(req.role() + ".codex.preflight.log"));

            // --output-schema 吃的是檔案路徑而不是字串（與 claude 的 --json-schema 不同），
            // 所以得先落檔。放在 logs 底下而不是 /tmp —— 沙箱開了 PrivateTmp=yes，
            // 寫進 /tmp 的檔案在受限行程裡看不到。
            Path schemaFile = null;
            if (req.jsonSchema() != null && !req.jsonSchema().isBlank()) {
                schemaFile = logDir.resolve(req.role() + ".codex.schema.json");
                Files.writeString(schemaFile, compatibleSchema(req.jsonSchema()), StandardCharsets.UTF_8);
            }

            String unit = props.unitPrefix() + req.taskId() + "-" + req.role()
                    + "-codex-" + (System.currentTimeMillis() / 1000);
            // Codex 額度訊息「try again at …」以本地時區輸出且不標時區；固定 UTC 才能精確換算成台北時間（QuotaRetryTime）。
            // 只套在 Codex 這個程序：systemd unit 不繼承 ProcessBuilder 的環境，所以用 env 前置。
            List<String> inner = new ArrayList<>(List.of("env", "TZ=UTC"));
            inner.addAll(buildCommand(req, schemaFile));
            List<String> cmd = sandbox.wrap(unit, req.sandboxPolicy(), req.workingDirectory(), inner);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectError(ProcessBuilder.Redirect.appendTo(stderrLog.toFile()));
            // 即時保留 stdout，逾時也有部分事件可追查，且不會卡住 pipe。
            pb.redirectOutput(stdoutLog.toFile());

            // 訂閱帳號認證：不可設 OPENAI_API_KEY，讓 codex 讀 ~/.codex/auth.json 的
            // ChatGPT 憑證。設了 key 會讓它改走 API 計費路徑。
            pb.environment().remove("OPENAI_API_KEY");

            log.info("[{}] 執行 {}（codex）：模型 {}，{} 個參數，逾時 {}",
                    req.taskId(), req.role(),
                    req.model() == null || req.model().isBlank() ? "(CLI 預設)" : req.model(),
                    cmd.size(), req.timeout());

            process = com.zipe.aiteam.sandbox.ArgumentListTooLong.start(pb);

            // 提示從 stdin 送入，寫完一定要關閉：codex exec 偵測到 stdin 是 pipe 就會等 EOF，
            // 不關閉會印一行 Reading additional input from stdin 然後靜靜掛到逾時 ——
            // 沒有錯誤、沒有結束碼，實測踩過。
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(buildPromptWithRole(req).getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }

            if (!SessionCapture.await(process, req.timeout(), stdoutLog, sessionStarted)) {
                process.destroyForcibly();
                QuotaExceeded.check(id(), Files.readString(stdoutLog, StandardCharsets.UTF_8), stdoutLog.toString());
                throw new RuntimeExecutionException(
                        "Codex 超過設定逾時 " + req.timeout() + "，已送出強制終止。"
                      + "耗時根因與 commit 狀態尚未確認。stdout：" + stdoutLog + "；stderr：" + stderrLog);
            }

            String stdout = Files.readString(stdoutLog, StandardCharsets.UTF_8);
            int exit = process.exitValue();
            QuotaExceeded.check(id(), stdout, stdoutLog.toString());
            ProviderAccessDenied.check(id(), stdout, stdoutLog.toString());

            if (exit != 0) {
                String stderrText = Files.exists(stderrLog) ? Files.readString(stderrLog, StandardCharsets.UTF_8) : "";
                if (RuntimeUsageError.looksLikeUsageError(exit, stdout, stderrText)
                        || RuntimeUsageError.looksLikeRequestContractError(exit, stdout, stderrText)) {
                    throw new RuntimeUsageError(
                            "Codex 請求在 agent 啟動前被拒絕（結束碼 " + exit + "）："
                          + Diagnostics.clip(stdout + "\n" + stderrText, 1200)
                          + "\n這是 runtime 請求契約的缺陷，不是額度、不是產品失敗；"
                          + "事件流為空：" + stdoutLog + "；stderr：" + stderrLog);
                }
                throw new RuntimeExecutionException(
                        "Codex 結束碼 " + exit + describeErrors(stdout)
                      + "。完整事件流：" + stdoutLog
                      + "；stderr：" + stderrLog);
            }
            return parse(stdout, stderrLog, stdoutLog, req);

        } catch (RuntimeExecutionException e) {
            throw e;
        } catch (InterruptedException e) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new RuntimeExecutionException("執行被中斷", e);
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            throw new RuntimeExecutionException("無法執行 Codex；stdout=" + stdoutLog
                    + "；stderr=" + stderrLog, e);
        }
    }

    /**
     * Codex Structured Outputs 只接受 JSON Schema 的子集合。團隊快照仍保留完整契約，
     * 只在送給 Codex 的副本移除不支援的 uniqueItems；陣列唯一性由 Traceability
     * 的確定性閘門驗證，不能因 runtime 相容處理而放寬。
     */
    String compatibleSchema(String schema) throws IOException {
        JsonNode root = mapper.readTree(schema);
        stripUnsupportedSchemaKeywords(root);
        return mapper.writeValueAsString(root);
    }

    private void stripUnsupportedSchemaKeywords(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            ((ObjectNode) node).remove("uniqueItems");
        }
        node.forEach(this::stripUnsupportedSchemaKeywords);
    }

    /** 無模型呼叫；與 exec 的 workspace-write 對應，且套用相同外層 policy。 */
    void checkSandbox(RuntimeRequest req, Path output) throws Exception {
        SessionCapture.preserve(output);
        List<String> probe = new ArrayList<>(List.of(props.codexBin().toString(), "sandbox"));
        if (req.sandboxPolicy().localTestNetwork()) {
            probe.addAll(CodexLocalTestPolicy.sandboxArguments(req, statePaths(props.userHome())));
            probe.addAll(List.of("-C", req.workingDirectory().toAbsolutePath().toString(), "--", "sh", "-ec",
                    "pwd; node -e \"$1\"; shift; for path do test -w \"$path\" || { printf 'not writable: %s\\n' \"$path\"; exit 23; }; done",
                    "local-test-preflight", CodexLocalTestPolicy.TCP_PROBE));
            CodexLocalTestPolicy.commandWrites(req, statePaths(props.userHome())).forEach(p -> probe.add(p.toString()));
        } else probe.addAll(List.of("-P", ":workspace", "-C", req.workingDirectory().toAbsolutePath().toString(), "--", "/bin/pwd"));
        List<String> wrapped = new ArrayList<>(sandbox.wrap(props.unitPrefix() + "codex-preflight-"
                + java.util.UUID.randomUUID(), req.sandboxPolicy(), req.workingDirectory(), probe));
        // 同時限制 unit 存活時間，避免用戶端逾時後留下 probe 子行程。
        if (wrapped.contains("--")) wrapped.addAll(wrapped.indexOf("--"),List.of("-p","RuntimeMaxSec=25","-p","KillMode=control-group"));
        Process process = com.zipe.aiteam.sandbox.ArgumentListTooLong.start(new ProcessBuilder(wrapped)
                .redirectErrorStream(true).redirectOutput(output.toFile()));
        try {
            process.getOutputStream().close();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                throw new RuntimeExecutionException("Codex 內層沙箱預檢逾時，尚未呼叫模型；證據：" + output);
            }
            if (process.exitValue() != 0) {
                throw new RuntimeExecutionException("Codex 內層沙箱預檢失敗，尚未呼叫模型："
                        + Diagnostics.clip(Files.readString(output), 2000) + "；證據：" + output);
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    /**
     * 以參數陣列建構指令，完全不經 shell。
     *
     * resume 是子指令而不是旗標，而且它不吃 --cd —— 工作目錄只能靠實際的
     * 行程 cwd，這裡由 ExecutionSandbox 的 --working-directory 帶入。
     */
    private List<String> buildCommand(RuntimeRequest req, Path schemaFile) {
        List<String> cmd = new ArrayList<>();
        cmd.add(props.codexBin().toString());
        cmd.add("exec");
        boolean resuming = req.resumeSessionId() != null && !req.resumeSessionId().isBlank();
        if (resuming) cmd.add("resume");

        // resume 會重新解析自己的選項；放在父層 exec 的沙箱設定雖然不會觸發
        // CLI 用法錯誤，實際續跑卻會退回 :read-only。因此續跑選項必須放在
        // `codex exec resume` 之後，並在 session id 之前。
        if (req.sandboxPolicy().localTestNetwork()) {
            cmd.addAll(CodexLocalTestPolicy.execArguments(req, statePaths(props.userHome())));
        } else if (resuming) {
            // resume 子指令沒有 -s，但有 -c；用同一個設定鍵明確恢復。
            cmd.addAll(List.of("-c", "sandbox_mode=\"workspace-write\""));
        } else {
            cmd.addAll(List.of("-s", "workspace-write"));
        }

        if (!resuming) {
            // 只有非 resume 的路徑吃 --cd。resume 傳了會直接以用法錯誤結束（碼 2）。
            cmd.add("--cd");
            cmd.add(req.workingDirectory().toAbsolutePath().toString());
        }

        cmd.add("--json");

        if (schemaFile != null) {
            cmd.add("--output-schema");
            cmd.add(schemaFile.toAbsolutePath().toString());
        }

        if (req.model() != null && !req.model().isBlank()) {
            cmd.add("-m");
            cmd.add(req.model());
        }

        // 縱深防禦而已。codex 只有三級沙箱，真正的隔離是外層的 systemd unit ——
        if (req.reasoningEffort() != null && !req.reasoningEffort().isBlank()) {
            cmd.add("-c");
            cmd.add("model_reasoning_effort=\"" + req.reasoningEffort() + "\"");
        }

        if (resuming) cmd.add(req.resumeSessionId());

        // 已實測：即使這裡放成 danger-full-access，越界寫入仍然被外層擋下。
        // 提示用 "-" 從 stdin 讀入。放在 argv 會受 Linux 單一參數 131072 位元組上限限制，
        // 長任務累積的需求、計畫與交接紀錄超過後，連 CLI 都啟動不了（#1016 分流節點即是）。
        // 已實測 codex-cli 0.153.4：151KB 提示經 systemd-run --pipe 由 stdin 送入可正常讀取。
        cmd.add(STDIN_PROMPT);
        return cmd;
    }

    /**
     * APPEND_SYSTEM_PROMPT 的降級。
     *
     * codex 沒有附加系統提示的旗標，所以角色定義只能併進 prompt 本文。
     * 這與 claude 的 --append-system-prompt-file 有實質差異：角色提示在這裡
     * 是一般使用者輸入，不具備系統提示的優先級。
     *
     * resume 時不重複附上 —— 前一輪的 context 還在。
     */
    String buildPromptWithRole(RuntimeRequest req) {
        if (req.resumeSessionId() != null && !req.resumeSessionId().isBlank()) {
            return req.prompt();
        }
        String role = "";
        try {
            role = Files.readString(req.systemPromptFile(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 角色定義讀不到就讓它明確地少掉，而不是安靜地用空角色跑完一輪。
            log.warn("讀不到角色定義 {}，本次 codex 執行將沒有角色提示：{}",
                    req.systemPromptFile(), e.toString());
        }
        if (role.isBlank()) return req.prompt();
        return "以下是你的角色定義，請完全依照它行事：\n\n" + role
             + "\n\n---\n\n" + req.prompt();
    }

    /**
     * 解析 JSONL 事件流。
     *
     * 與 claude 的單一 JSON 物件不同，這裡是一行一個事件，要點：
     *   thread.started  到 thread_id，就是 resume 要用的 id
     *   item.completed  到 agent_message 的 text；取最後一個
     *   turn.completed  到 usage（只有 token 數，沒有金額）
     */
    RuntimeResult parse(String stdout, Path stderrLog, Path stdoutLog, RuntimeRequest req)
            throws RuntimeExecutionException {
        String threadId = null;
        String lastMessage = "";
        JsonNode usage = null;
        List<String> errors = new ArrayList<>();
        boolean turnFailed = false;
        var toolFailures = mapper.createArrayNode();

        for (String line : stdout.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            JsonNode ev;
            try {
                ev = mapper.readTree(line);
            } catch (Exception ignored) {
                continue;   // 非 JSON 的雜訊行直接略過
            }
            String type = ev.path("type").asText("");
            switch (type) {
                case "thread.started" -> threadId = ev.path("thread_id").asText(null);
                case "item.completed" -> {
                    JsonNode item = ev.path("item");
                    String itemType = item.path("type").asText("");
                    // codex 把「模型名不存在」這類問題放進 error item。只認 agent_message
                    // 的話會把它整個吞掉，最後只剩一個看不出原因的結束碼。
                    if ("error".equals(itemType)) {
                        errors.add(item.path("message").asText(""));
                    }
                    if ("agent_message".equals(itemType)) {
                        lastMessage = item.path("text").asText("");
                    }
                    if ("command_execution".equals(itemType)
                            && ("failed".equals(item.path("status").asText())
                                || item.path("exit_code").asInt(0) != 0)) {
                        var failed = toolFailures.addObject();
                        failed.put("tool_name", "command_execution");
                        failed.put("failure_kind", "COMMAND_FAILURE");
                        failed.put("tool_use_id", item.path("id").asText("未提供"));
                        failed.putObject("tool_input").put("command", item.path("command").asText("未提供"));
                        failed.put("reason", "exit_code=" + item.path("exit_code") + "；"
                                + Diagnostics.clip(item.path("aggregated_output").asText("未提供錯誤輸出"), 700));
                    }
                }
                case "error" -> errors.add(ev.path("message").asText("未提供錯誤內容"));
                case "turn.failed" -> {
                    turnFailed = true;
                    errors.add(ev.path("error").path("message").asText("turn.failed 未提供錯誤內容"));
                }
                case "turn.completed" -> usage = ev.get("usage");
                default -> { }
            }
        }

        if (threadId == null) {
            throw new RuntimeExecutionException(
                    "Codex 的事件流裡沒有 thread.started，無法取得 session id。完整輸出：" + stdoutLog);
        }


        // 有錯誤事件而且完全沒有產出訊息時，讓原因浮上來，
        // 而不是回傳一個空字串讓上游以為 agent 只是沒話說。
        if (turnFailed || (lastMessage.isBlank() && !errors.isEmpty())) {
            throw new RuntimeExecutionException(
                    "Codex 執行未完成。事件流中的錯誤：" + Diagnostics.safe(String.join("；", errors))
                  + "。完整輸出：" + stdoutLog);
        }
        // schema 有要求時，codex 是把 JSON 放在 agent_message 的文字裡，
        // 不像 claude 會回一個已驗證的 structured_output 欄位，所以自己 parse。
        JsonNode structured = null;
        if (req.jsonSchema() != null && !req.jsonSchema().isBlank()) {
            try {
                structured = mapper.readTree(lastMessage);
            } catch (Exception e) {
                throw new RuntimeExecutionException(
                        "Codex 要求了 schema 但最後一則訊息不是 JSON：" + truncate(lastMessage, 800)
                      + "。完整輸出：" + stdoutLog, e);
            }
        }

        return new RuntimeResult(
                threadId,
                lastMessage,
                structured,
                // 沒有金額可回報：ChatGPT 訂閱不按次計費，事件流只有 token 數。
                // 寧可回 null，也不要用 token 數硬換一個看起來像成本的數字。
                null,
                false,
                stderrLog,
                describeModel(req, usage),
                // 實際 command_execution 失敗，不推測為權限問題；成功替代操作仍可繼續。
                toolFailures);
    }

    /**
     * 回報模型。
     *
     * 這裡只拿得到「我們要求的」值 —— 事件流沒有回報實際使用的模型，
     * 與 claude 的 modelUsage 不同。標註出來，免得比較不同輪次時誤以為是實測值。
     */
    private String describeModel(RuntimeRequest req, JsonNode usage) {
        String m = (req.model() == null || req.model().isBlank())
                ? "(codex 預設)" : req.model();
        if (req.reasoningEffort() != null && !req.reasoningEffort().isBlank()) m += "／推理：" + req.reasoningEffort();
        if (usage == null) return m + "（要求值）";
        return m + "（要求值）; tokens in=" + usage.path("input_tokens").asInt()
             + " out=" + usage.path("output_tokens").asInt();
    }


    /**
     * 把事件流裡的 error 事件濃縮成一句話，附在失敗訊息後面。
     *
     * 實測：傳一個 codex 不認識的模型名（例如把 claude 的 opus 設給它）不會乾脆拒絕，
     * 而是印一個 error item、退回 fallback metadata 繼續跑，最後才以結束碼 1 收場。
     * 少了這段，使用者看到的只會是「結束碼 1」，完全看不出是設定錯了模型。
     */
    private String describeErrors(String stdout) {
        List<String> found = new ArrayList<>();
        for (String line : stdout.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                JsonNode ev = mapper.readTree(line);
                JsonNode item = ev.path("item");
                if ("error".equals(ev.path("type").asText())) {
                    found.add(ev.path("message").asText(""));
                }
                if ("turn.failed".equals(ev.path("type").asText())) {
                    found.add(ev.path("error").path("message").asText("turn.failed"));
                }
                if ("error".equals(item.path("type").asText(""))) {
                    found.add(item.path("message").asText(""));
                }
            } catch (Exception ignored) {
                // 非 JSON 的雜訊行
            }
        }
        return found.isEmpty() ? "" : "（" + String.join("；", found) + "）";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
