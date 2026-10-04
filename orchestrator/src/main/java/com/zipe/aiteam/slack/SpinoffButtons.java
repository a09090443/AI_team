package com.zipe.aiteam.slack;

import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.element.BlockElement;
import java.util.ArrayList;
import java.util.List;

import static com.slack.api.model.block.Blocks.actions;
import static com.slack.api.model.block.Blocks.asBlocks;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;
import static com.slack.api.model.block.element.BlockElements.button;

/**
 * 純詢問結束後的「開成新需求」按鈕：PM 回答時發現、但這次不處理的事項（out_of_scope），
 * 一鍵在頻道開新討論串建立任務。原討論串仍維持一串一任務。
 *
 * value 只放任務編號與項目序號，不放原文：原文由系統從任務紀錄讀回，
 * 既避開 Slack 2000 字上限，也不讓按鈕內容被改寫成任意需求。
 */
public final class SpinoffButtons {
    private SpinoffButtons() {}

    public static final String ACTION = "ai_team_spinoff";
    /** 同一則訊息的 action_id 不可重複（否則整則以 invalid_blocks 被拒收），所以每顆加序號。 */
    public static final java.util.regex.Pattern ACTION_PATTERN =
            java.util.regex.Pattern.compile("^" + ACTION + "(_\\d+)?$");
    public static final int MAX_BUTTONS = 5;

    public record Choice(String task, int index) {
        public String value() { return task + "|" + index; }
        public static Choice parse(String value) {
            if (value == null || !value.matches("\\d{1,12}\\|\\d{1,2}")) throw new IllegalArgumentException("無效的按鈕");
            int split = value.indexOf('|');
            int index = Integer.parseInt(value.substring(split + 1));
            if (index < 1 || index > MAX_BUTTONS) throw new IllegalArgumentException("無效的按鈕");
            return new Choice(value.substring(0, split), index);
        }
    }

    /** 按鈕文字上限 75 字元；短標題再長也只取這麼多，其餘看訊息內文。 */
    static final int TITLE_LIMIT = 30;

    /**
     * @param titles 每項發現一個元素（長度即發現項目數），元素是按鈕上的短標題；null 或空白時只顯示序號。
     *               只有前 MAX_BUTTONS 項有按鈕。
     */
    public static List<LayoutBlock> blocks(String taskId, String text, List<String> titles) {
        List<BlockElement> buttons = new ArrayList<>();
        for (int i = 1; i <= Math.min(titles.size(), MAX_BUTTONS); i++) {
            int index = i;
            String label = label(index, titles.get(i - 1));
            buttons.add(button(b -> b.actionId(ACTION + "_" + index).value(new Choice(taskId, index).value())
                    .text(plainText(label))));
        }
        List<LayoutBlock> blocks = new ArrayList<>(asBlocks(section(s -> s.text(markdownText(clip(text))))));
        if (!buttons.isEmpty()) blocks.add(actions(a -> a.elements(buttons)));
        return blocks;
    }

    /** 按鈕是純文字，反引號會原樣顯示，所以去掉；換行與連續空白壓成一格。 */
    static String label(int index, String title) {
        String clean = title == null ? "" : title.replace("`", "").replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) return "開成新需求 " + index;
        if (clean.codePointCount(0, clean.length()) > TITLE_LIMIT)
            clean = clean.substring(0, clean.offsetByCodePoints(0, TITLE_LIMIT)) + "…";
        return "開成新需求 " + index + "：" + clean;
    }

    /** section 的文字上限是 3000 字元。 */
    private static String clip(String text) {
        String safe = text == null ? "" : text;
        return safe.length() <= 2900 ? safe : safe.substring(0, 2900) + "\n…（完整內容見任務紀錄）";
    }
}
