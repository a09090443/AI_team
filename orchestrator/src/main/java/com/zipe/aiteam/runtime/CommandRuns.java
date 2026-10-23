package com.zipe.aiteam.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 從 runtime 的原始紀錄取出 agent 本輪實際執行的 shell 指令與結束碼。
 *
 * 模型回報的「品質檢查通過」是自陳；#1001 的實作明明 `gradle … test` 結束碼 1，仍回報 SUCCESS，
 * 系統因此跑了 16 分鐘注定失敗的獨立驗證。這裡讀的是 CLI 自己寫下的紀錄，不經過模型轉述。
 * 格式於 2026-10-18 以 VM 上的真實紀錄核對（Claude Code session jsonl、Codex exec --json 事件流）。
 */
public final class CommandRuns {
    private CommandRuns() {}
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern EXIT_CODE = Pattern.compile("^Exit code (-?\\d+)");
    private static final Pattern BASH_WRAPPER = Pattern.compile("^/bin/(?:ba)?sh -l?c (['\"])(.*)\\1$", Pattern.DOTALL);

    /** 單筆指令；exitCode 取不到時 failed 的視為 -1。 */
    public record CommandRun(String command, int exitCode) {
        public boolean failed() { return exitCode != 0; }
    }

    /** Codex `exec --json`：item.completed 的 command_execution 帶 command、exit_code、status。 */
    public static List<CommandRun> fromCodexEvents(String jsonl) {
        List<CommandRun> runs = new ArrayList<>();
        if (jsonl == null) return runs;
        for (String line : jsonl.split("\n")) {
            JsonNode event = parse(line);
            if (event == null || !"item.completed".equals(event.path("type").asText())) continue;
            JsonNode item = event.path("item");
            if (!"command_execution".equals(item.path("type").asText())) continue;
            int exit = item.path("exit_code").isInt() ? item.path("exit_code").asInt()
                    : "failed".equals(item.path("status").asText()) ? -1 : 0;
            if (exit == 0 && "failed".equals(item.path("status").asText())) exit = -1;
            runs.add(new CommandRun(unwrapShell(item.path("command").asText("")), exit));
        }
        return runs;
    }

    /**
     * Claude Code session 紀錄：assistant 訊息裡的 Bash tool_use 帶 input.command，
     * 對應的 tool_result 失敗時 is_error=true、內容以「Exit code N」開頭。只取 since 之後的紀錄，
     * 因為 --resume 的 session 會帶著前幾輪的歷史。
     */
    public static List<CommandRun> fromClaudeTranscript(List<String> lines, Instant since) {
        List<CommandRun> runs = new ArrayList<>();
        Map<String, String> commands = new HashMap<>();
        for (String line : lines) {
            JsonNode entry = parse(line);
            if (entry == null || before(entry, since)) continue;
            JsonNode content = entry.path("message").path("content");
            if (!content.isArray()) continue;
            for (JsonNode block : content) {
                String type = block.path("type").asText();
                if ("tool_use".equals(type) && "Bash".equals(block.path("name").asText()))
                    commands.put(block.path("id").asText(), block.path("input").path("command").asText(""));
                else if ("tool_result".equals(type) && commands.containsKey(block.path("tool_use_id").asText())) {
                    String command = commands.remove(block.path("tool_use_id").asText());
                    runs.add(new CommandRun(command.strip(), block.path("is_error").asBoolean(false) ? exitCode(block) : 0));
                }
            }
        }
        return runs;
    }

    /** 依 session id 在 ~/.claude/projects 底下找紀錄檔；找不到回空（不代表沒有執行）。 */
    public static List<CommandRun> fromClaudeSession(Path claudeHome, String sessionId, Instant since) {
        if (sessionId == null || sessionId.isBlank() || !sessionId.matches("[0-9A-Za-z-]+")) return List.of();
        Path projects = claudeHome.resolve("projects");
        if (!Files.isDirectory(projects)) return List.of();
        try (var dirs = Files.list(projects)) {
            for (Path dir : (Iterable<Path>) dirs::iterator) {
                Path file = dir.resolve(sessionId + ".jsonl");
                if (Files.isRegularFile(file)) return fromClaudeTranscript(Files.readAllLines(file, StandardCharsets.UTF_8), since);
            }
        } catch (IOException ignored) {
            // 讀不到就當沒有證據；不因此讓節點失敗。
        }
        return List.of();
    }

    /** Codex 把指令包成 /bin/bash -lc '…'；還原成 agent 實際下的指令。 */
    static String unwrapShell(String command) {
        String value = command == null ? "" : command.strip();
        Matcher m = BASH_WRAPPER.matcher(value);
        if (!m.matches()) return value;
        String inner = m.group(2);
        return "'".equals(m.group(1)) ? inner.replace("'\\''", "'") : inner.replace("\\\"", "\"");
    }

    private static int exitCode(JsonNode block) {
        JsonNode content = block.path("content");
        String text = content.isTextual() ? content.asText()
                : content.isArray() && !content.isEmpty() ? content.get(0).path("text").asText("") : "";
        Matcher m = EXIT_CODE.matcher(text.strip());
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static boolean before(JsonNode entry, Instant since) {
        if (since == null) return false;
        String at = entry.path("timestamp").asText("");
        if (at.isBlank()) return false;
        try { return Instant.parse(at).isBefore(since); } catch (Exception e) { return false; }
    }

    private static JsonNode parse(String line) {
        String value = line == null ? "" : line.strip();
        if (!value.startsWith("{")) return null;
        try { return MAPPER.readTree(value); } catch (Exception e) { return null; }
    }
}
