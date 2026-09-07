package com.zipe.aiteam.slack;

import com.slack.api.model.event.AppMentionEvent;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.store.*;
import com.zipe.aiteam.task.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TriageRoutingTest {
    @Test void sameThreadFeedbackDispatchesWithoutCreatingTask() { exercise("t", "u", true); }
    @Test void wrongThreadDoesNotDispatch() { exercise("other", "u", false); }
    @Test void unauthorizedUserDoesNotDispatch() { exercise("t", "stranger", false); }
    void exercise(String thread, String user, boolean dispatch) {
        var props = mock(AiTeamProperties.class); when(props.allowedUsers()).thenReturn(Set.of("u")); when(props.channelId()).thenReturn("c");
        var store = mock(TaskStore.class); when(store.markEventSeen("event")).thenReturn(true);
        var app = mock(TaskOrchestrator.class); Task task = new Task("1011", "c", "t", "u", "mail");
        when(app.lookup("1011")).thenReturn(Optional.of(task));
        var listener = new SlackListener(props, app, mock(SlackNotifier.class), store, mock(AuditLog.class));
        var event = mock(AppMentionEvent.class); when(event.getChannel()).thenReturn("c"); when(event.getThreadTs()).thenReturn(thread);
        when(event.getUser()).thenReturn(user); when(event.getText()).thenReturn("<@BOT> 補充 #1011 已提供測試環境");
        ReflectionTestUtils.invokeMethod(listener, "handleMention", "event", event);
        if (dispatch) verify(app).requestTriage(task, "已提供測試環境", "u");
        else verify(app, never()).requestTriage(any(), any(), any());
        verify(app, never()).createAndSubmit(any(), any(), any(), any());
    }
}
