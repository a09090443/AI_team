package com.zipe.aiteam.runtime;

import com.fasterxml.jackson.databind.JsonNode;
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
 * CodexRuntime 的解析與降級行為。
 *
 * 事件流的樣本取自 VM 上的實機輸出（見 docs/codex-runtime-report.md），
 * 不是憑 API 文件想像出來的 —— 這個類別的價值全在於它能不能吃下 codex 真正吐出的東西。
 */
class CodexRuntimeTest {
    @Test void outputSchema移除Codex不支援關鍵字但保留其餘限制() throws Exception {
        String schema = """
                {"type":"object","properties":{"rows":{"type":"array","minItems":1,
                "uniqueItems":true,"items":{"type":"array","uniqueItems":true,
                "items":{"type":"string","minLength":1}}}}}
                """;

        JsonNode compatible = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(runtime.compatibleSchema(schema));

        assertThat(compatible.findValues("uniqueItems")).isEmpty();
        assertThat(compatible.at("/properties/rows/minItems").asInt()).isEqualTo(1);
        assertThat(compatible.at("/properties/rows/items/items/minLength").asInt()).isEqualTo(1);
    }

    @Test void 無效Schema必須明確失敗而不是送出不受控請求() {
        assertThatThrownBy(() -> runtime.compatibleSchema("{not-json"))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test void explicitModelAndEffortApplyToFreshAndResumedRuns() {
        var props = org.mockito.Mockito.mock(com.zipe.aiteam.config.AiTeamProperties.class);
        org.mockito.Mockito.when(props.codexBin()).thenReturn(Path.of("codex"));
        for (String session : new String[]{null, "saved-session"}) {
            var req = new AgentRuntime.RuntimeRequest("42", "plan", tmp, tmp.resolve("qa.md"), "test",
                    List.of(), null, session, 1, Duration.ofSeconds(10),
                    new SandboxPolicy(List.of(), List.of(), 256, 100, 32), "gpt-5.6-sol", "medium");
            List<String> cmd = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    new CodexRuntime(props, null), "buildCommand", req, null);
            assertThat(cmd).containsSubsequence("-m", "gpt-5.6-sol", "-c", "model_reasoning_effort=\"medium\"");
        }
    }
    @Test void promptNeverGoesIntoArgvSoLongTasksCanStillLaunch() {
        // Linux 單一參數上限 131072 位元組；長任務的提示超過後，連 CLI 都啟動不了。
        var props = org.mockito.Mockito.mock(com.zipe.aiteam.config.AiTeamProperties.class);
        org.mockito.Mockito.when(props.codexBin()).thenReturn(Path.of("codex"));
        String huge = "需求與交接紀錄".repeat(40_000);
        for (String session : new String[]{null, "saved-session"}) {
            var req = new AgentRuntime.RuntimeRequest("42", "triage", tmp, tmp.resolve("qa.md"), huge,
                    List.of(), null, session, 1, Duration.ofSeconds(10),
                    new SandboxPolicy(List.of(), List.of(), 256, 100, 32), "", "");
            List<String> cmd = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    new CodexRuntime(props, null), "buildCommand", req, null);
            assertThat(cmd.getLast()).isEqualTo("-");
            assertThat(cmd).noneMatch(arg -> arg.contains("需求與交接紀錄"));
            assertThat(cmd.stream().mapToInt(a -> a.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).max().orElse(0))
                    .isLessThan(131_072);
        }
    }
    @Test void oldRequestDoesNotOverrideEffort() {
        var props = org.mockito.Mockito.mock(com.zipe.aiteam.config.AiTeamProperties.class);
        org.mockito.Mockito.when(props.codexBin()).thenReturn(Path.of("codex"));
        List<String> cmd = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                new CodexRuntime(props, null), "buildCommand", request(null), null);
        assertThat(cmd).doesNotContain("-c", "-m");
    }

    @Test
    void resume的sandbox參數必須放在子指令() throws Exception {
        Files.writeString(tmp.resolve("pm.md"), "role");
        var props = org.mockito.Mockito.mock(com.zipe.aiteam.config.AiTeamProperties.class);
        org.mockito.Mockito.when(props.codexBin()).thenReturn(Path.of("codex"));
        var req = new AgentRuntime.RuntimeRequest("42", "pm", tmp, tmp.resolve("pm.md"), "resume",
                List.of(), null, "saved-session", 1, Duration.ofSeconds(10),
                new SandboxPolicy(List.of(), List.of(), 256, 100, 32), "model");
        List<String> cmd = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                new CodexRuntime(props, null), "buildCommand", req, null);
        assertThat(cmd).startsWith("codex", "exec", "resume", "-c", "sandbox_mode=\"workspace-write\"");
        assertThat(cmd.indexOf("saved-session")).isGreaterThan(cmd.indexOf("sandbox_mode=\"workspace-write\""));
        assertThat(cmd).doesNotContain("--cd");
    }

    @Test
    void localTestResume在子指令套用具名權限() throws Exception {
        Files.writeString(tmp.resolve("qa.md"), "role");
        var props = org.mockito.Mockito.mock(com.zipe.aiteam.config.AiTeamProperties.class);
        org.mockito.Mockito.when(props.codexBin()).thenReturn(Path.of("codex"));
        org.mockito.Mockito.when(props.userHome()).thenReturn(tmp.resolve("home"));
        var req = new AgentRuntime.RuntimeRequest("42", "qa", tmp, tmp.resolve("qa.md"), "resume",
                List.of(), null, "saved-session", 1, Duration.ofSeconds(10),
                new SandboxPolicy(List.of(tmp.resolve("target")), List.of(), 256, 100, 32, true), "model");
        List<String> cmd = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                new CodexRuntime(props, null), "buildCommand", req, null);

        assertThat(cmd).startsWith("codex", "exec", "resume");
        assertThat(cmd).contains("default_permissions=\"" + CodexLocalTestPolicy.PROFILE + "\"");
        assertThat(cmd.indexOf("default_permissions=\"" + CodexLocalTestPolicy.PROFILE + "\""))
                .isLessThan(cmd.indexOf("saved-session"));
        assertThat(cmd).doesNotContain("-s", "--cd");
    }

    @Test
    void turnFailed不能被先前的正常訊息掩蓋() {
        String events = """
            {"type":"thread.started","thread_id":"t-fail"}
            {"type":"item.completed","item":{"type":"agent_message","text":"準備執行"}}
            {"type":"turn.failed","error":{"message":"connection refused"}}
            """;
        assertThatThrownBy(() -> runtime.parse(events, tmp.resolve("err"), tmp.resolve("out"), request(null)))
                .hasMessageContaining("connection refused");
    }

    @Test
    void 指令失敗保留證據但不宣稱是權限問題() throws Exception {
        String events = """
            {"type":"thread.started","thread_id":"t-tool"}
            {"type":"item.completed","item":{"id":"cmd-1","type":"command_execution","command":"git diff","status":"failed","exit_code":1,"aggregated_output":"bwrap: .agents: Read-only file system"}}
            {"type":"item.completed","item":{"type":"agent_message","text":"無法讀取 diff"}}
            {"type":"turn.completed"}
            """;
        var result = runtime.parse(events, tmp.resolve("err"), tmp.resolve("out"), request(null));
        assertThat(Diagnostics.toolFailures(result.permissionDenials()))
                .contains("cmd-1", "git diff", "exit_code=1", "Read-only file system");
    }

    @TempDir Path tmp;

    private final CodexRuntime runtime = new CodexRuntime(null, null);

    private AgentRuntime.RuntimeRequest request(String schema) {
        return new AgentRuntime.RuntimeRequest(
                "1009", "PM", tmp, tmp.resolve("pm.md"), "需求內容",
                List.of(), schema, null, 30, Duration.ofSeconds(60),
                new SandboxPolicy(List.of(), List.of(), 256, 100, 32), "");
    }

    @Test
    void 解析事件流取出_threadId_與最後訊息() throws Exception {
        String jsonl = """
                {"type":"thread.started","thread_id":"01a0718b-7d75-7cc3-b51a-4e1d3bd7b714"}
                {"type":"turn.started"}
                {"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"第一則"}}
                {"type":"item.completed","item":{"id":"item_1","type":"agent_message","text":"最後一則"}}
                {"type":"turn.completed","usage":{"input_tokens":15214,"output_tokens":5}}
                """;

        AgentRuntime.RuntimeResult r = runtime.parse(jsonl, tmp.resolve("e.log"), tmp.resolve("o.jsonl"),
                request(null));

        assertThat(r.sessionId()).isEqualTo("01a0718b-7d75-7cc3-b51a-4e1d3bd7b714");
        assertThat(r.rawText()).isEqualTo("最後一則");
        assertThat(r.isError()).isFalse();
    }

    @Test
    void 有_schema_時把最後訊息當成_JSON_解出來() throws Exception {
        String jsonl = """
                {"type":"thread.started","thread_id":"t-1"}
                {"type":"item.completed","item":{"type":"agent_message","text":"{\\"decision\\":\\"PROCEED\\"}"}}
                {"type":"turn.completed","usage":{"input_tokens":10,"output_tokens":2}}
                """;

        AgentRuntime.RuntimeResult r = runtime.parse(jsonl, tmp.resolve("e.log"), tmp.resolve("o.jsonl"),
                request("{\"type\":\"object\"}"));

        assertThat(r.structuredOutput()).isNotNull();
        assertThat(r.structuredOutput().path("decision").asText()).isEqualTo("PROCEED");
    }

    @Test
    void 要求了_schema_但回的不是_JSON_要明確失敗() {
        String jsonl = """
                {"type":"thread.started","thread_id":"t-1"}
                {"type":"item.completed","item":{"type":"agent_message","text":"我沒有照格式回答"}}
                """;

        assertThatThrownBy(() -> runtime.parse(jsonl, tmp.resolve("e.log"), tmp.resolve("o.jsonl"),
                request("{\"type\":\"object\"}")))
                .isInstanceOf(AgentRuntime.RuntimeExecutionException.class)
                .hasMessageContaining("不是 JSON");
    }

    @Test
    void 沒有_threadStarted_就失敗而不是回傳_null_的_sessionId() {
        String jsonl = """
                {"type":"turn.started"}
                {"type":"item.completed","item":{"type":"agent_message","text":"哈囉"}}
                """;

        assertThatThrownBy(() -> runtime.parse(jsonl, tmp.resolve("e.log"), tmp.resolve("o.jsonl"),
                request(null)))
                .isInstanceOf(AgentRuntime.RuntimeExecutionException.class)
                .hasMessageContaining("thread.started");
    }

    @Test
    void 非_JSON_的雜訊行不會讓解析爆掉() throws Exception {
        String jsonl = """
                warning: Codex could not find bubblewrap on PATH.
                {"type":"thread.started","thread_id":"t-9"}
                {"type":"item.completed","item":{"type":"agent_message","text":"好"}}
                """;

        AgentRuntime.RuntimeResult r = runtime.parse(jsonl, tmp.resolve("e.log"), tmp.resolve("o.jsonl"),
                request(null));

        assertThat(r.sessionId()).isEqualTo("t-9");
        assertThat(r.rawText()).isEqualTo("好");
    }

    @Test
    void 訂閱制沒有金額可回報_成本必須是_null() throws Exception {
        String jsonl = """
                {"type":"thread.started","thread_id":"t-1"}
                {"type":"item.completed","item":{"type":"agent_message","text":"好"}}
                {"type":"turn.completed","usage":{"input_tokens":100,"output_tokens":20}}
                """;

        AgentRuntime.RuntimeResult r = runtime.parse(jsonl, tmp.resolve("e.log"), tmp.resolve("o.jsonl"),
                request(null));

        assertThat(r.costEstimate()).isNull();
        // token 數還是要留在紀錄裡，否則用量完全不可見
        assertThat(r.modelsUsed()).contains("tokens in=100").contains("out=20");
    }

    @Test
    void 沒有附加系統提示的旗標_角色定義改併進_prompt_本文() throws Exception {
        Files.writeString(tmp.resolve("pm.md"), "你是 PM。", StandardCharsets.UTF_8);

        String prompt = runtime.buildPromptWithRole(request(null));

        assertThat(prompt).contains("你是 PM。").contains("需求內容");
    }

    @Test
    void 角色定義讀不到時不丟例外_只是少了角色提示() {
        // 檔案不存在
        String prompt = runtime.buildPromptWithRole(request(null));
        assertThat(prompt).isEqualTo("需求內容");
    }

    @Test
    void 能力宣告必須誠實反映實測結果() {
        assertThat(runtime.capabilities())
                .contains(AgentRuntime.Capability.STRUCTURED_OUTPUT,
                          AgentRuntime.Capability.SESSION_RESUME,
                          AgentRuntime.Capability.MODEL_SELECTION,
                          AgentRuntime.Capability.NATIVE_AGENTS_MAP)
                // 這三項 codex 確實沒有。宣告成有會讓呼叫端跳過降級。
                .doesNotContain(AgentRuntime.Capability.APPEND_SYSTEM_PROMPT,
                                AgentRuntime.Capability.MAX_TURNS,
                                AgentRuntime.Capability.COST_REPORTING);
    }

    @Test
    void 狀態路徑與提權釘選指向_codex_自己的目錄() {
        Path home = Path.of("/home/u");
        assertThat(runtime.statePaths(home)).containsExactly(home.resolve(".codex"));
        assertThat(runtime.escalationPins(home))
                .contains(home.resolve(".codex/config.toml"),
                          home.resolve(".codex/plugins"),
                          home.resolve(".codex/skills"));
    }

    @Test
    void 只有錯誤事件而沒有產出時要把原因帶出來() {
        // 實測樣本：把 claude 的模型名設給 codex 會走到這裡
        String jsonl = """
                {"type":"thread.started","thread_id":"t-1"}
                {"type":"item.completed","item":{"id":"item_0","type":"error","message":"Model metadata for `opus` not found."}}
                {"type":"turn.started"}
                """;

        assertThatThrownBy(() -> runtime.parse(jsonl, tmp.resolve("e.log"), tmp.resolve("o.jsonl"),
                request(null)))
                .isInstanceOf(AgentRuntime.RuntimeExecutionException.class)
                .hasMessageContaining("Model metadata");
    }

    @Test
    void 有錯誤事件但仍有產出時不擋下來() throws Exception {
        // 錯誤是警告性質（退回 fallback metadata）而 agent 仍完成了工作，
        // 這時不該把整輪判成失敗。
        String jsonl = """
                {"type":"thread.started","thread_id":"t-1"}
                {"type":"item.completed","item":{"type":"error","message":"degraded"}}
                {"type":"item.completed","item":{"type":"agent_message","text":"還是做完了"}}
                """;

        AgentRuntime.RuntimeResult r = runtime.parse(jsonl, tmp.resolve("e.log"), tmp.resolve("o.jsonl"),
                request(null));

        assertThat(r.rawText()).isEqualTo("還是做完了");
    }
}
