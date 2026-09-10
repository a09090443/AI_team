package com.zipe.aiteam.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SlackReportTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void decodesJsonNewlinesWithoutChangingReceipt() throws Exception {
        var report = mapper.readTree("{\"route\":\"implementation\",\"execution_status\":\"COMPLETED\",\"summary\":\"保留既有實作\\n接續文件建置\"}");
        var before = report.deepCopy();
        String text = SlackReport.render(report);
        assertTrue(text.contains("保留既有實作\n接續文件建置"));
        assertTrue(text.contains("不代表整體任務或 QA 驗收通過"));
        assertTrue(text.contains("建議後續階段：\nimplementation"));
        assertFalse(text.contains("\\n"));
        assertFalse(text.contains("\"summary\""));
        assertEquals(before, report);
    }

    @Test void preservesNestedCustomFieldsAndLiteralPaths() throws Exception {
        var report = mapper.createObjectNode();
        report.put("summary", "C:\\new\\test <@U123> & \\`git status\\`");
        report.set("custom_findings", mapper.readTree("[{\"issue\":\"缺少文件\",\"severity\":\"HIGH\"}]"));
        String text = SlackReport.render(report);
        assertTrue(text.contains("C:\\new\\test"));
        assertTrue(text.contains("&lt;@U123&gt; &amp; `git status`"));
        assertTrue(text.contains("缺少文件"));
        assertTrue(text.contains("HIGH"));
        assertFalse(text.contains("{\""));
    }

    @Test void handlesMissingAndLongReports() {
        assertEquals("沒有有效報告", SlackReport.render(null));
        var report = mapper.createObjectNode().put("summary", "長".repeat(20000));
        String text = SlackReport.render(report);
        assertTrue(text.length() < 9000);
        assertTrue(text.contains("完整報告保留於任務紀錄"));
    }
}
