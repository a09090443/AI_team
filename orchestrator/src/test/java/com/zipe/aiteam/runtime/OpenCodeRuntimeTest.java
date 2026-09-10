package com.zipe.aiteam.runtime;

import com.zipe.aiteam.sandbox.SandboxPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenCodeRuntime 的解析與降級行為。
 *
 * 事件流樣本逐字取自 opencode 1.18.19 的實機輸出（見 docs/opencode-runtime-report.md），
 * 不是憑文件想像出來的 —— 這個類別的價值全在於它能不能吃下 opencode 真正吐出的東西。
 */
class OpenCodeRuntimeTest {

    @TempDir Path tmp;

    private final OpenCodeRuntime runtime = new OpenCodeRuntime(null, null);

    private AgentRuntime.RuntimeRequest request(String schema) {
        return request(schema, null);
    }

    private AgentRuntime.RuntimeRequest request(String schema, String session) {
        return new AgentRuntime.RuntimeRequest(
                "1015", "PM", tmp, tmp.resolve("pm.md"), "需求內容",
                List.of(), schema, session, 30, Duration.ofSeconds(60),
                new SandboxPolicy(List.of(), List.of(), 256, 100, 32), "");
    }

    @Test
    void 解析事件流取出_sessionID_與最後一則文字() throws Exception {
        String jsonl = """
                {"type":"step_start","sessionID":"ses_f74910487ffenMfxxLtt2uH2Au","part":{"type":"step-start"}}
                {"type":"text","sessionID":"ses_f74910487ffenMfxxLtt2uH2Au","part":{"type":"text","text":"第一則"}}
                {"type":"text","sessionID":"ses_f74910487ffenMfxxLtt2uH2Au","part":{"type":"text","text":"最後一則"}}
                {"type":"step_finish","sessionID":"ses_f74910487ffenMfxxLtt2uH2Au","part":{"type":"step-finish","reason":"stop","tokens":{"input":7784,"output":9},"cost":0.00024385}}
                """;

        AgentRuntime.RuntimeResult r = runtime.parse(jsonl, tmp.resolve("e.log"), tmp.resolve("o.jsonl"),
                request(null));

        assertThat(r.sessionId()).isEqualTo("ses_f74910487ffenMfxxLtt2uH2Au");
        assertThat(r.rawText()).isEqualTo("最後一則");
        assertThat(r.isError()).isFalse();
    }

    @Test
    void 成本要逐個_step_累加而不是只取最後一個() throws Exception {
        // 實測：一次含工具呼叫的執行會有多個 step_finish，各自帶自己的 cost。
        String jsonl = """
                {"type":"step_start","sessionID":"ses_1","part":{"type":"step-start"}}
                {"type":"step_finish","sessionID":"ses_1","part":{"tokens":{"input":27,"output":21},"cost":0.0002533}}
                {"type":"text","sessionID":"ses_1","part":{"type":"text","text":"好"}}
                {"type":"step_finish","sessionID":"ses_1","part":{"tokens":{"input":184,"output":28},"cost":0.00025108}}
                """;

        AgentRuntime.RuntimeResult r = runtime.parse(jsonl, tmp.resolve("e.log"), tmp.resolve("o.jsonl"),
                request(null));

        assertThat(r.costEstimate()).isEqualTo(0.0002533 + 0.00025108);
        assertThat(r.modelsUsed()).contains("tokens in=211").contains("out=49");
    }

    @Test
    void 有_schema_時把最後一則文字當成_JSON_解出來() throws Exception {
        String jsonl = """
                {"type":"text","sessionID":"ses_1","part":{"type":"text","text":"{\\"decision\\":\\"PROCEED\\"}"}}
                {"type":"step_finish","sessionID":"ses_1","part":{"tokens":{"input":1,"output":1},"cost":0.1}}
                """;

        AgentRuntime.RuntimeResult r = runtime.parse(jsonl, tmp.resolve("e.log"), tmp.resolve("o.jsonl"),
                request("{\"type\":\"object\"}"));

        assertThat(r.structuredOutput().path("decision").asText()).isEqualTo("PROCEED");
    }

