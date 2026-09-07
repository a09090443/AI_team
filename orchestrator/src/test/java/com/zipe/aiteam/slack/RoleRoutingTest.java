package com.zipe.aiteam.slack;

import com.slack.api.model.event.AppMentionEvent;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.store.*;
import com.zipe.aiteam.task.*;
import org.junit.jupiter.api.*;
import java.util.Set;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RoleRoutingTest {
    AiTeamProperties props = mock(AiTeamProperties.class);
    TaskOrchestrator app = mock(TaskOrchestrator.class);
    TaskStore store = mock(TaskStore.class);
    SlackNotifier slack = mock(SlackNotifier.class);
    SlackListener listener = new SlackListener(props, app, slack, store, mock(AuditLog.class));
    AppMentionEvent event = new AppMentionEvent();

    @BeforeEach void setup() {
        when(props.allowedUsers()).thenReturn(Set.of("U1", "BOTUSER")); when(props.channelId()).thenReturn("C1");
        when(store.markEventSeen(anyString())).thenReturn(true);
        when(slack.mention(SlackNotifier.Identity.QA)).thenReturn("<@QA>");
        when(slack.mention(SlackNotifier.Identity.BACKEND)).thenReturn("<@BE>");
        when(slack.isAgentUser("BOTUSER")).thenReturn(true);
        event.setUser("U1"); event.setChannel("C1"); event.setTs("t"); event.setText("<@QA> 要測哪些情境？");
    }
    @AfterEach void close() { listener.stop(); }
    void send() { listener.handleMention("event", event, SlackNotifier.Identity.QA); }
    @Test void allowedHumanDispatchesToExactRoleAndThread() {
        event.setThreadTs("parent"); send();
        verify(app).requestRole(SlackNotifier.Identity.QA, "C1", "parent", "U1", "要測哪些情境？");
        verify(app, never()).createAndSubmit(any(), any(), any(), any());
    }
    @Test void unauthorizedHumanRejected() { event.setUser("U2"); send(); verifyNoInteractions(app); }
    @Test void otherChannelIgnored() { event.setChannel("C2"); send(); verifyNoInteractions(app); }
    @Test void botMetadataRejectedEvenIfWhitelisted() { event.setBotId("B123"); send(); verifyNoInteractions(app, store); }
    @Test void agentUserRejectedEvenWithoutBotMetadata() { event.setUser("BOTUSER"); send(); verifyNoInteractions(app, store); }
    @Test void duplicateDeliveryDoesNotDispatch() { when(store.markEventSeen("QA:event")).thenReturn(false); send(); verifyNoInteractions(app); }
    @Test void missingEventKeyRejected() { listener.handleMention(null, event, SlackNotifier.Identity.QA); verifyNoInteractions(app, store); }
    @Test void multipleAgentTargetsRejected() { event.setText("<@QA> <@BE> 執行 修改功能"); send(); verifyNoInteractions(app); }
    @Test void newConfiguredRoleNeedsNoEnumOrSwitchCase() {
        var frontend = SlackNotifier.Identity.of("frontend", "Frontend Agent");
        when(props.identities()).thenReturn(java.util.List.of(SlackNotifier.Identity.SYSTEM, frontend));
        when(slack.mention(frontend)).thenReturn("<@FE>"); event.setText("<@FE> 這頁如何測試？");
        listener.handleMention("new-event", event, frontend);
        verify(app).requestRole(frontend, "C1", "t", "U1", "這頁如何測試？");
    }
}
