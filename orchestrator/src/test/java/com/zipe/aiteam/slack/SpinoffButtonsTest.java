package com.zipe.aiteam.slack;

import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.element.ButtonElement;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpinoffButtonsTest {
    @Test void buttonsHaveUniqueActionIdsAndAtMostFive() {
        var blocks = SpinoffButtons.blocks("1006", "發現", 7);
        assertThat(blocks).hasSize(2);
        var buttons = ((ActionsBlock) blocks.get(1)).getElements();
        assertThat(buttons).hasSize(SpinoffButtons.MAX_BUTTONS);
        var ids = new HashSet<String>();
        for (var element : buttons) {
            var button = (ButtonElement) element;
            assertThat(SpinoffButtons.ACTION_PATTERN.matcher(button.getActionId()).matches()).isTrue();
            assertThat(ids.add(button.getActionId())).isTrue();
        }
        assertThat(((ButtonElement) buttons.get(0)).getValue()).isEqualTo("1006|1");
    }

    @Test void noFindingsMeansNoButtonRow() {
        assertThat(SpinoffButtons.blocks("1006", "發現", 0)).hasSize(1);
    }

    @Test void dispatchRequiresAllowedUserAndOriginalThread() {
        var props = mock(com.zipe.aiteam.config.AiTeamProperties.class);
        when(props.allowedUsers()).thenReturn(java.util.Set.of("u"));
        var orchestrator = mock(com.zipe.aiteam.task.TaskOrchestrator.class);
        var task = new com.zipe.aiteam.task.Task("1006", "c", "t", "u", "列出所有 API");
        when(orchestrator.lookup("1006")).thenReturn(java.util.Optional.of(task));
        var listener = new SlackListener(props, orchestrator, mock(SlackNotifier.class),
                mock(com.zipe.aiteam.store.TaskStore.class), mock(com.zipe.aiteam.store.AuditLog.class));
        listener.handleSpinoffButton("stranger", "1006|1", "c", "t");
        listener.handleSpinoffButton("u", "1006|1", "wrong", "t");
        listener.handleSpinoffButton("u", "1006|1", "c", "wrong");
        verify(orchestrator, never()).spinoff(any(), anyInt(), any());
        listener.handleSpinoffButton("u", "1006|1", "c", "t");
        verify(orchestrator).spinoff(task, 1, "u");
    }

    @Test void valueRoundTripsAndRejectsTampering() {
        assertThat(SpinoffButtons.Choice.parse("1006|2")).isEqualTo(new SpinoffButtons.Choice("1006", 2));
        for (String bad : new String[]{null, "", "1006", "1006|", "|1", "1006|0", "1006|6", "abc|1", "1006|1|x", "1006|修正文件"})
            assertThatThrownBy(() -> SpinoffButtons.Choice.parse(bad)).isInstanceOf(IllegalArgumentException.class);
    }
}
