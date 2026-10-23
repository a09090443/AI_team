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

/**
 * opencode CLI 的 AgentRuntime 實作。
 *
 * 以下用法皆已於 opencode 1.18.19 實測（見 docs-site/docs/opencode-runtime-report.md）：
 *   opencode run             非互動入口
 *   --format json            JSONL 事件流；每個事件都帶 sessionID
 *   --dir DIR                指定工作目錄，與 --session 可並用（與 codex resume 不同）
 *   --session ID             保有前一輪 context
 *   -m provider/model        指定模型；格式是「供應商/模型」，與其他 runtime 不通用
 *   --auto                   不因權限詢問而停住
 *   --                       分隔位置參數；prompt 一律放在它之後
 *   AGENTS.md                原生自動載入，不需任何指示
 *
 * 與 Claude Code 的四個缺口，由這裡或呼叫端降級補足：
 *   沒有 --append-system-prompt-file → 角色提示前置進 prompt 本文
 *   沒有輸出 schema 旗標             → 把 schema 寫進 prompt，回來自己 parse
 *   沒有 --max-turns                 → 只能靠 timeout 控成本
 *   沒有細粒度工具白名單             → 真正的邊界本來就在 ExecutionSandbox
 */
@Component
public class OpenCodeRuntime implements AgentRuntime {

    /** opencode 無細粒度 CLI 白名單；寫入需求仍須通過外層 SandboxPolicy 預檢。 */
    @Override public boolean permitsCommand(List<String> tools, List<String> argv) { return true; }

    private static final Logger log = LoggerFactory.getLogger(OpenCodeRuntime.class);

    private final AiTeamProperties props;
    private final ExecutionSandbox sandbox;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenCodeRuntime(AiTeamProperties props, ExecutionSandbox sandbox) {
        this.props = props;
        this.sandbox = sandbox;
    }

    @Override
    public String id() {
        return "opencode";
    }

    /**
     * 少掉的四項是實測結果，不是還沒寫。宣告成有而實際沒有，
     * 會讓呼叫端跳過降級，失敗樣態是「安靜地少做事」而不是報錯。
     *
     * COST_REPORTING 有列進來：step_finish 事件帶 cost（美元），已實測。
     */
    @Override
    public Set<Capability> capabilities() {
        return EnumSet.of(
                Capability.SESSION_RESUME,
                Capability.MODEL_SELECTION,
                Capability.COST_REPORTING,
                Capability.NATIVE_AGENTS_MAP);
    }

    /**
     * opencode 的狀態散在兩處：設定與外掛在 ~/.config/opencode，
     * 憑證（auth.json）、session 資料庫與紀錄在 ~/.local/share/opencode。
     * 只給其中一個會讓 resume 或認證其中一項靜默失效。
     */
    @Override
    public List<Path> statePaths(Path home) {
        return List.of(
                home.resolve(".config/opencode"),
                home.resolve(".local/share/opencode"),
                home.resolve(".cache/opencode"));
    }

    /**
     * 設定檔可定義 MCP server 與外掛、外掛目錄本身就是會被載入的程式碼，全部釘回唯讀。
     * node_modules 一併釘住 —— 外掛是以 npm 套件形式安裝在設定目錄底下的。
     */
    @Override
    public List<Path> escalationPins(Path home) {
        Path config = home.resolve(".config/opencode");
        return List.of(
                config.resolve("opencode.json"),
                config.resolve("opencode.jsonc"),
                config.resolve("package.json"),
                config.resolve("node_modules"),
                config.resolve("plugin"),
                config.resolve("agent"),
                config.resolve("command"));
    }

    @Override
    public RuntimeResult execute(RuntimeRequest req) throws RuntimeExecutionException {
        return execute(req, session -> {});
    }

