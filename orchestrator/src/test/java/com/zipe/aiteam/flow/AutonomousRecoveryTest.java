package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zipe.aiteam.task.FailureReport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AutonomousRecoveryTest {
    @TempDir Path tmp;
    ObjectMapper mapper = new ObjectMapper();
    FlowRun flow;

    @BeforeEach void setup() throws Exception {
        Files.createDirectories(tmp.resolve(".git"));
        flow = new FlowRun(TeamCatalog.load(Path.of("teams/node-web.json"), key -> tmp.toString()));
    }
    void autonomy(String json) throws Exception {
        ObjectNode definition = mapper.valueToTree(flow.snapshot.definition());
        ((ObjectNode) definition.path("project")).set("autonomy", mapper.readTree(json));
        flow.snapshot = new TeamDefinition.Snapshot("rev", mapper.treeToValue(definition, TeamDefinition.class),
                flow.snapshot.repository(), flow.snapshot.assets());
    }
    static final String ENABLED = "{\"modelTimeoutRecoveries\":2,\"maxTimeoutSeconds\":3600,\"verificationContinuations\":2,"
            + "\"requirementChangeOptions\":true,\"channelCommandRouting\":true}";

    @Test void disabledProjectsNeverRecoverAutomatically() {
        assertThat(AutonomousRecovery.claimTimeoutRecovery(flow, "implementation", "h:clean")).isZero();
        assertThat(AutonomousRecovery.claimVerificationContinuation(flow, "h", 0, 3)).isZero();
        assertThat(AutonomousRecovery.timeBudgetNotice(flow, 1800)).isEmpty();
        assertThat(flow.systemRetries).isEmpty();
    }

    /** 只開延後驗收的團隊（例如 maven-library 範例）：其他自主恢復與時限提示都不啟用。 */
    @Test void deferralOnlyAutonomyKeepsEveryRecoveryOff() throws Exception {
        autonomy("{\"modelTimeoutRecoveries\":0,\"maxTimeoutSeconds\":0,\"verificationContinuations\":0,"
                + "\"requirementChangeOptions\":false,\"channelCommandRouting\":false,\"deferUnverifiable\":true}");
        assertThat(AutonomousRecovery.claimTimeoutRecovery(flow, "implementation", "h:clean")).isZero();
        assertThat(AutonomousRecovery.claimVerificationContinuation(flow, "h", 0, 3)).isZero();
        assertThat(AutonomousRecovery.timeBudgetNotice(flow, 1800)).isEmpty();
        assertThat(AutonomousRecovery.requirementChangeOptions(flow)).isFalse();
        assertThat(AutonomousRecovery.channelCommandRouting(flow.snapshot.definition())).isFalse();
        assertThat(DeferredAcceptance.enabled(flow)).isTrue();
        assertThat(flow.systemRetries).isEmpty();
    }

    @Test void timeoutRecoveryIsBoundedPerTaskAndNeedsProgressBetweenRounds() throws Exception {
        autonomy(ENABLED);
        var node = flow.snapshot.definition().nodes().get("implementation");
        assertThat(AutonomousRecovery.timeoutSeconds(flow, "implementation", node)).isEqualTo(1800);
        assertThat(AutonomousRecovery.claimTimeoutRecovery(flow, "implementation", "h1:clean")).isEqualTo(1);
        assertThat(AutonomousRecovery.timeoutSeconds(flow, "implementation", node)).isEqualTo(3600);
        // 同一工作區狀態再逾時＝沒有進展：不再接續。
        assertThat(AutonomousRecovery.claimTimeoutRecovery(flow, "implementation", "h1:clean")).isZero();
        assertThat(AutonomousRecovery.claimTimeoutRecovery(flow, "implementation", "h1:abc")).isEqualTo(2);
        // 每任務上限 2 次，用完就停給人。
        assertThat(AutonomousRecovery.claimTimeoutRecovery(flow, "verification", "h2:clean")).isZero();
    }

    @Test void extendedLimitNeverShortensANodeThatAlreadyHasMoreTime() throws Exception {
        autonomy("{\"modelTimeoutRecoveries\":1,\"maxTimeoutSeconds\":600,\"verificationContinuations\":0,"
                + "\"requirementChangeOptions\":false,\"channelCommandRouting\":false}");
        var node = flow.snapshot.definition().nodes().get("implementation");
        AutonomousRecovery.claimTimeoutRecovery(flow, "implementation", "h:clean");
        assertThat(AutonomousRecovery.timeoutSeconds(flow, "implementation", node)).isEqualTo(1800);
    }

    @Test void verificationContinuesOnlyWhenTheRoundPassedSomethingNew() throws Exception {
        autonomy(ENABLED);
        assertThat(AutonomousRecovery.claimVerificationContinuation(flow, "h", 2, 2)).isZero();
        assertThat(AutonomousRecovery.claimVerificationContinuation(flow, "h", 0, 2)).isEqualTo(1);
        assertThat(AutonomousRecovery.claimVerificationContinuation(flow, "h", 2, 3)).isEqualTo(2);
        assertThat(AutonomousRecovery.claimVerificationContinuation(flow, "h", 3, 4)).isZero();
        // 新的 commit 有自己的續驗額度。
        assertThat(AutonomousRecovery.claimVerificationContinuation(flow, "h2", 0, 1)).isEqualTo(1);
    }

    @Test void requirementChangeActionOnlyExistsInTheSchemaWhenEnabled() throws Exception {
        String schema = "{\"type\":\"object\",\"properties\":{}}";
        assertThat(FailureReport.extendSchema(schema)).doesNotContain("change_requirements");
        assertThat(mapper.readTree(FailureReport.extendSchema(schema, true)).path("properties").path("human_decision")
                .path("properties").path("options").path("items").path("properties").path("action").path("enum").toString())
                .contains("decide", "close", "successor", "publish", "change_requirements");
    }
}
