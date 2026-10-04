package com.zipe.aiteam.slack;

import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.element.ButtonElement;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpinoffButtonsTest {
    @Test void buttonsHaveUniqueActionIdsAndAtMostFive() {
        var blocks = SpinoffButtons.blocks("1006", "發現", java.util.Collections.nCopies(7, (String) null));
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
        assertThat(SpinoffButtons.blocks("1006", "發現", java.util.List.of())).hasSize(1);
    }

    @Test void buttonLabelsShowShortTitlesSoEachFindingIsDistinguishable() {
        // #1016：四顆按鈕都叫「開成新需求 N」，看不出各自要做什麼。
        var titles = new java.util.ArrayList<String>(java.util.List.of("決定 group 拼法", "目錄樹補 `OpenrouterModelBuilder`",
                "超過三十字的標題超過三十字的標題超過三十字的標題超過三十字的標題", " "));
        titles.add(null);
        var buttons = ((ActionsBlock) SpinoffButtons.blocks("1016", "發現", titles).get(1)).getElements();
        var labels = buttons.stream().map(b -> ((ButtonElement) b).getText().getText()).toList();
        assertThat(labels.get(0)).isEqualTo("開成新需求 1：決定 group 拼法");
        assertThat(labels.get(1)).isEqualTo("開成新需求 2：目錄樹補 OpenrouterModelBuilder");
        assertThat(labels.get(2)).startsWith("開成新需求 3：").endsWith("…")
                .hasSize("開成新需求 3：".length() + SpinoffButtons.TITLE_LIMIT + 1);
        assertThat(labels.get(3)).isEqualTo("開成新需求 4");
        assertThat(labels.get(4)).isEqualTo("開成新需求 5");
        assertThat(labels).allSatisfy(l -> assertThat(l.length()).isLessThanOrEqualTo(75));
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