    @Test
    void schema只靠_prompt_要求_所以圍欄要拆掉而不是判定失敗() throws Exception {
        String jsonl = """
                {"type":"text","sessionID":"ses_1","part":{"type":"text","text":"```json\\n{\\"decision\\":\\"PROCEED\\"}\\n```"}}
                """;

        AgentRuntime.RuntimeResult r = runtime.parse(jsonl, tmp.resolve("e.log"), tmp.resolve("o.jsonl"),
                request("{\"type\":\"object\"}"));

        assertThat(r.structuredOutput().path("decision").asText()).isEqualTo("PROCEED");
    }

    @Test
    void 要求了_schema_但回的不是_JSON_要明確失敗() {
        String jsonl = """
                {"type":"text","sessionID":"ses_1","part":{"type":"text","text":"我沒有照格式回答"}}
                """;

        assertThatThrownBy(() -> runtime.parse(jsonl, tmp.resolve("e.log"), tmp.resolve("o.jsonl"),
                request("{\"type\":\"object\"}")))
                .isInstanceOf(AgentRuntime.RuntimeExecutionException.class)
                .hasMessageContaining("不是 JSON");
    }

    @Test
    void 沒有任何帶_sessionID_的事件就失敗而不是回傳_null() {
        String jsonl = """
                {"type":"step_start","part":{"type":"step-start"}}
                """;

        assertThatThrownBy(() -> runtime.parse(jsonl, tmp.resolve("e.log"), tmp.resolve("o.jsonl"),
                request(null)))
                .isInstanceOf(AgentRuntime.RuntimeExecutionException.class)
                .hasMessageContaining("sessionID");
    }

    @Test
    void 錯誤事件不能被當成一則空回答吞掉() {
        // 逐字取自實測：模型名不存在時 opencode 只吐這一行，結束碼 1。
        String jsonl = """
                {"type":"error","timestamp":1789045497997,"sessionID":"ses_f74942585ffeHAmfioBe08PqZV","error":{"name":"APIError","data":{"message":"This model is unavailable for free.","statusCode":404}}}
                """;

        assertThatThrownBy(() -> runtime.parse(jsonl, tmp.resolve("e.log"), tmp.resolve("o.jsonl"),
                request(null)))
                .isInstanceOf(AgentRuntime.RuntimeExecutionException.class)
                .hasMessageContaining("APIError")
                .hasMessageContaining("unavailable for free");
    }

    @Test
    void 工具終態失敗保留證據但不宣稱是權限問題() throws Exception {
        String jsonl = """
                {"type":"tool_use","sessionID":"ses_1","part":{"type":"tool","tool":"write","callID":"call-1","state":{"status":"error","input":{"filePath":"src/main/App.java"},"error":"EROFS: read-only file system"}}}
                {"type":"text","sessionID":"ses_1","part":{"type":"text","text":"寫不進去"}}
                """;

        AgentRuntime.RuntimeResult r = runtime.parse(jsonl, tmp.resolve("e.log"), tmp.resolve("o.jsonl"),
                request(null));

        assertThat(Diagnostics.toolFailures(r.permissionDenials()))
                .contains("write", "call-1", "status=error", "read-only file system");
    }

    @Test
    void 成功的工具呼叫不算失敗() throws Exception {
        String jsonl = """
                {"type":"tool_use","sessionID":"ses_1","part":{"type":"tool","tool":"read","callID":"call-1","state":{"status":"completed","input":{"filePath":"README.md"},"output":"hello"}}}
                {"type":"text","sessionID":"ses_1","part":{"type":"text","text":"好"}}
                """;

        AgentRuntime.RuntimeResult r = runtime.parse(jsonl, tmp.resolve("e.log"), tmp.resolve("o.jsonl"),
                request(null));

        assertThat(r.permissionDenials()).isEmpty();
    }

    @Test
    void 非_JSON_的雜訊行不會讓解析爆掉() throws Exception {
        String jsonl = """
                npm warn cleanup Failed to remove some directories
                {"type":"text","sessionID":"ses_9","part":{"type":"text","text":"好"}}
                """;

        AgentRuntime.RuntimeResult r = runtime.parse(jsonl, tmp.resolve("e.log"), tmp.resolve("o.jsonl"),
                request(null));

        assertThat(r.sessionId()).isEqualTo("ses_9");
        assertThat(r.rawText()).isEqualTo("好");
    }

