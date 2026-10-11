package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ToolTrustTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private TeamDefinition.Role role(List<String> tools) {
        return new TeamDefinition.Role("PM Agent", "claude-code", "opus", "prompts/intake.md", "BOT", "APP", tools);
    }

    private JsonNode denials(String json) throws Exception { return mapper.readTree(json); }

    @Test void noDenialsMeansNoBlock() throws Exception {
        assertThat(ToolTrust.blockingReason(role(List.of("Read")), null)).isNull();
        assertThat(ToolTrust.blockingReason(role(List.of("Read")), denials("[]"))).isNull();
    }

    @Test void deniedToolAbsentFromRoleIsReportedAsConfigurationGap() throws Exception {
        // #1014：triage 用的 coordinator 完全沒有 Bash，卻產出 git 證據。
        String reason = ToolTrust.blockingReason(role(List.of("Read", "Grep", "Glob")),
                denials("[{\"tool_name\":\"Bash\",\"tool_use_id\":\"t1\",\"tool_input\":{\"command\":\"git show --stat 814c9b1\"}}]"));

        assertThat(reason).contains("報告卻回報未受阻").contains("完全沒有這些工具").contains("Bash");
        assertThat(reason).doesNotContain("有配置但本次呼叫方式不符");
    }

    @Test void callShapeMismatchIsRecordedButDoesNotInvalidateTheConclusion() throws Exception {
        // #1016：分流有 git 權限，只是用了 `git -C <路徑> log`。它用 Read／Grep 做出的判斷仍然成立；
        // 作廢只會讓它每輪重跑到用完次數。
        var withGit = role(List.of("Read", "Bash(git show *)"));
        var denied = denials("[{\"tool_name\":\"Bash\",\"tool_use_id\":\"t1\","
                + "\"tool_input\":{\"command\":\"git -C /work log --oneline -5\"}}]");

        assertThat(ToolTrust.blockingReason(withGit, denied)).isNull();
        assertThat(ToolTrust.caveat(withGit, denied)).contains("呼叫方式不符白名單樣式").contains("Bash")
                .contains("未因此作廢");
    }

    @Test void noCaveatWhenTheToolIsMissingEntirely() throws Exception {
        var noBash = role(List.of("Read", "Grep", "Glob"));
        var denied = denials("[{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"git log\"}}]");

        assertThat(ToolTrust.caveat(noBash, denied)).isNull();
        assertThat(ToolTrust.blockingReason(noBash, denied)).contains("完全沒有這些工具");
    }

    @Test void roleWithoutToolListFailsClosed() throws Exception {
        assertThat(ToolTrust.blockingReason(role(null),
                denials("[{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"ls\"}}]")))
                .contains("完全沒有這些工具");
    }

    @Test void missingToolNameStillProducesAReason() throws Exception {
        assertThat(ToolTrust.blockingReason(role(List.of("Read")), denials("[{\"tool_input\":{\"command\":\"ls\"}}]")))
                .contains("報告卻回報未受阻");
    }

    @Test void commandFailureIsNeitherMissingPermissionNorWhitelistMismatch() throws Exception {
        var failed = denials("""
                [{"tool_name":"command_execution","failure_kind":"COMMAND_FAILURE",
                  "reason":"exit_code=2; new blank line at EOF"}]
                """);
        assertThat(ToolTrust.blockingReason(role(List.of("Read")), failed)).isNull();
        assertThat(ToolTrust.caveat(role(List.of("command_execution")), failed)).isNull();
    }

    @Test void commandFailureDoesNotHideAnActualDenial() throws Exception {
        var mixed = denials("""
                [{"tool_name":"command_execution","failure_kind":"COMMAND_FAILURE"},
                 {"tool_name":"Bash","failure_kind":"PERMISSION_DENIED"}]
                """);
        assertThat(ToolTrust.blockingReason(role(List.of("Read")), mixed)).contains("Bash");
        assertThat(ToolTrust.blockingReason(role(List.of("Read")), denials("""
                [{"tool_name":"command_execution","failure_kind":"PERMISSION_DENIED"}]
                """))).contains("完全沒有這些工具");
    }
}
