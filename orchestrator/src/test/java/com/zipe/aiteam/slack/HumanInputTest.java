package com.zipe.aiteam.slack;

import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.InputBlock;
import com.slack.api.model.block.element.ButtonElement;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.flow.HumanOptions;
import com.zipe.aiteam.store.*;
import com.zipe.aiteam.task.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 要填內容的選項：按鈕開視窗，送出的仍是與打字相同的「補充」指令。 */
class HumanInputTest {
    static final HumanOptions.Option REQUIREMENT = new HumanOptions.Option(HumanOptions.REQUIREMENT, "需求要改", "h", null);
    static final HumanOptions.Option SUPPLEMENT = new HumanOptions.Option(HumanOptions.SUPPLEMENT, "告訴它還缺什麼", "h", null);
    static final HumanOptions.Option REMEDIATE = new HumanOptions.Option(HumanOptions.REMEDIATE, "你自己修好了，請它重驗", "h", null);

    @Test void onlyFreeTextKindsWithoutCommandAreInputs() {
        assertThat(REQUIREMENT.input()).isTrue();
        assertThat(new HumanOptions.Option(HumanOptions.RESUME, "繼續執行", "h", "續跑").input()).isFalse();
        assertThat(new HumanOptions.Option(HumanOptions.PLAN, "核准測試計畫", "h", null).input()).isFalse();
    }

    @Test void stopMessageHasAnInputRowWithUniqueActionIds() {
        var options = List.of(new HumanOptions.Option(HumanOptions.RESUME, "繼續執行", "h", "續跑"), REQUIREMENT, SUPPLEMENT);
        var blocks = HumanGate.blocks("1012", "停下來了", options);
        Set<String> ids = new HashSet<>();
        int inputs = 0;
        for (var block : blocks) if (block instanceof ActionsBlock row) for (var element : row.getElements()) {
            var button = (ButtonElement) element;
            assertThat(ids.add(button.getActionId())).as(button.getActionId()).isTrue();
            if (HumanInput.ACTION_PATTERN.matcher(button.getActionId()).matches()) {
                inputs++;
                assertThat(HumanInput.Choice.parse(button.getValue()).task()).isEqualTo("1012");
            }
        }
        assertThat(inputs).isEqualTo(2);
    }

    @Test void choiceAndMetadataRejectTampering() {
        assertThat(HumanInput.Choice.parse("1012|SUPPLEMENT")).isEqualTo(new HumanInput.Choice("1012", "SUPPLEMENT"));
        for (String bad : new String[]{null, "", "1012", "1012|RESUME", "1012|續跑", "x|SUPPLEMENT", "1012|SUPPLEMENT|x"})
            assertThatThrownBy(() -> HumanInput.Choice.parse(bad)).isInstanceOf(IllegalArgumentException.class);
        var s = new HumanInput.Submission("1012", "REMEDIATE", "C1", "1.0", "verification abc1234");
        assertThat(HumanInput.Submission.parse(s.metadata())).isEqualTo(s);
        assertThatThrownBy(() -> HumanInput.Submission.parse("1012|REMEDIATE|C1|1.0|verification; rm")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HumanInput.Submission.parse("1012|RESUME|C1|1.0|")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void commandsMatchWhatTheUserWouldHaveTyped() {
        var requirement = new HumanInput.Submission("1012", "REQUIREMENT", "C1", "1.0", "");
        assertThat(HumanInput.command(requirement, "  移除 REQ-2，改由審查檢查 ")).isEqualTo("核准需求變更 移除 REQ-2，改由審查檢查");
        assertThat(HumanInput.command(requirement, "   ")).isNull();
        var supplement = new HumanInput.Submission("1012", "SUPPLEMENT", "C1", "1.0", "");
        assertThat(HumanInput.command(supplement, "第 142 行改成 14 個模組")).isEqualTo("第 142 行改成 14 個模組");
        assertThat(HumanInput.command(supplement, null)).isNull();
        var remediate = new HumanInput.Submission("1012", "REMEDIATE", "C1", "1.0", "verification abc1234");
        assertThat(HumanInput.command(remediate, null)).isEqualTo("核准人工修復重驗 verification abc1234");
    }

    @Test void modalsAskForTextExceptRemediationWhichShowsTheSystemTarget() {
        var supplement = HumanInput.modal(new HumanInput.Submission("1012", "SUPPLEMENT", "C1", "1.0", ""));
        assertThat(supplement.getCallbackId()).isEqualTo(HumanInput.CALLBACK);
        assertThat(supplement.getBlocks()).anyMatch(b -> b instanceof InputBlock i && HumanInput.BLOCK.equals(i.getBlockId()));
        var remediate = HumanInput.modal(new HumanInput.Submission("1012", "REMEDIATE", "C1", "1.0", "verification abc1234"));
        assertThat(remediate.getBlocks()).noneMatch(b -> b instanceof InputBlock);
        assertThat(remediate.getBlocks().getFirst().toString()).contains("abc1234").contains("verification");
    }

    @Test void submissionRequiresAllowedUserAndOriginalThread() {
        var props = mock(AiTeamProperties.class); when(props.allowedUsers()).thenReturn(Set.of("u"));
        var orchestrator = mock(TaskOrchestrator.class);
        var task = new Task("1012", "C1", "1.0", "u", "改文件");
        when(orchestrator.lookup("1012")).thenReturn(Optional.of(task));
        var listener = new SlackListener(props, orchestrator, mock(SlackNotifier.class), mock(TaskStore.class), mock(AuditLog.class));
        String ok = new HumanInput.Submission("1012", "SUPPLEMENT", "C1", "1.0", "").metadata();
        listener.handleHumanInputSubmit("stranger", ok, "補一句");
        listener.handleHumanInputSubmit("u", new HumanInput.Submission("1012", "SUPPLEMENT", "C1", "9.9", "").metadata(), "補一句");
        listener.handleHumanInputSubmit("u", ok, "  ");
        verify(orchestrator, never()).requestTriage(any(), any(), any());
        listener.handleHumanInputSubmit("u", ok, "補一句");
        verify(orchestrator).requestTriage(task, "補一句", "u");
        // 開窗也只認白名單與原討論串。
        assertThat(listener.inputView("stranger", "1012|SUPPLEMENT", "C1", "1.0")).isNull();
        assertThat(listener.inputView("u", "1012|SUPPLEMENT", "C1", "9.9")).isNull();
        assertThat(listener.inputView("u", "1012|SUPPLEMENT", "C1", "1.0")).isNotNull();
        when(orchestrator.remediationTarget(task)).thenReturn(Optional.empty());
        assertThat(listener.inputView("u", "1012|REMEDIATE", "C1", "1.0")).isNull();
    }
}
