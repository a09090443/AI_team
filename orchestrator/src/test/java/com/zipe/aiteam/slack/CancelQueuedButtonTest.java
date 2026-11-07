package com.zipe.aiteam.slack;

import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.store.*;
import com.zipe.aiteam.task.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CancelQueuedButtonTest {
    @Test void cancelRequiresAllowedUserOriginalThreadAndRequester() {
        var props = mock(AiTeamProperties.class); when(props.allowedUsers()).thenReturn(Set.of("u", "other"));
        var orchestrator = mock(TaskOrchestrator.class);
        var slack = mock(SlackNotifier.class);
        var task = new Task("42", "c", "t", "u", "重複的需求");
        when(orchestrator.lookup("42")).thenReturn(Optional.of(task));
        var listener = new SlackListener(props, orchestrator, slack, mock(TaskStore.class), mock(AuditLog.class));

        listener.handleCancelQueued("stranger", "42", "c", "t");
        listener.handleCancelQueued("u", "42", "wrong", "t");
        listener.handleCancelQueued("u", "42", "c", "wrong");
        listener.handleCancelQueued("other", "42", "c", "t");
        verify(orchestrator, never()).cancelQueued(any(), any());
        verify(slack).post(eq(SlackNotifier.Identity.SYSTEM), eq("c"), eq("t"), contains("只有提出者"));

        listener.handleCancelQueued("u", "42", "c", "t");
        verify(orchestrator).cancelQueued(task, "u");
    }

    @Test void queuedBlocksCarryTheCancelAction() {
        var blocks = SlackBlocks.queuedBlocks("42", "排隊中");
        var actions = (com.slack.api.model.block.ActionsBlock) blocks.get(1);
        var button = (com.slack.api.model.block.element.ButtonElement) actions.getElements().getFirst();
        assertEquals(SlackBlocks.ACTION_CANCEL_QUEUED, button.getActionId());
        assertEquals("42", button.getValue());
        assertEquals("取消排隊", button.getText().getText());
    }

    @Test void typedCancelCommandIsUnwrapped() {
        assertEquals("取消排隊 #42", SlackListener.unwrapCommand("`取消排隊 #42`"));
    }
}
