package com.zipe.aiteam.slack;

import com.slack.api.model.block.ContextBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import com.slack.api.model.block.composition.TextObject;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把訊息裡的裸網址包成 Slack 的明確連結 {@code <url>}。
 *
 * Slack 自動偵測網址時只以空白斷開，中文句子常把全形標點或文字直接接在網址後面，
 * 例如「…/dynamic-api/，audience 為…」會被連成 {@code /dynamic-api/%EF%BC%8Caudience}，
 * 使用者點到的是錯的網址（dynamic-api #1023）。這是 Slack 語法的確定性轉換，
 * 不依賴 agent 記得在網址後加空白，所以放在所有發言的出口統一處理。
 *
 * 網址遇到空白、非 ASCII 字元（全形標點、中文）或 {@code <>|`} 即結束，句尾的 ASCII 標點與
 * 未成對的右括號不算網址。已是 {@code <...>} 的連結與程式碼區塊、行內程式碼內的網址保持原樣。
 */
final class SlackLinks {
    private SlackLinks() {}

    private static final Pattern CODE = Pattern.compile("```[\\s\\S]*?```|`[^`\\n]*`");
    private static final Pattern URL = Pattern.compile("https?://[\\x21-\\x7E&&[^<>|`]]+");

    static String wrap(String text) {
        if (text == null || !text.contains("://")) return text;
        StringBuilder out = new StringBuilder(text.length() + 16);
        Matcher code = CODE.matcher(text);
        int at = 0;
        while (code.find()) {
            out.append(wrapPlain(text.substring(at, code.start()))).append(code.group());
            at = code.end();
        }
        return out.append(wrapPlain(text.substring(at))).toString();
    }

    private static String wrapPlain(String text) {
        Matcher m = URL.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 16);
        int at = 0;
        while (m.find()) {
            // 已是 <url> 或 <url|文字> 的明確連結，不重複包。
            if (m.start() > 0 && text.charAt(m.start() - 1) == '<') continue;
            String url = trimTail(m.group());
            if (url.indexOf("://") + 3 >= url.length()) continue;
            out.append(text, at, m.start()).append('<').append(url).append('>');
            at = m.start() + url.length();
        }
        return out.append(text.substring(at)).toString();
    }

    /** 句尾的 ASCII 標點與未成對的右括號屬於句子，不屬於網址。 */
    private static String trimTail(String url) {
        int end = url.length();
        while (end > 0) {
            char c = url.charAt(end - 1);
            if (".,;:!?'\"*_~".indexOf(c) >= 0) { end--; continue; }
            if ((c == ')' || c == ']') && unbalanced(url.substring(0, end), c == ')' ? '(' : '[', c)) { end--; continue; }
            break;
        }
        return url.substring(0, end);
    }

    private static boolean unbalanced(String s, char open, char close) {
        int depth = 0;
        for (char c : s.toCharArray()) { if (c == open) depth++; else if (c == close) depth--; }
        return depth < 0;
    }

    /** section 與 context 的 mrkdwn 文字也要處理：有 blocks 時 Slack 顯示的是 blocks，不是 text。 */
    static List<LayoutBlock> wrapBlocks(List<LayoutBlock> blocks) {
        if (blocks == null) return null;
        for (LayoutBlock block : blocks) {
            if (block instanceof SectionBlock section) {
                wrapText(section.getText());
                if (section.getFields() != null) section.getFields().forEach(SlackLinks::wrapText);
            } else if (block instanceof ContextBlock context && context.getElements() != null) {
                context.getElements().forEach(e -> { if (e instanceof TextObject t) wrapText(t); });
            }
        }
        return blocks;
    }

    private static void wrapText(TextObject text) {
        if (text instanceof MarkdownTextObject md) md.setText(wrap(md.getText()));
    }
}
