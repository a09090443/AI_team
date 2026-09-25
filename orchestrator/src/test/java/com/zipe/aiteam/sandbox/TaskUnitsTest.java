package com.zipe.aiteam.sandbox;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TaskUnitsTest {
    @Test void stopsOnlyThisTasksAgentAndVerificationUnitsWithoutBlocking() {
        assertThat(TaskUnits.command("1017")).containsExactly(
                "systemctl", "--user", "--no-block", "stop", "ai-team-1017-*", "ai-team-check-1017-*");
    }
    @Test void rejectsIdsThatCouldWidenTheGlob() {
        for (String bad : new String[]{"*", "1017*", "10 17", "", null})
            assertThatThrownBy(() -> TaskUnits.command(bad)).isInstanceOf(IllegalArgumentException.class);
        assertThat(TaskUnits.stop("*")).isFalse();
    }
}
