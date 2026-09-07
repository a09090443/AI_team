package com.zipe.aiteam.slack;

import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.flow.TeamDefinition.Role;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfiguredMentionTest {
    AiTeamProperties props = mock(AiTeamProperties.class);
    SlackNotifier notifier = spy(new SlackNotifier(props));
    Role frozen = new Role("Custom Reviewer", "codex", "", "prompt.md", "OLD_REVIEWER_TOKEN", null, List.of("Read"));
    @Test void resolvesIdentityUsingFrozenTokenReferenceNotCurrentRoleName() {
        when(props.secretReference("OLD_REVIEWER_TOKEN")).thenReturn("fixture-old-token");
        doReturn("UOLD123").when(notifier).configuredUserId("fixture-old-token");
        assertThat(notifier.mentionConfigured(frozen)).isEqualTo("<@UOLD123>");
        verify(props).secretReference("OLD_REVIEWER_TOKEN");
    }
    @Test void missingTokenExplicitlyReportsThatNoNotificationWasCreated() {
        when(props.secretReference(anyString())).thenReturn("");
        assertThat(notifier.mentionConfigured(frozen)).contains("Custom Reviewer", "無法建立 @通知").doesNotContain("<@");
        verify(notifier, never()).configuredUserId(anyString());
    }
    @Test void unverifiedOrMalformedUserIdCannotBecomeFakeMention() {
        when(props.secretReference(anyString())).thenReturn("fixture-token");
        doReturn("not-an-id").when(notifier).configuredUserId("fixture-token");
        assertThat(notifier.mentionConfigured(frozen)).contains("無法建立 @通知").doesNotContain("<@");
    }
}
