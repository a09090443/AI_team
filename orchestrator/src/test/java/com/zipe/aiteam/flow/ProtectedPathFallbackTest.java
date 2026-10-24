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

    ObjectNode fullstack() throws Exception {
        return (ObjectNode) mapper.readTree(Files.readString(Path.of("teams/gradle-fullstack.json")));
    }
    TeamDefinition definition(ObjectNode json) throws Exception { return mapper.treeToValue(json, TeamDefinition.class); }

    @Test void extractsPathLikeTokensFromRequirementText() {
        assertThat(ProtectedPathFallback.mentionedPaths("把 `.claude/rules/api-endpoints.md` 第 49 行改成 admin，依據 backend/src/A.kt。"))
                .contains(".claude/rules/api-endpoints.md", "backend/src/A.kt");
    }

    @Test void delegatesOnlyExplicitPlanPathsNotMentionsOrOtherReceipts() throws Exception {
        var flow = new FlowRun(TeamCatalog.load(Path.of("teams/gradle-fullstack.json"), key -> tmp.toString()));
        var node = flow.snapshot.definition().nodes().get("implementation");
        flow.receipts.put("intake", mapper.createObjectNode().put("summary", "不要修改 .claude/rules/a.md"));
        assertThat(ProtectedPathFallback.needed(flow, node, "參考 .claude/rules/a.md", claude)).isEmpty();
        ObjectNode plan = mapper.createObjectNode();
        plan.putArray("protected_path_changes").add(".claude/rules/b.md");
        flow.receipts.put("plan", plan);
        assertThat(ProtectedPathFallback.needed(flow, node, "參考 .claude/rules/a.md", claude))
                .contains(new ProtectedPathFallback.Need(List.of(".claude/rules/b.md"), List.of()));
        assertThat(ProtectedPathFallback.partViolations(List.of(".claude/rules/b.md", ".claude/rules/a.md"),
                fallback, ProtectedPathFallback.plannedPaths(flow, node))).containsExactly(".claude/rules/a.md");
        plan.putArray("protected_path_changes");
        assertThat(ProtectedPathFallback.needed(flow, node, "改 .claude/rules/a.md", claude)).isEmpty();
    }

    @Test void rejectsUnsafeOrContradictoryModificationPlans() {
        for (String path : List.of("../a.md", ".claude/rules/**", ".claude/settings.json", "/tmp/a.md", ".claude//a.md")) {
            ObjectNode plan = mapper.createObjectNode();
            plan.putArray("protected_path_changes").add(path);
            assertThat(ProtectedPathFallback.planReason(plan)).as(path).isNotNull();
        }
        ObjectNode plan = mapper.createObjectNode();
        plan.putArray("protected_path_changes").add(".claude/rules/a.md");
        plan.putArray("scenarios").addObject().putArray("unchanged_paths").add(".claude/rules/a.md");
        assertThat(ProtectedPathFallback.planReason(plan)).contains("衝突");
    }

    @Test void executableFilesInDocsCannotSkipIndependentVerification() {
        for (String path : List.of("docs/init.sql", "docs/install.sh", "docs/config.yaml", "docs/app.js", "docs/sidebars.ts"))
            assertThat(DocOnlyChanges.qualifies(List.of("docs/**"), List.of(path))).as(path).isFalse();
        assertThat(DocOnlyChanges.qualifies(List.of("docs/**"), List.of("docs/install.md"))).isTrue();
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
        TeamCatalog.validate(definition(fullstack()));
        for (String bad : new String[]{".claude/**", ".claude/settings/**", ".git/**", ".claude/*/x/**", "../x/**", ".claude/rules"}) {
            var json = fullstack();
            var fb = (ObjectNode) json.path("nodes").path("implementation").path("protectedPathFallback");
            fb.putArray("paths").add(bad);
            assertThatThrownBy(() -> TeamCatalog.validate(definition(json))).as(bad).hasMessageContaining("protectedPathFallback");
        }
        var sameRuntime = fullstack();
        ((ObjectNode) sameRuntime.path("nodes").path("implementation").path("protectedPathFallback")).put("role", "architect");
        assertThatThrownBy(() -> TeamCatalog.validate(definition(sameRuntime))).hasMessageContaining("不同 runtime");
        var missing = fullstack();
        ((ObjectNode) missing.path("nodes").path("implementation").path("protectedPathFallback")).put("role", "nobody");
        assertThatThrownBy(() -> TeamCatalog.validate(definition(missing))).hasMessageContaining("不存在");
        var wrongNode = fullstack();
        ((ObjectNode) wrongNode.path("nodes").path("verification")).set("protectedPathFallback",
                wrongNode.path("nodes").path("implementation").path("protectedPathFallback").deepCopy());
        assertThatThrownBy(() -> TeamCatalog.validate(definition(wrongNode))).hasMessageContaining("只能設在實作節點");
    }

    @Test void reassignedImplementerIsToldItsBoundaries() {
        assertThat(ProtectedPathFallback.notice(fallback)).contains(".claude/rules/**", ".claude/settings", "不要嘗試 commit");
    }
}
