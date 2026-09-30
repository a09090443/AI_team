package com.zipe.aiteam.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1021：實作節點改 `.claude/rules/project-overview.md` 被 dontAsk 拒絕，
 * 稽核只寫「未提供具體拒絕理由」，分流就提議開放權限 —— 那在 Claude Code 是開放不了的。
 */
class ClaudeCodeProtectedPathTest {
    private final ClaudeCodeRuntime claude = new ClaudeCodeRuntime(null, null);

    @Test void officialProtectedPathsAreRecognizedAnywhereInTheWorkspace() {
        assertThat(claude.isProtectedWrite("/home/vboxuser/ai-team/workspaces/task-1021/.claude/rules/project-overview.md")).isTrue();
        assertThat(claude.isProtectedWrite(".claude/settings.json")).isTrue();
        assertThat(claude.isProtectedWrite("starters_example/.mvn/wrapper/maven-wrapper.properties")).isTrue();
        assertThat(claude.isProtectedWrite("gradle/wrapper/gradle-wrapper.properties")).isTrue();
        assertThat(claude.isProtectedWrite("a\\.git\\config")).isTrue();
        assertThat(claude.isProtectedWrite(".config/git/ignore")).isTrue();
        assertThat(claude.isProtectedWrite(".mcp.json")).isTrue();
    }

    @Test void ordinaryFilesAndTheWorktreeExceptionAreNotProtected() {
        assertThat(claude.isProtectedWrite(".claude/worktrees/feature/src/Main.java")).isFalse();
        assertThat(claude.isProtectedWrite("src/main/java/Main.java")).isFalse();
        assertThat(claude.isProtectedWrite("doc-site/docs/claude.md")).isFalse();
        assertThat(claude.isProtectedWrite("AGENTS.md")).isFalse();
        assertThat(claude.isProtectedWrite("my.claude/x.md")).isFalse();
        assertThat(claude.isProtectedWrite(".config/other/x")).isFalse();
        assertThat(claude.isProtectedWrite("")).isFalse();
        assertThat(claude.isProtectedWrite(null)).isFalse();
    }

    @Test void runtimesWithoutSuchLimitsDeclareNothing() {
        AgentRuntime plain = new CodexRuntime(null, null);
        assertThat(plain.protectedWritePaths()).isEmpty();
        assertThat(plain.isProtectedWrite(".claude/rules/x.md")).isFalse();
    }

    @Test void protectedPathDenialsCarryTheRealReasonIntoDiagnostics() throws Exception {
        var denials = new ObjectMapper().readTree("""
                [{"tool_name":"Edit","tool_use_id":"toolu_016n",
                  "tool_input":{"file_path":"/home/vboxuser/ai-team/workspaces/task-1021/.claude/rules/project-overview.md",
                                "old_string":"3.5.x","new_string":"4.0.8"}},
                 {"tool_name":"Bash","tool_use_id":"toolu_2","tool_input":{"command":"ls /tmp"}},
                 {"tool_name":"Edit","tool_use_id":"toolu_3","tool_input":{"file_path":"src/Main.java"}}]""");

        String detail = Diagnostics.toolFailures(claude.annotateDenials(denials));

        String first = detail.substring(0, detail.indexOf("toolu_2"));
        assertThat(first).contains("受保護路徑").contains("allow 規則也開放不了").doesNotContain("未提供具體拒絕理由");
        assertThat(detail.substring(detail.indexOf("toolu_2"))).contains("未提供具體拒絕理由").doesNotContain("受保護路徑");
    }
}
