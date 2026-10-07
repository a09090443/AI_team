package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zipe.aiteam.runtime.AgentRuntime;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** #1014：Claude Code 寫不了 `.claude/`，允許的內容類路徑改派 Codex，行為類路徑一律禁止，並由系統事後核對。 */
class ProtectedPathFallbackTest {
    @TempDir Path tmp;
    ObjectMapper mapper = new ObjectMapper();
    AgentRuntime claude = mock(AgentRuntime.class);
    TeamDefinition.Fallback fallback = new TeamDefinition.Fallback("builder_codex", List.of(".claude/rules/**", ".claude/skills/**"));

    @BeforeEach void setup() throws Exception {
        Files.createDirectories(tmp.resolve(".git"));
        when(claude.protectedWritePaths()).thenReturn(List.of(".claude/", ".git/", ".mcp.json"));
        when(claude.isProtectedWrite(anyString())).thenCallRealMethod();
    }

    ObjectNode bestpartner() throws Exception {
        return (ObjectNode) mapper.readTree(Files.readString(Path.of("teams/bestpartner.json")));
    }
    TeamDefinition definition(ObjectNode json) throws Exception { return mapper.treeToValue(json, TeamDefinition.class); }

    @Test void extractsPathLikeTokensFromRequirementText() {
        assertThat(ProtectedPathFallback.mentionedPaths("把 `.claude/rules/api-endpoints.md` 第 49 行改成 admin，依據 bestpartner-service/src/A.kt。"))
                .contains(".claude/rules/api-endpoints.md", "bestpartner-service/src/A.kt");
    }

    @Test void reassignsAllowedPathsAndLeavesTheRestToTheOriginalFlow() throws Exception {
        var node = definition(bestpartner()).nodes().get("implementation");
        var flow = new FlowRun(TeamCatalog.load(Path.of("teams/node-web.json"), key -> tmp.toString()));
        assertThat(ProtectedPathFallback.needed(flow, node, "把 `.claude/rules/api-endpoints.md` 的 all 改成 admin", claude))
                .contains(new ProtectedPathFallback.Need(List.of(".claude/rules/api-endpoints.md"), List.of()));
        // 行為類設定不交出去，但同一需求裡允許的規則檔照樣改派；settings 留給原角色，真要改會被拒絕而停下問人。
        assertThat(ProtectedPathFallback.needed(flow, node, "改 .claude/rules/a.md 並在 .claude/settings.json 加 hook", claude))
                .contains(new ProtectedPathFallback.Need(List.of(".claude/rules/a.md"), List.of(".claude/settings.json")));
        // #1029：只要求 `.claude/CLAUDE.md`「維持不變」，不能讓 `.claude/rules/` 的改派整個失效。
        assertThat(ProtectedPathFallback.needed(flow, node,
                "更新 .claude/rules/tech-stack-and-versions.md 與 .claude/rules/gradle-conventions.md；.claude/CLAUDE.md 的 import 維持不變", claude))
                .contains(new ProtectedPathFallback.Need(
                        List.of(".claude/rules/tech-stack-and-versions.md", ".claude/rules/gradle-conventions.md"), List.of(".claude/CLAUDE.md")));
        // 只有允許範圍外的受保護路徑：不改派。
        assertThat(ProtectedPathFallback.needed(flow, node, "在 .claude/settings.json 加 hook", claude)).isEmpty();
        // 沒有受保護路徑：照常由原角色實作。
        assertThat(ProtectedPathFallback.needed(flow, node, "修改 bestpartner-service/src/main/A.kt", claude)).isEmpty();
        // 未配置 fallback 的節點。
        var plain = node.withRole("builder");
        var noFallback = new TeamDefinition.Node(plain.kind(), plain.role(), plain.schema(), plain.scope(), plain.gate(), plain.workspace(),
                plain.writablePaths(), plain.plan(), plain.next(), plain.blocked(), plain.routes(), plain.requires(),
                plain.maxVisits(), plain.timeoutSeconds(), plain.maxTurns(), plain.instructions());
        assertThat(ProtectedPathFallback.needed(flow, noFallback, "改 .claude/rules/a.md", claude)).isEmpty();
    }

    @Test void auditRejectsBehaviourFilesAndUnallowedProtectedPaths() {
        assertThat(ProtectedPathFallback.violations(List.of(".claude/rules/a.md", "docs/x.md", "src/A.kt"), fallback, claude)).isEmpty();
        assertThat(ProtectedPathFallback.violations(List.of(".claude/rules/a.md", ".claude/settings.json"), fallback, claude))
                .containsExactly(".claude/settings.json");
        assertThat(ProtectedPathFallback.violations(List.of(".claude/hooks/pre.sh", ".mcp.json", ".github/workflows/ci.yml"), fallback, claude))
                .hasSize(3);
        // 原 runtime 不可用時仍擋禁止清單。
        assertThat(ProtectedPathFallback.violations(List.of(".claude/settings.local.json"), fallback, null)).hasSize(1);
    }

    @Test void configurationIsValidatedStrictly() throws Exception {
        TeamCatalog.validate(definition(bestpartner()));
        for (String bad : new String[]{".claude/**", ".claude/settings/**", ".git/**", ".claude/*/x/**", "../x/**", ".claude/rules"}) {
            var json = bestpartner();
            var fb = (ObjectNode) json.path("nodes").path("implementation").path("protectedPathFallback");
            fb.putArray("paths").add(bad);
            assertThatThrownBy(() -> TeamCatalog.validate(definition(json))).as(bad).hasMessageContaining("protectedPathFallback");
        }
        var sameRuntime = bestpartner();
        ((ObjectNode) sameRuntime.path("nodes").path("implementation").path("protectedPathFallback")).put("role", "architect");
        assertThatThrownBy(() -> TeamCatalog.validate(definition(sameRuntime))).hasMessageContaining("不同 runtime");
        var missing = bestpartner();
        ((ObjectNode) missing.path("nodes").path("implementation").path("protectedPathFallback")).put("role", "nobody");
        assertThatThrownBy(() -> TeamCatalog.validate(definition(missing))).hasMessageContaining("不存在");
        var wrongNode = bestpartner();
        ((ObjectNode) wrongNode.path("nodes").path("verification")).set("protectedPathFallback",
                wrongNode.path("nodes").path("implementation").path("protectedPathFallback").deepCopy());
        assertThatThrownBy(() -> TeamCatalog.validate(definition(wrongNode))).hasMessageContaining("只能設在實作節點");
    }

    @Test void reassignedImplementerIsToldItsBoundaries() {
        assertThat(ProtectedPathFallback.notice(fallback)).contains(".claude/rules/**", ".claude/settings", "不要嘗試 commit");
    }
}
