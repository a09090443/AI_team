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

    @Test void oneExtensionAlwaysLetsWorkContinueEvenAfterBudgetWasResetByConfigUpdate() {
        // 套用配置更新會把額外額度歸零、已用次數保留（#1017：已用 10、上限回到 6）。
        // 舊做法按一次只到 8，任務立刻又停；現在以已用次數為基準，按一次就能再處理 2 件。
        FlowRun flow = new FlowRun();
        flow.snapshot = new TeamDefinition.Snapshot("config", null, null, null);
        flow.automaticHandoff = "blocked-evidence";
        flow.automaticHandoffs = 10;
        flow.automaticHandoffExtraBudget = 0;
        assertThat(AutomaticHandoff.extensionGrantsRoom(flow)).isTrue();

        AutomaticHandoff.authorizeExtension(flow, AutomaticHandoff.extensionToken(flow, "head"), "head");

        assertThat(AutomaticHandoff.remaining(flow)).isEqualTo(2);
        assertThat(AutomaticHandoff.effectiveLimit(flow))
                .isEqualTo(AutomaticHandoff.LIMIT + AutomaticHandoff.MAX_EXTRA_BUDGET);
    }

    @Test void extensionNearTheHardCapGivesWhatIsLeftAndThenStopsBeingOffered() {
        FlowRun flow = new FlowRun();
        flow.snapshot = new TeamDefinition.Snapshot("config", null, null, null);
        flow.automaticHandoff = "blocked-evidence";
        int hardCap = AutomaticHandoff.LIMIT + AutomaticHandoff.MAX_EXTRA_BUDGET;

        flow.automaticHandoffs = hardCap - 1;
        AutomaticHandoff.authorizeExtension(flow, AutomaticHandoff.extensionToken(flow, "head"), "head");
        assertThat(AutomaticHandoff.remaining(flow)).isEqualTo(1);

        // 總上限不因延長而放寬：用到硬上限後不再提供延長，按了也會被拒。
        flow.automaticHandoffs = hardCap;
        assertThat(AutomaticHandoff.extensionGrantsRoom(flow)).isFalse();
        assertThatThrownBy(() -> AutomaticHandoff.authorizeExtension(flow, AutomaticHandoff.extensionToken(flow, "head2"), "head2"))
                .hasMessageContaining("硬上限");
    }
}
