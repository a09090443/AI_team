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

    @Test void deniedToolGrantedByPatternIsReportedAsCallShapeProblem() throws Exception {
        String reason = ToolTrust.blockingReason(role(List.of("Read", "Bash(git show *)")),
                denials("[{\"tool_name\":\"Bash\",\"tool_use_id\":\"t1\",\"tool_input\":{\"command\":\"for c in a b; do git show $c; done\"}}]"));

        assertThat(reason).contains("有配置但本次呼叫方式不符白名單").contains("Bash");
        assertThat(reason).doesNotContain("完全沒有這些工具");
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
}
