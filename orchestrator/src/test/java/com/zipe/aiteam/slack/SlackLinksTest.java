package com.zipe.aiteam.slack;

import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static org.assertj.core.api.Assertions.assertThat;

class SlackLinksTest {

    @Test
    void fullWidthPunctuationEndsUrl() {
        // dynamic-api #1023：Slack 把「，audience」連進網址
        assertThat(SlackLinks.wrap("測試網址是 http://192.168.56.20:18080/dynamic-api/，audience 為測試電腦"))
                .isEqualTo("測試網址是 <http://192.168.56.20:18080/dynamic-api/>，audience 為測試電腦");
        assertThat(SlackLinks.wrap("網址http://host/a?x=1&y=2。請開啟"))
                .isEqualTo("網址<http://host/a?x=1&y=2>。請開啟");
        assertThat(SlackLinks.wrap("見（https://host/p）"))
                .isEqualTo("見（<https://host/p>）");
    }

    @Test
    void trailingAsciiPunctuationIsNotPartOfUrl() {
        assertThat(SlackLinks.wrap("Open http://host/a. Then (see http://host/b), and http://host/wiki/X_(Y)"))
                .isEqualTo("Open <http://host/a>. Then (see <http://host/b>), and <http://host/wiki/X_(Y)>");
    }

    @Test
    void keepsExplicitLinksAndCode() {
        String text = "<http://host/a|管理介面> 與 <http://host/b>\n`curl http://host/c，x`\n```\nGET http://host/d，y\n```";
        assertThat(SlackLinks.wrap(text)).isEqualTo(text);
        assertThat(SlackLinks.wrap("沒有網址")).isEqualTo("沒有網址");
        assertThat(SlackLinks.wrap(null)).isNull();
        assertThat(SlackLinks.wrap("http:// 不是網址")).isEqualTo("http:// 不是網址");
    }

    @Test
    void wrapsMarkdownInBlocks() {
        List<LayoutBlock> blocks = new ArrayList<>(List.of(section(s -> s.text(markdownText("入口 http://host/x，請測")))));
        SlackLinks.wrapBlocks(blocks);
        assertThat(((MarkdownTextObject) ((SectionBlock) blocks.get(0)).getText()).getText())
                .isEqualTo("入口 <http://host/x>，請測");
    }
}
