package com.zipe.aiteam.slack;

import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.store.*;
import com.zipe.aiteam.task.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanButtonTest {
    @Test void copiedCommandsAreUnwrappedButQuestionsAreNot() {
        String command = "補充 #1014 請依已核准計畫重新執行 QA 驗收";
        assertEquals(command, SlackListener.unwrapCommand("`" + command + "`"));
        assertEquals(command, SlackListener.unwrapCommand("```text\n" + command + "\n```"));
        assertEquals(command, SlackListener.unwrapCommand(command));
        assertEquals("請解釋 `補充 #1014`", SlackListener.unwrapCommand("請解釋 `補充 #1014`"));
        assertEquals("`mvn verify`", SlackListener.unwrapCommand("`mvn verify`"));
    }
    @Test void roundTripAndTwoDistinctActions() {
        var d = new PlanReview.Decision("42", "plan", "digest", "config", "baseline");
        assertEquals(d, PlanReview.Decision.parse(d.value()));
        var blocks = PlanReview.buttons(d);
        var actions = (com.slack.api.model.block.ActionsBlock) blocks.get(1);
        assertEquals(2, actions.getElements().size());
        assertEquals(PlanReview.APPROVE, ((com.slack.api.model.block.element.ButtonElement) actions.getElements().get(0)).getActionId());
        assertEquals(PlanReview.REVISE, ((com.slack.api.model.block.element.ButtonElement) actions.getElements().get(1)).getActionId());
        assertThrows(IllegalArgumentException.class, () -> PlanReview.Decision.parse("42"));
    }
    @Test void dispatchRequiresAllowedUserAndOriginalThread() {
        var props = mock(AiTeamProperties.class); when(props.allowedUsers()).thenReturn(Set.of("u"));
        var orchestrator = mock(TaskOrchestrator.class);
        var task = new Task("42", "c", "t", "u", "test");
        when(orchestrator.lookup("42")).thenReturn(Optional.of(task));
        var listener = new SlackListener(props, orchestrator, mock(SlackNotifier.class), mock(TaskStore.class), mock(AuditLog.class));
        var d = new PlanReview.Decision("42", "plan", "digest", "config", "baseline");
        listener.handlePlanButton("stranger", d.value(), PlanReview.APPROVE, "c", "t");
        listener.handlePlanButton("u", d.value(), PlanReview.APPROVE, "wrong", "t");
        listener.handlePlanButton("u", d.value(), PlanReview.APPROVE, "c", "wrong");
        verify(orchestrator, never()).planDecision(any(), any(), any(), anyBoolean(), any(), any());
        listener.handlePlanButton("u", d.value(), PlanReview.APPROVE, "c", "t");
        verify(orchestrator).planDecision(task, "u", d, true, "c", "t");
    }
}
