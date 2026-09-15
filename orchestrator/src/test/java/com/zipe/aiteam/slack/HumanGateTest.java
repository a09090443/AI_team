package com.zipe.aiteam.slack;

import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.element.ButtonElement;
import com.zipe.aiteam.flow.HumanOptions;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class HumanGateTest {

    private List<ButtonElement> buttons(List<HumanOptions.Option> options) {
        return HumanGate.blocks("1014", "停下來了", options).stream()
                .filter(ActionsBlock.class::isInstance).map(ActionsBlock.class::cast)
                .flatMap(a -> a.getElements().stream())
                .filter(ButtonElement.class::isInstance).map(ButtonElement.class::cast).toList();
    }

    @Test void abortIsAlwaysAvailableEvenWithNoOtherOption() {
        var only = buttons(List.of());
        assertThat(only).hasSize(1);
        assertThat(only.getFirst().getActionId()).isEqualTo(SlackBlocks.ACTION_ABORT);
        assertThat(only.getFirst().getValue()).isEqualTo("1014");
    }

    @Test void onlyButtonBackedOptionsBecomeButtons() {
        var rendered = buttons(List.of(
                new HumanOptions.Option(HumanOptions.EXTEND, "再給系統兩次機會", "說明", "核准延長自動交接 abc"),
                new HumanOptions.Option(HumanOptions.SUPPLEMENT, "告訴它還缺什麼", "說明", null)));

        assertThat(rendered).hasSize(2);
        assertThat(rendered.getFirst().getActionId()).isEqualTo(HumanGate.ACTION_COMMAND);
        assertThat(rendered.getFirst().getStyle()).isEqualTo("primary");
        assertThat(rendered.getFirst().getValue()).isEqualTo("1014|核准延長自動交接 abc");
        assertThat(rendered.getLast().getActionId()).isEqualTo(SlackBlocks.ACTION_ABORT);
    }

    @Test void commandRoundTripsThroughTheButtonValue() {
        var command = new HumanGate.Command("1014", "核准延長自動交接 " + "a".repeat(64));
        assertThat(HumanGate.Command.parse(command.value())).isEqualTo(command);
    }

    @Test void commandKeepsPipesInsideTheCommandItself() {
        var parsed = HumanGate.Command.parse("1014|檢視完整報告 a|b");
        assertThat(parsed.task()).isEqualTo("1014");
        assertThat(parsed.command()).isEqualTo("檢視完整報告 a|b");
    }

    @Test void malformedButtonValuesAreRejected() {
        assertThatThrownBy(() -> HumanGate.Command.parse(null)).hasMessageContaining("無效");
        assertThatThrownBy(() -> HumanGate.Command.parse("1014")).hasMessageContaining("無效");
        assertThatThrownBy(() -> HumanGate.Command.parse("|續跑")).hasMessageContaining("無效");
        assertThatThrownBy(() -> HumanGate.Command.parse("1014|")).hasMessageContaining("無效");
        assertThatThrownBy(() -> HumanGate.Command.parse("1014|" + "x".repeat(1900))).hasMessageContaining("無效");
    }

    @Test void oversizedCommandsAreRefusedRatherThanSilentlyTruncated() {
        assertThatThrownBy(() -> new HumanGate.Command("1014", "x".repeat(1900)).value())
                .hasMessageContaining("過長");
    }

    @Test void atMostFourCommandButtonsPlusAbort() {
        var many = new java.util.ArrayList<HumanOptions.Option>();
        for (int i = 0; i < 6; i++)
            many.add(new HumanOptions.Option(HumanOptions.REPORT, "選項" + i, "說明", "檢視完整報告 n" + i));

        assertThat(buttons(many)).hasSize(5);
    }
}
