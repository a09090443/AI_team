package com.zipe.aiteam.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 樣本格式取自 2026-10-18 VM 上的真實紀錄（dynamic-api #1001 Claude 實作、bestpartner #1042 Codex 驗收）。 */
class CommandRunsTest {
    @TempDir Path tmp;

    @Test void codexEventsKeepEveryCommandWithExitCodeAndUnwrapTheShell() {
        String jsonl = String.join("\n",
                "{\"type\":\"thread.started\",\"thread_id\":\"t1\"}",
                "{\"type\":\"item.started\",\"item\":{\"id\":\"i0\",\"type\":\"command_execution\",\"command\":\"/bin/bash -lc 'git status'\",\"status\":\"in_progress\"}}",
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"i0\",\"type\":\"command_execution\",\"command\":\"/bin/bash -lc 'git status'\",\"aggregated_output\":\"\",\"exit_code\":0,\"status\":\"completed\"}}",
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"i1\",\"type\":\"command_execution\",\"command\":\"/bin/bash -lc 'gradle -p backend --offline --no-daemon test'\",\"aggregated_output\":\"FAILED\",\"exit_code\":1,\"status\":\"failed\"}}",
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"i2\",\"type\":\"command_execution\",\"command\":\"/bin/bash -lc 'npm --prefix ui run test'\",\"aggregated_output\":\"\",\"exit_code\":null,\"status\":\"failed\"}}",
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"i3\",\"type\":\"agent_message\",\"text\":\"done\"}}",
                "not json");
        assertThat(CommandRuns.fromCodexEvents(jsonl)).containsExactly(
                new CommandRuns.CommandRun("git status", 0),
                new CommandRuns.CommandRun("gradle -p backend --offline --no-daemon test", 1),
                new CommandRuns.CommandRun("npm --prefix ui run test", -1));
    }

    @Test void codexDoubleQuotedWrapperIsUnescaped() {
        assertThat(CommandRuns.unwrapShell("/bin/bash -lc \"rg -n \\\"admin\\\" docs\"")).isEqualTo("rg -n \"admin\" docs");
        assertThat(CommandRuns.unwrapShell("/bin/bash -lc 'echo '\\''x'\\'''")).isEqualTo("echo 'x'");
        assertThat(CommandRuns.unwrapShell("gradle test")).isEqualTo("gradle test");
    }

    @Test void claudeTranscriptPairsBashUseWithResultAndSkipsEarlierTurns() {
        List<String> lines = List.of(
                entry("2026-10-18T12:20:00.000Z", "assistant", "[{\"type\":\"tool_use\",\"id\":\"old\",\"name\":\"Bash\",\"input\":{\"command\":\"gradle -p backend --no-daemon test -x buildFrontend\"}}]"),
                entry("2026-10-18T12:20:05.000Z", "user", "[{\"type\":\"tool_result\",\"tool_use_id\":\"old\",\"is_error\":true,\"content\":\"Exit code 1\\nold run\"}]"),
                entry("2026-10-18T12:30:00.000Z", "assistant", "[{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"Bash\",\"input\":{\"command\":\"gradle -p backend --no-daemon compileJava compileTestJava -x buildFrontend\"}},"
                        + "{\"type\":\"tool_use\",\"id\":\"r\",\"name\":\"Read\",\"input\":{\"file_path\":\"x\"}}]"),
                entry("2026-10-18T12:31:00.000Z", "user", "[{\"type\":\"tool_result\",\"tool_use_id\":\"a\",\"is_error\":false,\"content\":\"BUILD SUCCESSFUL\"},"
                        + "{\"type\":\"tool_result\",\"tool_use_id\":\"r\",\"content\":\"file\"}]"),
                entry("2026-10-18T12:31:10.000Z", "assistant", "[{\"type\":\"tool_use\",\"id\":\"b\",\"name\":\"Bash\",\"input\":{\"command\":\"gradle -p backend --no-daemon test -x buildFrontend\"}}]"),
                entry("2026-10-18T12:31:53.821Z", "user", "[{\"type\":\"tool_result\",\"tool_use_id\":\"b\",\"is_error\":true,\"content\":\"Exit code 1\\nTo honour the JVM settings\"}]"),
                entry("2026-10-18T12:32:00.000Z", "assistant", "[{\"type\":\"tool_use\",\"id\":\"c\",\"name\":\"Bash\",\"input\":{\"command\":\"false\"}}]"),
                entry("2026-10-18T12:32:01.000Z", "user", "[{\"type\":\"tool_result\",\"tool_use_id\":\"c\",\"is_error\":true,\"content\":[{\"type\":\"text\",\"text\":\"Exit code 2\"}]}]"));
        assertThat(CommandRuns.fromClaudeTranscript(lines, Instant.parse("2026-10-18T12:25:00Z"))).containsExactly(
                new CommandRuns.CommandRun("gradle -p backend --no-daemon compileJava compileTestJava -x buildFrontend", 0),
                new CommandRuns.CommandRun("gradle -p backend --no-daemon test -x buildFrontend", 1),
                new CommandRuns.CommandRun("false", 2));
    }

    @Test void claudeSessionIsFoundByIdUnderAnyProjectDirectory() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("projects/-home-agent-ws-task-1"));
        Files.write(dir.resolve("abc-123.jsonl"), List.of(
                entry("2026-10-18T12:31:10.000Z", "assistant", "[{\"type\":\"tool_use\",\"id\":\"b\",\"name\":\"Bash\",\"input\":{\"command\":\"npm test\"}}]"),
                entry("2026-10-18T12:31:20.000Z", "user", "[{\"type\":\"tool_result\",\"tool_use_id\":\"b\",\"is_error\":false,\"content\":\"ok\"}]")));
        assertThat(CommandRuns.fromClaudeSession(tmp, "abc-123", null)).containsExactly(new CommandRuns.CommandRun("npm test", 0));
        assertThat(CommandRuns.fromClaudeSession(tmp, "missing", null)).isEmpty();
        assertThat(CommandRuns.fromClaudeSession(tmp, "../escape", null)).isEmpty();
    }

    private static String entry(String at, String type, String content) {
        return "{\"type\":\"" + type + "\",\"timestamp\":\"" + at + "\",\"message\":{\"role\":\"" + type + "\",\"content\":" + content + "}}";
    }
}
