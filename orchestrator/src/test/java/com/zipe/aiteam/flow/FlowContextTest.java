package com.zipe.aiteam.flow;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FlowContextTest {

    @Test void keepsNewEntryWhenContextIsAlreadySaturated() {
        String saturated = "舊".repeat(FlowContext.MAX);
        String result = FlowContext.append(saturated, "節點 review 受阻：TLS socket 退化");

        assertThat(result).contains("節點 review 受阻：TLS socket 退化");
        assertThat(result.length()).isLessThanOrEqualTo(FlowContext.MAX + FlowContext.DROPPED.length());
    }

    @Test void repeatedAppendsNeverStopRecordingLatestEvent() {
        String context = "";
        for (int i = 0; i < 40; i++) context = FlowContext.append(context, "事件 " + i + "：" + "填充".repeat(600));

        assertThat(context).contains("事件 39：");
        assertThat(context).doesNotContain("事件 0：");
        assertThat(context.length()).isLessThanOrEqualTo(FlowContext.MAX + FlowContext.DROPPED.length());
    }

    @Test void dropsOldestSegmentsInsteadOfTruncatingTail() {
        String context = FlowContext.append("", "最舊事件");
        for (int i = 0; i < 6; i++) context = FlowContext.append(context, "中間" + i + "填充".repeat(FlowContext.ENTRY_MAX));
        context = FlowContext.append(context, "最新事件");

        assertThat(context).contains("最新事件").startsWith(FlowContext.DROPPED);
        assertThat(context).doesNotContain("最舊事件");
    }

    @Test void dropMarkerDoesNotAccumulate() {
        String context = "";
        for (int i = 0; i < 8; i++) context = FlowContext.append(context, "事件" + i + "填充".repeat(FlowContext.ENTRY_MAX));

        assertThat(context).startsWith(FlowContext.DROPPED).contains("事件7");
        assertThat(context.split(java.util.regex.Pattern.quote(FlowContext.DROPPED), -1)).hasSize(2);
    }

    @Test void singleOversizedEntryIsClippedButStillDelivered() {
        String result = FlowContext.append("", "開頭標記" + "內容".repeat(FlowContext.ENTRY_MAX));

        assertThat(result).contains("開頭標記");
        assertThat(result.length()).isLessThanOrEqualTo(FlowContext.MAX + FlowContext.DROPPED.length());
    }

    @Test void legacyContextWithoutMarkersStillAcceptsNewEntries() {
        // 既有任務的 context 是舊版一整段文字，沒有段落標記。
        String legacy = "舊版單段內容".repeat(4000);
        String result = FlowContext.append(legacy, "節點 review 受阻：必須送達");

        assertThat(result).contains("節點 review 受阻：必須送達");
    }

    @Test void shortContextIsLeftReadable() {
        String result = FlowContext.append("起始", "第一筆");

        assertThat(result).doesNotContain(FlowContext.DROPPED).contains("起始").contains("第一筆");
    }
}
