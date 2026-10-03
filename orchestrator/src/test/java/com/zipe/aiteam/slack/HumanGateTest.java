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

        // 要填內容的「告訴它還缺什麼」改成打開輸入視窗的按鈕（另一排），不直接送指令。
        assertThat(rendered).hasSize(3);
        assertThat(rendered).filteredOn(b -> b.getActionId().matches(HumanGate.ACTION_PATTERN.pattern())).singleElement()
                .satisfies(b -> {
                    assertThat(b.getStyle()).isEqualTo("primary");
                    assertThat(b.getValue()).isEqualTo("1014|核准延長自動交接 abc");
                });
        assertThat(rendered).filteredOn(b -> HumanInput.ACTION_PATTERN.matcher(b.getActionId()).matches()).singleElement()
                .satisfies(b -> assertThat(b.getValue()).isEqualTo("1014|SUPPLEMENT"));
        assertThat(rendered.getLast().getActionId()).isEqualTo(SlackBlocks.ACTION_ABORT);
    }

    @Test void everyButtonHasAUniqueActionIdOrSlackRejectsTheWholeMessage() {
        // #1014：「繼續執行」與「看完整報告」共用 action_id，整則停止訊息以 invalid_blocks 被拒收。
        var rendered = buttons(List.of(
                new HumanOptions.Option(HumanOptions.RESUME, "繼續執行", "說明", "續跑"),
                new HumanOptions.Option(HumanOptions.REPORT, "看完整報告", "說明", "檢視完整報告")));

        var ids = rendered.stream().map(ButtonElement::getActionId).toList();
        assertThat(ids).hasSize(3).doesNotHaveDuplicates();
        assertThat(ids.subList(0, 2)).allMatch(id -> HumanGate.ACTION_PATTERN.matcher(id).matches());
    }

    @Test void decisionsGetTheirOwnRowAndIdsStayUniqueAcrossRows() {
        var blocks = HumanGate.blocks("1014", "停下來了", List.of(
                new HumanOptions.Option(HumanOptions.DECISION, "另開任務處理", "說明", "負責人決定：q　選擇：A"),
                new HumanOptions.Option(HumanOptions.DECISION, "這次一起支援", "說明", "負責人決定：q　選擇：B"),
                new HumanOptions.Option(HumanOptions.REPORT, "看完整報告", "說明", "檢視完整報告")));

        var rows = blocks.stream().filter(ActionsBlock.class::isInstance).map(ActionsBlock.class::cast).toList();
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().getElements()).hasSize(2);
        assertThat(rows.getLast().getElements()).hasSize(2); // 看完整報告 + 中止

        var ids = buttons(List.of(
                new HumanOptions.Option(HumanOptions.DECISION, "A", "說明", "負責人決定：q　選擇：A"),
                new HumanOptions.Option(HumanOptions.REPORT, "看完整報告", "說明", "檢視完整報告")))
                .stream().map(ButtonElement::getActionId).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test void longLabelsAreClippedToSlacksButtonLimit() {
        var rendered = buttons(List.of(new HumanOptions.Option(HumanOptions.DECISION, "長".repeat(100), "說明", "負責人決定：q")));
        assertThat(rendered.getFirst().getText().getText().length()).isLessThanOrEqualTo(75);
    }

    @Test void listenerPatternAcceptsOnlyCommandButtons() {
        assertThat(HumanGate.ACTION_PATTERN.matcher("ai_team_human_command_0").matches()).isTrue();
        assertThat(HumanGate.ACTION_PATTERN.matcher("ai_team_human_command_12").matches()).isTrue();
        assertThat(HumanGate.ACTION_PATTERN.matcher(SlackBlocks.ACTION_ABORT).matches()).isFalse();
        assertThat(HumanGate.ACTION_PATTERN.matcher("ai_team_human_command_x").matches()).isFalse();
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

    @Test void atMostEightCommandButtonsPlusAbort() {
        // 按鈕版面的文字不再附打字指令，按鈕被截掉就等於選項消失，所以上限從 4 放寬到 8（Slack 單排上限 25）。
        var many = new java.util.ArrayList<HumanOptions.Option>();
        for (int i = 0; i < 10; i++)
            many.add(new HumanOptions.Option(HumanOptions.REPORT, "選項" + i, "說明", "檢視完整報告 n" + i));

        assertThat(buttons(many)).hasSize(9);
    }
}
