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
        assertTrue(text.contains("還不代表整個任務驗收通過"));
        assertTrue(text.contains("下一步：\n交回工程實作修正（implementation）"));
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

    @Test void 摘要只留判定摘要阻礙與後續_其餘欄位不顯示但要說有幾個() throws Exception {
        var report = mapper.readTree("""
                {"verdict":"FAIL","summary":"6/27 情境通過","route":"triage",
                 "scenario_results":[{"id":"SC-01","expected_result":"送信成功"}],
                 "findings":["GreenMail socket 被拒"],"evidence":"mvn -B verify exit 1"}
                """);

        String text = SlackReport.brief(report);

        assertTrue(text.contains("驗收沒過"));
        assertTrue(text.contains("6/27 情境通過"));
        assertTrue(text.contains("下一步：交給 PM 分流判斷（triage）"));
        // 未顯示的欄位不能靜靜消失，要讓人知道還有東西可看
        assertTrue(text.contains("還有 3 項細節沒列出來"));
        assertFalse(text.contains("SC-01"));
        assertFalse(text.contains("mvn -B verify"));
    }

    @Test void 阻礙在摘要模式一定看得到() throws Exception {
        var report = mapper.readTree("""
                {"execution_status":"BLOCKED","summary":"無法核實",
                 "blockers":["工作區唯讀，npm ci 失敗","GreenMail 無法建立 socket"]}
                """);

        String text = SlackReport.brief(report);

        assertTrue(text.contains("卡住了，需要處理"));
        assertTrue(text.contains("阻礙 2 項"));
        assertTrue(text.contains("工作區唯讀"));
    }

    @Test void 摘要過長要截斷_但不動原始報告() throws Exception {
        var report = mapper.createObjectNode().put("summary", "長".repeat(3000));
        var before = report.deepCopy();

        String text = SlackReport.brief(report);

        assertTrue(text.length() < 700);
        assertTrue(text.contains("…"));
        assertEquals(before, report);
    }

    private static final String DECISION_REPORT = """
            {"execution_status":"COMPLETED","route":"human","summary":"%s",
             "human_decision":{"headline":"驗收時發現一個原本就存在的限制","question":"smtps 這次要一起支援嗎？",
               "options":[{"label":"另開任務處理","consequence":"這次照現況完成，smtps 另外追蹤"},
                          {"label":"這次一起支援","consequence":"要改需求、補情境、重跑實作與驗收"}],
               "recommendation":"建議另開任務，因為不是這次改壞的",
               "also_needed":["QA 環境需要補 npm 離線快取"]}}
            """;

    @Test void 需要決定時完整顯示決定事項_不被摘要截斷() throws Exception {
        var report = mapper.readTree(DECISION_REPORT.formatted("長".repeat(3000)));

        String text = SlackReport.brief(report);

        assertTrue(text.startsWith("*為什麼停下來*"));
        assertTrue(text.contains("smtps 這次要一起支援嗎？"));
        assertTrue(text.contains("A．另開任務處理"));
        assertTrue(text.contains("B．這次一起支援"));
        assertTrue(text.contains("要改需求、補情境、重跑實作與驗收"));
        assertTrue(text.contains("建議另開任務"));
        assertTrue(text.contains("QA 環境需要補 npm 離線快取"));
        // 決定事項本身不算「沒列出來的細節」。
        assertFalse(text.contains("human_decision"));
    }

    @Test void 完整模式也把決定事項放最前面() throws Exception {
        String text = SlackReport.render(mapper.readTree(DECISION_REPORT.formatted("摘要")));
        assertTrue(text.startsWith("*為什麼停下來*"));
        assertFalse(text.contains("human_decision"));
    }

    @Test void 沒有決定事項時維持原本摘要() throws Exception {
        var report = mapper.readTree("{\"execution_status\":\"COMPLETED\",\"summary\":\"完成\",\"human_decision\":null}");
        assertFalse(SlackReport.hasDecision(report));
        assertFalse(SlackReport.brief(report).contains("需要你決定"));
    }

    @Test void 摘要模式同樣要遮蔽與跳脫() throws Exception {
        var report = mapper.createObjectNode().put("summary", "<@U123> & 完成");

        assertTrue(SlackReport.brief(report).contains("&lt;@U123&gt; &amp; 完成"));
        assertEquals("沒有有效報告", SlackReport.brief(null));
    }
}
