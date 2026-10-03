package com.zipe.aiteam.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class AiTeamPropertiesInstanceTest {
    @Test void blankInstanceKeepsLegacyUnitNames() {
        assertThat(new AiTeamProperties().unitPrefix()).isEqualTo("ai-team-");
        for (String blank : new String[]{"", "  "}) assertThat(AiTeamProperties.unitPrefixFor(blank)).isEqualTo("ai-team-");
    }
    @Test void instanceIsAddedToThePrefix() {
        AiTeamProperties props = new AiTeamProperties();
        ReflectionTestUtils.setField(props, "instance", " starters ");
        assertThat(props.unitPrefix()).isEqualTo("ai-team-starters-");
        assertThat(AiTeamProperties.unitPrefixFor("web2")).isEqualTo("ai-team-web2-");
    }
    @Test void rejectsNamesThatCouldCollideWithTaskIdsOrLegacyPrefixes() {
        for (String bad : new String[]{"Starters", "1web", "my-proj", "my_proj", "a*", "abcdefghijklmnopq",
                "check", "preflight", "selftest", "codex"})
            assertThatThrownBy(() -> AiTeamProperties.unitPrefixFor(bad))
                    .as(bad).isInstanceOf(IllegalStateException.class).hasMessageContaining("AI_TEAM_INSTANCE");
    }
}
