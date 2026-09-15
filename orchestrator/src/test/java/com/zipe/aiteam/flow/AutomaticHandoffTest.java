package com.zipe.aiteam.flow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AutomaticHandoffTest {
    @Test void normalizesLegacyHopKeysIntoUniqueIncidents() {
        FlowRun flow = new FlowRun();
        String first = "a".repeat(64), second = "b".repeat(64);
        flow.automaticHandoffKeys.add(first + ":verification:7");
        flow.automaticHandoffKeys.add(first + ":triage:5");
        flow.automaticHandoffKeys.add(second + ":verification:8");
        flow.automaticHandoffKeys.add(second + ":triage:6");
        flow.automaticHandoffKeys.add("incident:" + "c".repeat(64));
        flow.automaticHandoffKeys.add("incident:" + "d".repeat(64));
        flow.automaticHandoffs = 6;

        var result = AutomaticHandoff.normalizeLegacyKeys(flow);

        assertThat(result.changed()).isTrue();
        assertThat(result.before()).isEqualTo(6);
        assertThat(result.after()).isEqualTo(4);
        assertThat(flow.automaticHandoffKeys).containsExactly(
                "incident:" + first, "incident:" + second,
                "incident:" + "c".repeat(64), "incident:" + "d".repeat(64));
    }

    @Test void unknownLegacyFormatNeverLowersExistingBudgetUsage() {
        FlowRun flow = new FlowRun();
        flow.automaticHandoffKeys.add("unknown-old-key");
        flow.automaticHandoffs = 6;

        AutomaticHandoff.normalizeLegacyKeys(flow);

        assertThat(flow.automaticHandoffs).isEqualTo(6);
        assertThat(flow.automaticHandoffKeys).containsExactly("unknown-old-key");
    }

    @Test void missingHistoricalKeysNeverLowerExistingBudgetUsage() {
        FlowRun flow = new FlowRun();
        flow.automaticHandoffKeys.add("incident:known");
        flow.automaticHandoffs = 4;

        AutomaticHandoff.normalizeLegacyKeys(flow);

        assertThat(flow.automaticHandoffs).isEqualTo(4);
    }

    @Test void extensionIsBoundedAndCannotBeReplayed() {
        FlowRun flow = new FlowRun();
        flow.snapshot = new TeamDefinition.Snapshot("config", null, null, null);
        flow.automaticHandoff = "blocked-evidence";
        flow.automaticHandoffs = AutomaticHandoff.LIMIT;
        String token = AutomaticHandoff.extensionToken(flow, "head");

        AutomaticHandoff.authorizeExtension(flow, token, "head");

        assertThat(AutomaticHandoff.effectiveLimit(flow)).isEqualTo(8);
        assertThatThrownBy(() -> AutomaticHandoff.authorizeExtension(flow, token, "head"))
                .hasMessageContaining("不能重放");
        assertThatThrownBy(() -> AutomaticHandoff.authorizeExtension(flow, token, "other-head"))
                .hasMessageContaining("不符");
    }
}
