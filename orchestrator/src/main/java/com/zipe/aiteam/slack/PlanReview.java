package com.zipe.aiteam.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zipe.aiteam.flow.Traceability;
import java.util.ArrayList;
import java.util.List;

/** 完整逐情境呈現計畫，不把摘要當成可核准的完整契約。 */
public final class PlanReview {
    public static final String APPROVE = "ai_team_plan_approve";
    public static final String REVISE = "ai_team_plan_revise";
    public record Decision(String task, String plan, String digest, String configuration, String baseline) {
        public String value() { return String.join("|", task, plan, digest, configuration, baseline); }
        public static Decision parse(String value) {
            if (value == null || value.length() > 1900) throw new IllegalArgumentException("無效的計畫按鈕");
            String[] p = value.split("\\|", -1);
            if (p.length != 5 || java.util.Arrays.stream(p).anyMatch(String::isBlank)) throw new IllegalArgumentException("無效的計畫按鈕");
            return new Decision(p[0], p[1], p[2], p[3], p[4]);
        }
    }
    public static java.util.List<com.slack.api.model.block.LayoutBlock> buttons(Decision decision) {
        return com.slack.api.model.block.Blocks.asBlocks(
                com.slack.api.model.block.Blocks.section(s -> s.text(com.slack.api.model.block.composition.BlockCompositions.plainText(
                        "任務 #" + decision.task() + "｜請先審閱上方完整測試計畫。僅原負責人可核准或要求修改；核准後自動排入 QA，無需另送續跑。這不代表核准 PR。"))),
                com.slack.api.model.block.Blocks.actions(a -> a.elements(com.slack.api.model.block.element.BlockElements.asElements(
                        com.slack.api.model.block.element.BlockElements.button(b -> b.actionId(APPROVE).value(decision.value()).style("primary")
                                .text(com.slack.api.model.block.composition.BlockCompositions.plainText("核准測試計畫"))),
                        com.slack.api.model.block.element.BlockElements.button(b -> b.actionId(REVISE).value(decision.value())
                                .text(com.slack.api.model.block.composition.BlockCompositions.plainText("要求修改")))))));
    }
    private PlanReview() {}
    public static List<String> pages(String taskId, String planId, JsonNode plan) {
        if (plan == null || !plan.isObject() || !plan.path("scenarios").isArray()
                || plan.path("scenarios").isEmpty()) throw new IllegalArgumentException("尚無完整情境測試計畫，不能核准");
        List<String> bodies = new ArrayList<>();
        ObjectNode overview = plan.deepCopy(); overview.remove("scenarios");
        bodies.add(SlackReport.render(overview));
        for (JsonNode scenario : plan.path("scenarios")) bodies.add(SlackReport.render(scenario));
        if (bodies.stream().anyMatch(s -> s.contains("完整報告保留於任務紀錄") || s.contains("詳細內容請見完整任務紀錄")))
            throw new IllegalArgumentException("計畫過長，無法完整呈現，請拆分情境後重新審閱；未提供核准指令");
        List<String> pages = new ArrayList<>();
        for (int i = 0; i < bodies.size(); i++) pages.add("任務 #" + taskId + " 測試計畫（" + (i + 1) + "/" + bodies.size() + "）\n" + bodies.get(i));
        pages.add("任務 #" + taskId + " 測試計畫已完整列出，共 " + plan.path("scenarios").size() + " 條情境。\n"
                + "請先審閱；若同意，在原討論串 @PM Agent 後輸入：\n補充 #" + taskId + " 核准測試計畫 " + planId + " " + Traceability.digest(plan)
                + "\n核准成功後自動排入 QA，無需另送續跑；這不代表核准 PR。若不同意，請按要求修改並提出具體意見。");
        return List.copyOf(pages);
    }
}
