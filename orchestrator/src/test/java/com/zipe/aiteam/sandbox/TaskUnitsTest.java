package com.zipe.aiteam.sandbox;

import org.junit.jupiter.api.Test;
import java.nio.file.FileSystems;
import static org.assertj.core.api.Assertions.*;

class TaskUnitsTest {
    @Test void stopsOnlyThisTasksAgentAndVerificationUnitsWithoutBlocking() {
        assertThat(TaskUnits.command("ai-team-", "1017")).containsExactly(
                "systemctl", "--user", "--no-block", "stop", "ai-team-1017-*", "ai-team-check-1017-*");
    }
    @Test void instancePrefixScopesTheGlobToThisService() {
        assertThat(TaskUnits.command("ai-team-starters-", "1001")).containsExactly(
                "systemctl", "--user", "--no-block", "stop", "ai-team-starters-1001-*", "ai-team-starters-check-1001-*");
    }
    @Test void instancesNeverMatchEachOthersUnits() {
        // 同一台 VM 兩個專案服務的 #1001：任一方的 glob 都不可配到另一方的 unit。
        String[] units = {"ai-team-1001-BUILDER-1790985000", "ai-team-check-1001-6f1c",
                "ai-team-starters-1001-BUILDER-1790985000", "ai-team-starters-check-1001-6f1c",
                "ai-team-web-1001-TESTER-codex-1790985000", "ai-team-web-check-1001-6f1c"};
        for (String prefix : new String[]{"ai-team-", "ai-team-starters-", "ai-team-web-"}) {
            var globs = TaskUnits.command(prefix, "1001").subList(4, 6).stream()
                    .map(g -> FileSystems.getDefault().getPathMatcher("glob:" + g)).toList();
            for (String unit : units) {
                boolean own = unit.startsWith(prefix) && (prefix.equals("ai-team-")
                        ? unit.matches("ai-team-(check-)?1001-.*") : true);
                boolean matched = globs.stream().anyMatch(m -> m.matches(java.nio.file.Path.of(unit)));
                assertThat(matched).as(prefix + " vs " + unit).isEqualTo(own);
            }
        }
    }
    @Test void rejectsIdsAndPrefixesThatCouldWidenTheGlob() {
        for (String bad : new String[]{"*", "1017*", "10 17", "", null})
            assertThatThrownBy(() -> TaskUnits.command("ai-team-", bad)).isInstanceOf(IllegalArgumentException.class);
        for (String bad : new String[]{"ai-team-*", "", null, "ai-team-1-", "x-"})
            assertThatThrownBy(() -> TaskUnits.command(bad, "1017")).isInstanceOf(IllegalArgumentException.class);
        assertThat(TaskUnits.stop("ai-team-", "*")).isFalse();
    }
}
