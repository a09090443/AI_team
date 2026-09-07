package com.zipe.aiteam.agent;

import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.slack.SlackNotifier.Identity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class DirectStageTest {
    @Test void allRolesHaveReadOnlyDialogueAndOriginalModelRuntime() {
        var props = new AiTeamProperties();
        ReflectionTestUtils.setField(props, "modelQa", "sonnet");
        ReflectionTestUtils.setField(props, "runtimeCodeReview", "codex");
        for (Identity role : Identity.values()) {
            if (role == Identity.SYSTEM) continue;
            var stage = StageSpec.direct(role);
            assertThat(stage.writeScope()).isEqualTo(StageSpec.WriteScope.NONE);
            assertThat(stage.allowedTools()).containsExactly("Read", "Grep", "Glob");
            assertThat(stage.identity()).isEqualTo(role);
        }
        assertThat(props.roleModel("direct_qa")).isEqualTo("sonnet");
        assertThat(props.roleRuntime("direct_code_review")).isEqualTo("codex");
    }
    @Test void missingAppTokensPreserveWriteOnlyCompatibility() { new AiTeamProperties().validateRoleTokens(); }
    @Test void enablingRoleRequiresOwnBotTokenWithoutExposingToken() {
        var props = new AiTeamProperties(); ReflectionTestUtils.setField(props, "appTokenQa", "secret-app");
        assertThatThrownBy(props::validateRoleTokens).hasMessageContaining("SLACK_BOT_TOKEN_QA").hasMessageNotContaining("secret-app");
    }
    @Test void duplicateRoleAndMainAppTokenRejected() {
        var props = new AiTeamProperties();
        ReflectionTestUtils.setField(props, "slackAppToken", "secret-app");
        ReflectionTestUtils.setField(props, "appTokenQa", "secret-app");
        ReflectionTestUtils.setField(props, "slackBotTokenQa", "secret-bot");
        assertThatThrownBy(props::validateRoleTokens).hasMessageContaining("重複").hasMessageNotContaining("secret-app");
    }
}
