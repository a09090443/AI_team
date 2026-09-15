package com.zipe.aiteam.slack;

import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.element.BlockElement;
import com.zipe.aiteam.flow.HumanOptions;
import java.util.ArrayList;
import java.util.List;

import static com.slack.api.model.block.Blocks.actions;
import static com.slack.api.model.block.Blocks.asBlocks;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;
import static com.slack.api.model.block.element.BlockElements.button;

/**
 * 任務停下來等人決定時的版面：先用白話說發生什麼事，再把能一鍵完成的選項做成按鈕。
 *
 * 按鈕不是新的權限路徑 —— 它送出的就是使用者本來要手動打的那串「補充」指令，
 * 走同一套白名單、同一套驗證。差別只在使用者不必自己抄一長串授權碼，
 * 而那正是 #1014 實際卡住的地方之一：授權碼被誤填成使用者 ID。
 */
public final class HumanGate {
    private HumanGate() {}

    public static final String ACTION_COMMAND = "ai_team_human_command";

    /** value 需要同時帶任務與要送出的指令；Slack 上限 2000 字元。 */
    public record Command(String task, String command) {
        public String value() {
            String value = task + "|" + command;
            if (value.length() > 1900) throw new IllegalArgumentException("按鈕指令過長");
            return value;
        }
        public static Command parse(String value) {
            if (value == null || value.length() > 1900) throw new IllegalArgumentException("無效的按鈕");
            int split = value.indexOf('|');
            if (split <= 0 || split == value.length() - 1) throw new IllegalArgumentException("無效的按鈕");
            return new Command(value.substring(0, split), value.substring(split + 1));
        }
    }

    public static List<LayoutBlock> blocks(String taskId, String text, List<HumanOptions.Option> options) {
        List<BlockElement> buttons = new ArrayList<>();
        for (HumanOptions.Option option : options) {
            if (!option.button() || buttons.size() >= 4) continue;
            String value = new Command(taskId, option.command()).value();
            boolean primary = HumanOptions.EXTEND.equals(option.kind());
            buttons.add(button(b -> {
                b.actionId(ACTION_COMMAND).value(value).text(plainText(option.label()));
                return primary ? b.style("primary") : b;
            }));
        }
        buttons.add(button(b -> b.actionId(SlackBlocks.ACTION_ABORT).value(taskId).style("danger")
                .text(plainText("中止任務"))));
        return asBlocks(section(s -> s.text(markdownText(clip(text)))),
                actions(a -> a.elements(buttons)));
    }

    /** section 的文字上限是 3000 字元，超過整則訊息會被 Slack 拒絕。 */
    private static String clip(String text) {
        String safe = text == null ? "" : text;
        return safe.length() <= 2900 ? safe : safe.substring(0, 2900) + "\n…（完整內容見任務紀錄）";
    }
}
