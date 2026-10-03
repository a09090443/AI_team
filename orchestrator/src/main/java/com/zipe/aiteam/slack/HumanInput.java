package com.zipe.aiteam.slack;

import com.slack.api.model.block.element.BlockElement;
import com.slack.api.model.view.View;
import com.zipe.aiteam.flow.HumanOptions;
import java.util.ArrayList;
import java.util.List;

import static com.slack.api.model.block.Blocks.asBlocks;
import static com.slack.api.model.block.Blocks.input;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;
import static com.slack.api.model.block.element.BlockElements.button;
import static com.slack.api.model.block.element.BlockElements.plainTextInput;
import static com.slack.api.model.view.Views.view;
import static com.slack.api.model.view.Views.viewClose;
import static com.slack.api.model.view.Views.viewSubmit;
import static com.slack.api.model.view.Views.viewTitle;

/**
 * 需要你寫內容的選項（需求要改、告訴它還缺什麼、修好了請重驗）：按鈕打開 Slack 輸入視窗，送出後組成
 * 和打字完全相同的「補充」指令，走同一套白名單、同一個「只認原討論串」檢查與同一套流程驗證，不另開權限。
 *
 * 按鈕 value 與視窗 metadata 只放任務、種類與討論串；指令文字由系統依種類組成，
 * 使用者在視窗裡只能填說明內容，不能改寫成其他指令種類。
 */
public final class HumanInput {
    private HumanInput() {}

    public static final String ACTION = "ai_team_human_input";
    /** 同一則訊息的 action_id 不可重複，每顆加序號。 */
    public static final java.util.regex.Pattern ACTION_PATTERN = java.util.regex.Pattern.compile("^" + ACTION + "(_\\d+)?$");
    public static final String CALLBACK = "ai_team_human_input_submit";
    public static final String BLOCK = "content";
    public static final String FIELD = "text";
    static final int MAX_TEXT = 2800;

    public record Choice(String task, String kind) {
        public String value() { return task + "|" + kind; }
        public static Choice parse(String value) {
            if (value == null || !value.matches("\\d{1,12}\\|[A-Z]{1,20}")) throw new IllegalArgumentException("無效的按鈕");
            String[] parts = value.split("\\|");
            if (!HumanOptions.INPUT_KINDS.contains(parts[1])) throw new IllegalArgumentException("無效的按鈕");
            return new Choice(parts[0], parts[1]);
        }
    }

    /** 視窗帶著的脈絡；extra 只有重驗用到：開窗當下系統查得的「驗收節點 commit」。 */
    public record Submission(String task, String kind, String channel, String thread, String extra) {
        public String metadata() { return String.join("|", task, kind, channel, thread, extra == null ? "" : extra); }
        public static Submission parse(String metadata) {
            if (metadata == null || metadata.length() > 2000) throw new IllegalArgumentException("無效的輸入視窗");
            String[] parts = metadata.split("\\|", -1);
            if (parts.length != 5 || !parts[0].matches("\\d{1,12}") || !HumanOptions.INPUT_KINDS.contains(parts[1])
                    || parts[2].isBlank() || parts[3].isBlank()) throw new IllegalArgumentException("無效的輸入視窗");
            if (HumanOptions.REMEDIATE.equals(parts[1]) && !parts[4].matches("[a-z][a-z0-9_]* [0-9a-f]{7,64}"))
                throw new IllegalArgumentException("無效的輸入視窗");
            return new Submission(parts[0], parts[1], parts[2], parts[3], parts[4]);
        }
    }

    public static BlockElement inputButton(String taskId, HumanOptions.Option option, int index) {
        String label = option.label().length() <= 70 ? option.label() : option.label().substring(0, 70) + "…";
        return button(b -> b.actionId(ACTION + "_" + index).value(new Choice(taskId, option.kind()).value()).text(plainText(label)));
    }

    /** 重驗只需確認系統帶入的節點與 commit；其他種類要填內容。 */
    public static boolean needsText(String kind) { return !HumanOptions.REMEDIATE.equals(kind); }

    public static View modal(Submission s) {
        String title, intro, label = null, placeholder = null;
        switch (s.kind()) {
            case "REQUIREMENT" -> {
                title = "需求要改";
                intro = "*任務 #" + s.task() + "* 的需求變更。只有需求負責人可以核准；核准後會回到需求整理重做，"
                        + "之後的測試計畫、驗收與審查都會重跑，PR 仍需要你核准。";
                label = "要怎麼改、為什麼";
                placeholder = "例如：移除 REQ-2，規範掃描改由審查檢查";
            }
            case "SUPPLEMENT" -> {
                title = "告訴它還缺什麼";
                intro = "*任務 #" + s.task() + "*：你寫的內容會加入流程脈絡，交由分流重新判斷。"
                        + "知道還有沒修好的問題就直接寫出來，系統才派得出去。";
                label = "你的說明或決定";
                placeholder = "例如：第 142 行改成 14 個模組即可，不需要動其他檔案";
            }
            default -> {
                title = "修好了，請它重驗";
                String[] target = s.extra().split(" ");
                intro = "*任務 #" + s.task() + "*：系統會依目前工作區授權「" + target[0] + "」重驗一次。\n"
                        + "目前 commit：`" + target[1] + "`\n工作區必須乾淨，而且這個 commit 要是你修好的版本；"
                        + "重驗後仍須重新核准測試計畫，不會重設歷史次數。";
            }
        }
        var blocks = new ArrayList<>(asBlocks(section(sec -> sec.text(markdownText(intro)))));
        if (label != null) {
            String fieldLabel = label, hint = placeholder;
            blocks.add(input(i -> i.blockId(BLOCK).label(plainText(fieldLabel)).element(
                    plainTextInput(p -> p.actionId(FIELD).multiline(true).maxLength(MAX_TEXT).placeholder(plainText(hint))))));
        }
        return view(v -> v.type("modal").callbackId(CALLBACK).privateMetadata(s.metadata())
                .title(viewTitle(t -> t.type("plain_text").text(title)))
                .submit(viewSubmit(t -> t.type("plain_text").text(needsText(s.kind()) ? "送出" : "確認重驗")))
                .close(viewClose(t -> t.type("plain_text").text("取消")))
                .blocks(blocks));
    }

    /** 組成與打字相同的指令；內容空白回 null，由呼叫端以欄位錯誤擋下。 */
    public static String command(Submission s, String text) {
        String content = text == null ? "" : text.strip();
        return switch (s.kind()) {
            case "REQUIREMENT" -> content.isEmpty() ? null : "核准需求變更 " + content;
            case "SUPPLEMENT" -> content.isEmpty() ? null : content;
            default -> "核准人工修復重驗 " + s.extra();
        };
    }

    /** 給停止訊息以外的地方用：只放幾顆輸入按鈕。 */
    public static List<BlockElement> buttons(String taskId, List<HumanOptions.Option> options, int start) {
        List<BlockElement> out = new ArrayList<>();
        int index = start;
        for (HumanOptions.Option option : options) if (option.input()) out.add(inputButton(taskId, option, index++));
        return out;
    }
}