    @Override
    public RuntimeResult execute(RuntimeRequest req, java.util.function.Consumer<String> sessionStarted)
            throws RuntimeExecutionException {
        Path logDir = props.logsPath().resolve(req.taskId());
        Path stderrLog = logDir.resolve(req.role() + ".opencode.stderr.log");
        Path stdoutLog = logDir.resolve(req.role() + ".opencode.jsonl");

        String unit = props.unitPrefix() + req.taskId() + "-" + req.role()
                + "-opencode-" + java.util.UUID.randomUUID();
        Process process = null;
        try {
            Files.createDirectories(logDir);
            SessionCapture.preserve(stdoutLog);
            SessionCapture.preserve(stderrLog);

            List<String> cmd = sandbox.wrap(unit, req.sandboxPolicy(),
                    req.workingDirectory(), buildCommand(req));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectError(ProcessBuilder.Redirect.appendTo(stderrLog.toFile()));
            // 即時保留 stdout，逾時也有部分事件可追查，且不會卡住 pipe。
            pb.redirectOutput(stdoutLog.toFile());

            log.info("[{}] 執行 {}（opencode）：模型 {}，{} 個參數，逾時 {}",
                    req.taskId(), req.role(),
                    req.model() == null || req.model().isBlank() ? "(CLI 預設)" : req.model(),
                    cmd.size(), req.timeout());

            process = com.zipe.aiteam.sandbox.ArgumentListTooLong.start(pb);

            // 這一步不是形式。實測：opencode run 沒拿到位置參數時會去讀 stdin，
            // stdin 是 pipe 就一路等到逾時 —— 沒有錯誤、沒有結束碼。
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.flush();
            }

            if (!SessionCapture.await(process, req.timeout(), stdoutLog, sessionStarted,
                    OpenCodeRuntime::sessionOf)) {
                var cleanup = com.zipe.aiteam.sandbox.SandboxUnitCleanup.terminate(unit, process);
                throw new RuntimeTimedOut(id(), req.timeout(), stdoutLog, stderrLog, cleanup);
            }

            String stdout = Files.readString(stdoutLog, StandardCharsets.UTF_8);
            int exit = process.exitValue();
            QuotaExceeded.check(id(), stdout, stdoutLog.toString());

            if (exit != 0) {
                String stderrText = Files.exists(stderrLog) ? Files.readString(stderrLog, StandardCharsets.UTF_8) : "";
                if (RuntimeUsageError.looksLikeYargsUsageError(exit, stdout, stderrText)) {
                    throw new RuntimeUsageError(
                            "opencode CLI 用法錯誤，指令未送達模型（結束碼 " + exit + "）："
                          + Diagnostics.clip(stderrText, 1200)
                          + "\n這是本專案組裝參數的缺陷，不是額度、不是產品失敗；"
                          + "事件流為空：" + stdoutLog + "；stderr：" + stderrLog);
                }
                throw new RuntimeExecutionException(
                        "opencode 結束碼 " + exit + describeErrors(stdout)
                      + "。完整事件流：" + stdoutLog
                      + "；stderr：" + stderrLog);
            }
            return parse(stdout, stderrLog, stdoutLog, req);

        } catch (RuntimeExecutionException e) {
            throw e;
        } catch (InterruptedException e) {
            var cleanup = com.zipe.aiteam.sandbox.SandboxUnitCleanup.terminate(unit, process);
            Thread.currentThread().interrupt();
            throw new RuntimeExecutionException("執行被中斷；sandbox 清理=" + cleanup.status() + "；unit=" + unit, e);
        } catch (Exception e) {
            var cleanup = com.zipe.aiteam.sandbox.SandboxUnitCleanup.terminate(unit, process);
            throw new RuntimeExecutionException("無法執行 opencode；stdout=" + stdoutLog
                    + "；stderr=" + stderrLog + "；sandbox 清理=" + cleanup.status(), e);
        }
    }

    /** 每個事件都帶 sessionID，取到第一個就夠；失敗事件也帶，所以中斷時仍接得回去。 */
    static String sessionOf(JsonNode event) {
        return event.path("sessionID").asText("");
    }

    /**
     * 以參數陣列建構指令，完全不經 shell。
     *
     * prompt 一律放在 `--` 之後。實測 opencode 的參數解析不會對不認識的旗標報錯，
     * 而是印出說明並以結束碼 1 收場 —— 少了 `--`，一個以 "-" 開頭的 prompt
     * 會被吃成旗標，失敗訊息卻完全看不出原因。
     */
    List<String> buildCommand(RuntimeRequest req) {
        List<String> cmd = new ArrayList<>();
        cmd.add(props.opencodeBin().toString());
        cmd.add("run");
        cmd.add("--format");
        cmd.add("json");

        // --dir 與 --session 可以並用（實測），不必像 codex resume 那樣分兩條路徑。
        cmd.add("--dir");
        cmd.add(req.workingDirectory().toAbsolutePath().toString());

        if (req.resumeSessionId() != null && !req.resumeSessionId().isBlank()) {
            cmd.add("--session");
            cmd.add(req.resumeSessionId());
        }

        // 模型格式是 provider/model，與 claude、codex 都不通用；空字串整個略過旗標。
        if (req.model() != null && !req.model().isBlank()) {
            cmd.add("-m");
            cmd.add(req.model());
        }

        // 縱深防禦以外的考量：headless 下權限詢問無人可答，等於掛到逾時。
        // 真正的隔離仍然是外層的 systemd unit。
        cmd.add("--auto");

        cmd.add("--");
        cmd.add(buildPromptWithRole(req));
        return cmd;
    }

    /**
     * APPEND_SYSTEM_PROMPT 與 STRUCTURED_OUTPUT 兩項降級。
     *
     * opencode 沒有附加系統提示的旗標，也沒有輸出 schema 的旗標，所以角色定義與
     * schema 都只能併進 prompt 本文。與 claude 的 --append-system-prompt-file、
     * codex 的 --output-schema 有實質差異：這裡兩者都只是一般使用者輸入，
     * 沒有系統提示的優先級，schema 也沒有 CLI 層的驗證。
     *
     * resume 時不重複附上角色 —— 前一輪的 context 還在。
     */
    String buildPromptWithRole(RuntimeRequest req) {
        boolean resuming = req.resumeSessionId() != null && !req.resumeSessionId().isBlank();
        StringBuilder sb = new StringBuilder();
        if (!resuming) {
            String role = "";
            try {
                role = Files.readString(req.systemPromptFile(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                // 角色定義讀不到就讓它明確地少掉，而不是安靜地用空角色跑完一輪。
                log.warn("讀不到角色定義 {}，本次 opencode 執行將沒有角色提示：{}",
                        req.systemPromptFile(), e.toString());
            }
            if (!role.isBlank()) {
                sb.append("以下是你的角色定義，請完全依照它行事：\n\n").append(role).append("\n\n---\n\n");
            }
        }
        sb.append(req.prompt());
        if (req.jsonSchema() != null && !req.jsonSchema().isBlank()) {
            sb.append("\n\n---\n\n輸出格式要求：你這一輪的最後一則訊息必須「只有 JSON」，"
                    + "符合下列 JSON Schema，不要加任何說明文字，也不要用 Markdown 圍欄。\n\n")
              .append(req.jsonSchema());
        }
        return sb.toString();
    }

    /**
     * 解析 JSONL 事件流。實測樣本見 docs-site/docs/opencode-runtime-report.md：
     *   step_start   → 一個 step 開始
     *   text         → part.text，助理的文字；一輪可能有多個，取最後一個
     *   tool_use     → part.tool／part.callID／part.state，工具呼叫與其結果
     *   step_finish  → part.tokens 與 part.cost（美元），逐 step 累加才是本輪總額
     *   error        → error.name 與 error.data.message，結束碼同時為 1
     */
    RuntimeResult parse(String stdout, Path stderrLog, Path stdoutLog, RuntimeRequest req)
            throws RuntimeExecutionException {
        String sessionId = null;
        String lastText = "";
        double cost = 0;
        boolean hasCost = false;
        long inputTokens = 0;
        long outputTokens = 0;
        List<String> errors = new ArrayList<>();
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
            if (sessionId == null && !sessionOf(ev).isBlank()) sessionId = sessionOf(ev);
            JsonNode part = ev.path("part");
            switch (ev.path("type").asText("")) {
                case "text" -> lastText = part.path("text").asText(lastText);
                case "step_finish" -> {
                    if (part.hasNonNull("cost")) {
                        cost += part.path("cost").asDouble();
                        hasCost = true;
                    }
                    inputTokens += part.path("tokens").path("input").asLong(0);
                    outputTokens += part.path("tokens").path("output").asLong(0);
                }
                case "tool_use" -> {
                    JsonNode state = part.path("state");
                    String status = state.path("status").asText("");
                    // 只有 completed 是實測看過的成功狀態。其餘終態一律留證，
                    // 但不推測成因 —— 權限、工具本身失敗、被外層沙箱擋下都長得像這樣。
                    if (!status.isEmpty() && !"completed".equals(status)
                            && !"pending".equals(status) && !"running".equals(status)) {
                        var failed = toolFailures.addObject();
                        failed.put("tool_name", part.path("tool").asText("未提供"));
                        failed.put("tool_use_id", part.path("callID").asText("未提供"));
                        failed.set("tool_input", state.path("input").isObject()
                                ? state.get("input") : mapper.createObjectNode());
                        failed.put("reason", "status=" + status + "；"
                                + Diagnostics.clip(state.path("error").asText(
                                        state.path("output").asText("未提供錯誤輸出")), 700));
                    }
                }
                case "error" -> errors.add(errorMessage(ev));
                default -> { }
            }
        }

        if (sessionId == null) {
            throw new RuntimeExecutionException(
                    "opencode 的事件流裡沒有任何帶 sessionID 的事件，無法取得 session id。完整輸出：" + stdoutLog);
        }

        // 有錯誤事件而且完全沒有產出訊息時，讓原因浮上來，
        // 而不是回傳一個空字串讓上游以為 agent 只是沒話說。
        if (lastText.isBlank() && !errors.isEmpty()) {
            throw new RuntimeExecutionException(
                    "opencode 執行未完成。事件流中的錯誤：" + Diagnostics.safe(String.join("；", errors))
                  + "。完整輸出：" + stdoutLog);
        }

        JsonNode structured = null;
        if (req.jsonSchema() != null && !req.jsonSchema().isBlank()) {
            try {
                structured = mapper.readTree(stripFence(lastText));
            } catch (Exception e) {
                throw new RuntimeExecutionException(
                        "opencode 要求了 schema 但最後一則訊息不是 JSON："
                      + Diagnostics.clip(lastText, 800) + "。完整輸出：" + stdoutLog, e);
            }
        }

        return new RuntimeResult(
                sessionId,
                lastText,
                structured,
                hasCost ? cost : null,
                false,
                stderrLog,
                describeModel(req, inputTokens, outputTokens),
                // 實際工具終態失敗，不推測為權限問題；成功替代操作仍可繼續。
                toolFailures);
    }

    /**
     * 拿掉 Markdown 圍欄。
     *
     * schema 是靠 prompt 要求的，沒有 CLI 層驗證，模型照樣可能把 JSON 包進圍欄。
     * 這不是「順手容錯」：不拆掉的話整個階段會因為一組反引號而判定失敗重跑。
     */
    static String stripFence(String text) {
        String s = text == null ? "" : text.trim();
        if (!s.startsWith("```")) return s;
        int firstBreak = s.indexOf('\n');
        if (firstBreak < 0) return s;
        int lastFence = s.lastIndexOf("```");
        if (lastFence <= firstBreak) return s;
        return s.substring(firstBreak + 1, lastFence).trim();
    }

    /** error 事件的訊息藏在 error.data.message，name 要一併帶上才看得出是哪一類。 */
    private static String errorMessage(JsonNode event) {
        JsonNode error = event.path("error");
        String name = error.path("name").asText("");
        String message = error.path("data").path("message").asText(
                error.path("message").asText("未提供錯誤內容"));
        return name.isBlank() ? message : name + "：" + message;
    }

    /**
     * 回報模型。
     *
     * 這裡只拿得到「我們要求的」值 —— 事件流沒有回報實際使用的模型，
     * 與 claude 的 modelUsage 不同。標註出來，免得比較不同輪次時誤以為是實測值。
     */
    private String describeModel(RuntimeRequest req, long inputTokens, long outputTokens) {
        String m = (req.model() == null || req.model().isBlank())
                ? "(opencode 預設)" : req.model();
        return m + "（要求值）; tokens in=" + inputTokens + " out=" + outputTokens;
    }

    /**
     * 把事件流裡的 error 事件濃縮成一句話，附在失敗訊息後面。
     *
     * 實測：模型名不存在、供應商回 404／429 都是走 error 事件加結束碼 1，
     * 少了這段，使用者看到的只會是「結束碼 1」，看不出是設定錯了模型還是被限流。
     */
    private String describeErrors(String stdout) {
        List<String> found = new ArrayList<>();
        for (String line : stdout.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                JsonNode ev = mapper.readTree(line);
                if ("error".equals(ev.path("type").asText())) found.add(errorMessage(ev));
            } catch (Exception ignored) {
                // 非 JSON 的雜訊行
            }
        }
        return found.isEmpty() ? "" : "（" + Diagnostics.clip(String.join("；", found), 1200) + "）";
    }
}