    @Test
    void 沒有附加系統提示的旗標_角色定義改併進_prompt_本文() throws Exception {
        Files.writeString(tmp.resolve("pm.md"), "你是 PM。", StandardCharsets.UTF_8);

        String prompt = runtime.buildPromptWithRole(request(null));

        assertThat(prompt).contains("你是 PM。").contains("需求內容");
    }

    @Test
    void 角色定義讀不到時不丟例外_只是少了角色提示() {
        String prompt = runtime.buildPromptWithRole(request(null));
        assertThat(prompt).isEqualTo("需求內容");
    }

    @Test
    void resume時不重複附上角色定義() throws Exception {
        Files.writeString(tmp.resolve("pm.md"), "你是 PM。", StandardCharsets.UTF_8);

        String prompt = runtime.buildPromptWithRole(request(null, "ses_1"));

        assertThat(prompt).isEqualTo("需求內容");
    }

    @Test
    void 沒有輸出_schema_旗標_schema要寫進_prompt() throws Exception {
        String prompt = runtime.buildPromptWithRole(request("{\"type\":\"object\"}"));

        assertThat(prompt).contains("{\"type\":\"object\"}").contains("只有 JSON");
    }

    @Test
    void prompt一律放在雙連字號之後_避免被當成旗標() {
        var props = org.mockito.Mockito.mock(com.zipe.aiteam.config.AiTeamProperties.class);
        org.mockito.Mockito.when(props.opencodeBin()).thenReturn(Path.of("opencode"));

        List<String> cmd = new OpenCodeRuntime(props, null).buildCommand(request(null));

        assertThat(cmd).startsWith("opencode", "run", "--format", "json", "--dir");
        assertThat(cmd).containsSubsequence("--auto", "--", "需求內容");
        assertThat(cmd.indexOf("--")).isEqualTo(cmd.size() - 2);
    }

    @Test
    void resume時仍然帶著_dir_而且指定了_session() {
        var props = org.mockito.Mockito.mock(com.zipe.aiteam.config.AiTeamProperties.class);
        org.mockito.Mockito.when(props.opencodeBin()).thenReturn(Path.of("opencode"));

        List<String> cmd = new OpenCodeRuntime(props, null).buildCommand(request(null, "ses_1"));

        assertThat(cmd).containsSubsequence("--dir", tmp.toAbsolutePath().toString());
        assertThat(cmd).containsSubsequence("--session", "ses_1");
    }

    @Test
    void 沒指定模型就整個略過旗標() {
        var props = org.mockito.Mockito.mock(com.zipe.aiteam.config.AiTeamProperties.class);
        org.mockito.Mockito.when(props.opencodeBin()).thenReturn(Path.of("opencode"));

        assertThat(new OpenCodeRuntime(props, null).buildCommand(request(null))).doesNotContain("-m");
    }

    @Test
    void 能力宣告必須誠實反映實測結果() {
        assertThat(runtime.capabilities())
                .containsExactlyInAnyOrder(
                        AgentRuntime.Capability.SESSION_RESUME,
                        AgentRuntime.Capability.MODEL_SELECTION,
                        AgentRuntime.Capability.COST_REPORTING,
                        AgentRuntime.Capability.NATIVE_AGENTS_MAP);
    }

    @Test
    void 兩處狀態目錄都要可寫_否則認證或_resume_會靜默失效() {
        Path home = Path.of("/home/vboxuser");

        assertThat(runtime.statePaths(home))
                .contains(home.resolve(".config/opencode"), home.resolve(".local/share/opencode"));
        assertThat(runtime.escalationPins(home))
                .contains(home.resolve(".config/opencode").resolve("opencode.json"),
                          home.resolve(".config/opencode").resolve("node_modules"));
    }

    @Test
    void 用法錯誤要與產品失敗分開_但事件流非空就不算() {
        String usage = "Positionals:\n  message  message to send\n\nOptions:\n  -h, --help";

        assertThat(RuntimeUsageError.looksLikeYargsUsageError(1, "", usage)).isTrue();
        assertThat(RuntimeUsageError.looksLikeYargsUsageError(1, "",
                "Error: You must provide a message or a command")).isTrue();
        assertThat(RuntimeUsageError.looksLikeYargsUsageError(1,
                "{\"type\":\"error\",\"sessionID\":\"ses_1\"}", usage)).isFalse();
        assertThat(RuntimeUsageError.looksLikeYargsUsageError(0, "", usage)).isFalse();
    }
}
