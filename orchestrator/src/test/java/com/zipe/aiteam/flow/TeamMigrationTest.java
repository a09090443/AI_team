package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class TeamMigrationTest {
    @TempDir Path tmp;
    ObjectMapper mapper = new ObjectMapper();
    TeamDefinition.Snapshot original;
    ObjectNode target;
    @BeforeEach void setup() throws Exception {
        Files.createDirectories(tmp.resolve(".git"));
        original = TeamCatalog.load(Path.of("teams/node-web.json"), key -> tmp.toString());
        target = mapper.valueToTree(original.definition());
    }
    TeamDefinition.Snapshot snapshot() throws Exception {
        return new TeamDefinition.Snapshot("new-revision", mapper.treeToValue(target, TeamDefinition.class), original.repository(), original.assets());
    }
    @Test void explicitCapabilityMigrationPreservesHistoryAndRejectsStaleToken() throws Exception {
        ((ObjectNode)target.path("project")).put("buildInstructions", "new instructions");
        var next = snapshot(); var preview = TeamMigration.preview(original, next);
        assertThat(preview.changes()).anyMatch(s -> s.contains("buildInstructions"));
        FlowRun flow = new FlowRun(original); flow.workspacePath = "original-workspace"; flow.baselineRevision = "baseline";
        flow.automaticHandoff = "old-blocker"; flow.automaticHandoffHops = 2;
        flow.automaticHandoffExtraBudget = 2; flow.automaticHandoffExtensionKeys.add("used-token");
        assertThatThrownBy(() -> TeamMigration.apply(flow, next, "wrong")).hasMessageContaining("版本過期");
        assertThat(flow.snapshot).isEqualTo(original);
        TeamMigration.apply(flow, next, preview.token());
        assertThat(flow.migrationHistory).containsExactly(original);
        assertThat(flow.workspacePath).isEqualTo("original-workspace");
        assertThat(flow.baselineRevision).isEqualTo("baseline");
        assertThat(flow.automaticHandoff).isNull();
        assertThat(flow.automaticHandoffHops).isZero();
        assertThat(flow.automaticHandoffExtraBudget).isZero();
        assertThat(flow.automaticHandoffExtensionKeys).containsExactly("used-token");
    }
    @Test void modelChangeWithinSameRuntimeIsListedAndStartsFreshSession() throws Exception {
        String before = original.definition().roles().get("builder").model();
        ((ObjectNode)target.path("roles").path("builder")).put("model", "haiku");
        var next = snapshot(); var preview = TeamMigration.preview(original, next);
        assertThat(preview.changes()).containsExactly("roles.builder.model：\"" + before + "\" → \"haiku\"");
        FlowRun flow = new FlowRun(original); flow.node = "implementation"; flow.activeNode = "implementation";
        TeamMigration.apply(flow, next, preview.token());
        assertThat(flow.snapshot.definition().roles().get("builder").model()).isEqualTo("haiku");
        // 下一步開新對話，不以新模型接續舊模型的 session。
        assertThat(flow.activeNode).isNull();
    }
    @Test void runtimeOrApprovalPolicyChangesCannotSneakIntoCapabilityMigration() throws Exception {
        ((ObjectNode)target.path("roles").path("builder")).put("runtime", "codex").put("model", "gpt-5.6-sol");
        assertThatThrownBy(() -> TeamMigration.preview(original, snapshot())).hasMessageContaining("非能力設定");
        target = mapper.valueToTree(original.definition());
        ((ObjectNode)target.path("project")).put("requirePlanApproval", false);
        assertThatThrownBy(() -> TeamMigration.preview(original, snapshot())).hasMessageContaining("非能力設定");
    }
    @Test void explicitWriteCapabilityRequiresNewTokenAndDoesNotChangeRetryLimits() throws Exception {
        ((ObjectNode)target.path("nodes").path("verification")).withArray("writablePaths").add("docs/build/**");
        var next = snapshot();
        var preview = TeamMigration.preview(original,next);
        assertThat(preview.changes()).anyMatch(c->c.contains("writablePaths"));
        var flow = new FlowRun(original); flow.visits.put("verification",3);
        TeamMigration.apply(flow,next,preview.token());
        assertThat(flow.visits.get("verification")).isEqualTo(3);
        ((ObjectNode)target.path("nodes").path("verification")).put("maxVisits",4);
        assertThatThrownBy(()->TeamMigration.preview(original,snapshot())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void autonomyPolicyIsMigratable() throws Exception {
        ((ObjectNode)target.path("project")).set("autonomy", mapper.readTree("{\"modelTimeoutRecoveries\":2,\"maxTimeoutSeconds\":3600,"
                + "\"verificationContinuations\":2,\"requirementChangeOptions\":true,\"channelCommandRouting\":true}"));
        var next = snapshot(); var preview = TeamMigration.preview(original, next);
        assertThat(preview.changes()).singleElement().asString().startsWith("project.autonomy：null → ");
        var flow = new FlowRun(original);
        TeamMigration.apply(flow, next, preview.token());
        assertThat(AutonomousRecovery.of(flow)).isNotNull();
    }
    @Test void nodeTimeoutIsMigratableResourceLimit() throws Exception {
        int before = original.definition().nodes().get("implementation").timeoutSeconds();
        ((ObjectNode)target.path("nodes").path("implementation")).put("timeoutSeconds", 3600);
        var next = snapshot(); var preview = TeamMigration.preview(original, next);
        assertThat(preview.changes()).containsExactly("nodes.implementation.timeoutSeconds：" + before + " → 3600");
        var flow = new FlowRun(original); flow.node = "implementation"; flow.visits.put("implementation", 1);
        TeamMigration.apply(flow, next, preview.token());
        assertThat(flow.snapshot.definition().nodes().get("implementation").timeoutSeconds()).isEqualTo(3600);
        assertThat(flow.visits.get("implementation")).isEqualTo(1);
    }
}
