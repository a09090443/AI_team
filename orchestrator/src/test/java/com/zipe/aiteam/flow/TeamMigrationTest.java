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
        assertThatThrownBy(() -> TeamMigration.apply(flow, next, "wrong")).hasMessageContaining("版本過期");
        assertThat(flow.snapshot).isEqualTo(original);
        TeamMigration.apply(flow, next, preview.token());
        assertThat(flow.migrationHistory).containsExactly(original);
        assertThat(flow.workspacePath).isEqualTo("original-workspace");
        assertThat(flow.baselineRevision).isEqualTo("baseline");
    }
    @Test void modelOrApprovalPolicyChangesCannotSneakIntoCapabilityMigration() throws Exception {
        ((ObjectNode)target.path("roles").path("builder")).put("model", "other");
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
}
