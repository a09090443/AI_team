package com.zipe.aiteam.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.zipe.aiteam.agent.StageSpec;
import com.zipe.aiteam.task.StageOutcome;
import com.slack.api.model.block.LayoutBlock;
import com.zipe.aiteam.task.Task;

import java.util.ArrayList;
import java.util.List;

import static com.slack.api.model.block.Blocks.actions;
import static com.slack.api.model.block.Blocks.asBlocks;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;
import static com.slack.api.model.block.element.BlockElements.asElements;
import static com.slack.api.model.block.element.BlockElements.button;

/** Slack 訊息版面。按鈕的 action_id 是 Listener 的路由依據。 */
public final class SlackBlocks {

    public static final String ACTION_APPROVE = "ai_team_approve";
    public static final String ACTION_REDO    = "ai_team_redo";
    public static final String ACTION_ABORT   = "ai_team_abort";
    public static final String ACTION_CONTINUE = "ai_team_continue";
    /** 只停目前這一輪、任務保留可續跑；和 ACTION_ABORT（終止任務）刻意分開。 */
    public static final String ACTION_INTERRUPT = "ai_team_interrupt";

    /** 執行中的定期狀態訊息：附一顆「中斷這一輪」。 */
    public static List<LayoutBlock> progressBlocks(String taskId, String text) {
        return asBlocks(section(s -> s.text(markdownText(clip(text, 2900)))),
                actions(a -> a.elements(asElements(
                        button(b -> b.actionId(ACTION_INTERRUPT).value(taskId)
                                .text(plainText("中斷這一輪")))))));
    }

    public static List<LayoutBlock> quotaBlocks(String taskId, String text) {
        return asBlocks(section(s -> s.text(markdownText(clip(text, 2900)))),
                actions(a -> a.elements(asElements(
                        button(b -> b.actionId(ACTION_CONTINUE).value(taskId).style("primary")
                                .text(plainText("繼續任務"))),
                        button(b -> b.actionId(ACTION_ABORT).value(taskId).style("danger")
                                .text(plainText("中止")))))));
    }

    private SlackBlocks() {}

    /**
     * 唯一的人工閘門：推分支與開 PR 之前。
     *
     * 按鈕的 value 帶 taskId，Listener 靠它路由（見 SlackListener 的 blockAction 註冊）。
     * section 的文字上限是 3000 字元，超過會整則訊息被 Slack 拒絕，所以先裁切。
     */
    public static List<LayoutBlock> approvalBlocks(String taskId, String text) {
        return asBlocks(
                section(s -> s.text(markdownText(clip(text, 2900)))),
                actions(a -> a.elements(asElements(
                        button(b -> b.actionId(ACTION_APPROVE).value(taskId)
                                .style("primary")
                                .text(plainText("核准，推分支並開 PR"))),
                        button(b -> b.actionId(ACTION_REDO).value(taskId)
                                .text(plainText("重新開始（封存原工作區）"))),
                        button(b -> b.actionId(ACTION_ABORT).value(taskId)
                                .style("danger")
                                .text(plainText("中止")))
                ))));
    }

    /** 依角色分派渲染。新增角色時在這裡加一個 case。 */
    public static String renderStage(Task task, StageSpec stage, StageOutcome o) {
        return switch (stage.role()) {
            case "pm"           -> renderPm(task, o);
            case "architecture" -> renderArchitecture(task, o);
            case "backend"      -> renderBackend(task, o);
            case "qa_plan"      -> renderQaPlan(task, o);
            case "pm_triage" -> "*PM 問題處理：" + o.result().path("action").asText() + "*\n"
                    + o.result().path("summary").asText() + "\n原因：" + o.result().path("reason").asText()
                    + "\n下一步：" + o.result().path("instructions").asText();
            case "qa"           -> renderQa(task, o);
            case "code_review"  -> renderCodeReview(task, o);
            default             -> renderGeneric(task, stage, o);
        };
    }

    // ------------------------------------------------------------------ PM

    private static String renderPm(Task task, StageOutcome o) {
        JsonNode r = o.result();
        StringBuilder sb = new StringBuilder();
        sb.append("*任務 #").append(task.id()).append("　PM 分析結果*\n\n");
        sb.append("*理解*：").append(text(r, "summary")).append("\n");
        sb.append("*類型*：").append(text(r, "task_type"))
          .append("　　*優先度*：").append(text(r, "priority"))
          .append("　　*把握度*：").append(text(r, "confidence")).append("\n");

        String modules = array(r, "affected_modules");
        sb.append("*受影響模組*：").append(modules.isEmpty() ? "（無）" : modules).append("\n");

        String agents = array(r, "required_agents");
        sb.append("*需要的角色*：").append(agents.isEmpty() ? "（不需下游角色）" : agents).append("\n");

        if (r.hasNonNull("backward_compatibility_risk")) {
            sb.append("*向後相容風險*：").append(text(r, "backward_compatibility_risk")).append("\n");
        }

        JsonNode plan = r.get("plan");
        if (plan != null && plan.isArray() && !plan.isEmpty()) {
            sb.append("\n*執行計畫*\n");
            for (JsonNode step : plan) {
                sb.append("　").append(step.path("step").asInt()).append(". `")
                  .append(step.path("agent").asText()).append("`　")
                  .append(step.path("goal").asText()).append("\n");
            }
        }
        appendUncertainties(sb, r);
        appendFooter(sb, task, o);
        return sb.toString();
    }

    // ------------------------------------------------------------------ Architecture

    private static String renderArchitecture(Task task, StageOutcome o) {
        JsonNode r = o.result();
        StringBuilder sb = new StringBuilder();
        sb.append("*任務 #").append(task.id()).append("　架構分析（")
          .append(text(r, "adr_title")).append("）*\n\n");

        sb.append("*現況*\n").append(text(r, "current_architecture")).append("\n\n");
        sb.append("*問題*\n").append(text(r, "problem")).append("\n\n");
        sb.append("*提案*\n").append(text(r, "proposal")).append("\n\n");

        String modules = array(r, "affected_modules");
        if (!modules.isEmpty()) sb.append("*受影響模組*：").append(modules).append("\n");

        sb.append("*向後相容性*：").append(text(r, "backward_compatibility")).append("\n");
        sb.append("*風險等級*：").append(text(r, "risk_level")).append("\n");

        appendList(sb, r, "risks", "風險");
        appendList(sb, r, "implementation_boundary", "給 Backend 的實作邊界");
        appendList(sb, r, "qa_criteria", "給 QA 的驗收條件");
        appendUncertainties(sb, r);
        appendFooter(sb, task, o);
        return sb.toString();
    }

    // ------------------------------------------------------------------ Backend

    private static String renderBackend(Task task, StageOutcome o) {
        JsonNode r = o.result();
        String status = text(r, "status");
        StringBuilder sb = new StringBuilder();

        sb.append(statusIcon(status)).append(" *任務 #").append(task.id())
          .append("　實作結果：").append(status).append("*\n\n");
        sb.append(text(r, "summary")).append("\n");

        JsonNode gate = r.get("quality_gate");
        if (gate != null && gate.isObject()) {
            sb.append("\n*品質 Gate*：spotless ")
              .append(gate.path("spotless_applied").asBoolean(false) ? "✅" : "⬜")
              .append("　verify ")
              .append(gate.path("verify_passed").asBoolean(false) ? "✅" : "❌");
            if (gate.hasNonNull("docs_synced")) {
                sb.append("　文件同步 ")
                  .append(gate.path("docs_synced").asBoolean(false) ? "✅" : "⬜");
            }
            sb.append("\n");
            if (gate.hasNonNull("failure_detail")) {
                sb.append("```\n")
                  .append(clip(gate.path("failure_detail").asText(), 700))
                  .append("\n```\n");
            }
        }

        JsonNode files = r.get("changed_files");
        if (files != null && files.isArray() && !files.isEmpty()) {
            sb.append("\n*異動檔案*（").append(files.size()).append(" 個）\n");
            int shown = 0;
            for (JsonNode fn : files) {
                if (shown++ >= 12) {
                    sb.append("　… 另有 ").append(files.size() - 12).append(" 個\n");
                    break;
                }
                sb.append("　").append(changeMark(fn.path("change").asText()))
                  .append(" `").append(fn.path("path").asText()).append("`\n");
            }
        }

        JsonNode commits = r.get("commits");
        if (commits != null && commits.isArray() && !commits.isEmpty()) {
            sb.append("\n*Commit*\n");
            for (JsonNode c : commits) {
                sb.append("　`").append(c.path("sha").asText()).append("`　")
                  .append(c.path("message").asText()).append("\n");
            }
        }

        // 越界是最需要被人看見的一種結果，所以獨立成一段而不是混在 blockers 裡
        JsonNode conflict = r.get("boundary_conflict");
        if (conflict != null && conflict.isObject()) {
            sb.append("\n*⚠️ 超出實作邊界，已停止*\n");
            sb.append("　需要動到：").append(conflict.path("needed").asText()).append("\n");
            sb.append("　原因：").append(conflict.path("why").asText()).append("\n");
        }

        appendList(sb, r, "blockers", "阻礙");
        appendUncertainties(sb, r);
        appendFooter(sb, task, o);
        return sb.toString();
    }

    private static String statusIcon(String status) {
        return switch (status) {
            case "SUCCESS" -> "✅";
            case "PARTIAL" -> "⚠️";
            case "FAILED"  -> "❌";
            default        -> "•";
        };
    }

    private static String changeMark(String change) {
        return switch (change) {
            case "ADDED"   -> "＋";
            case "DELETED" -> "－";
            default        -> "✎";
        };
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ------------------------------------------------------------------ QA

    private static String renderQaPlan(Task task, StageOutcome o) {
        StringBuilder sb = new StringBuilder("*任務 #" + task.id() + "　實作前測試計畫*\n");
        sb.append(text(o.result(), "summary")).append("\n");
        for (JsonNode s : o.result().path("scenarios")) {
            sb.append("\n• ").append(s.path("id").asText()).append("：")
              .append(clip(s.path("criterion").asText(), 200)).append("\n")
              .append("方式：").append(clip(s.path("method").asText(), 200)).append("\n")
              .append("預期：").append(clip(s.path("expected_result").asText(), 200)).append("\n");
        }
        sb.append("\n完整資料、步驟與環境需求已保存於 qa_plan 報告；此時尚未執行驗收。\n");
        appendFooter(sb, task, o);
        return sb.toString();
    }

    private static String renderQa(Task task, StageOutcome o) {
        JsonNode r = o.result();
        String verdict = text(r, "verdict");
        StringBuilder sb = new StringBuilder();

        for (JsonNode s : r.path("scenario_results")) {
            sb.append("• ").append(s.path("scenario_id").asText()).append("：")
              .append(s.path("status").asText()).append("\n")
              .append(clip(s.path("actual_result").asText(), 200)).append("\n證據：")
              .append(clip(s.path("evidence").asText(), 250)).append("\n");
        }

        sb.append(verdictIcon(verdict)).append(" *任務 #").append(task.id())
          .append("　驗收結果：").append(verdict).append("*\n\n");
        sb.append(text(r, "summary")).append("\n");

        sb.append("\n*建置*：").append(r.path("verify_passed").asBoolean(false) ? "✅ 通過" : "❌ 未通過");
        sb.append("\n");
        if (r.hasNonNull("verify_detail") && !r.path("verify_detail").asText().isBlank()) {
            sb.append("```\n").append(clip(r.path("verify_detail").asText(), 700)).append("\n```\n");
        }

        // 逐條驗收是 QA 的核心產出，即使全部 COVERED 也要顯示 ——
        // 看到「每一條都被哪個測試驗到」才是人類敢放行的依據
        JsonNode criteria = r.get("criteria_results");
        if (criteria != null && criteria.isArray() && !criteria.isEmpty()) {
            sb.append("\n*驗收條件逐條對照*\n");
            for (JsonNode c : criteria) {
                sb.append("　").append(coverMark(c.path("status").asText()))
                  .append(" ").append(c.path("criterion").asText()).append("\n");
                String ev = c.path("evidence").asText("");
                if (!ev.isBlank()) sb.append("　　　_").append(clip(ev, 200)).append("_\n");
            }
        }

        JsonNode findings = r.get("findings");
        if (findings != null && findings.isArray() && !findings.isEmpty()) {
            sb.append("\n*發現的問題*\n");
            for (JsonNode fnode : findings) {
                sb.append("　").append(severityMark(fnode.path("severity").asText()))
                  .append(" ").append(fnode.path("detail").asText());
                String path = fnode.path("path").asText("");
                if (!path.isBlank()) sb.append("　`").append(path).append("`");
                sb.append("\n");
            }
        }

        JsonNode added = r.get("tests_added");
        if (added != null && added.isArray() && !added.isEmpty()) {
            sb.append("\n*QA 補上的測試*（").append(added.size()).append(" 項）\n");
            for (JsonNode t : added) {
                sb.append("　• `").append(t.path("path").asText()).append("`　")
                  .append(t.path("covers").asText()).append("\n");
            }
        }

        appendUncertainties(sb, r);
        appendFooter(sb, task, o);
        return sb.toString();
    }

    private static String verdictIcon(String verdict) {
        return switch (verdict) {
            case "PASS"               -> "✅";
            case "PASS_WITH_CONCERNS" -> "⚠️";
            case "FAIL"               -> "❌";
            case "APPROVE"            -> "✅";
            case "COMMENT"            -> "💬";
            case "REQUEST_CHANGES"    -> "🛑";
            default                   -> "•";
        };
    }

    private static String coverMark(String status) {
        return switch (status) {
            case "COVERED"     -> "✅";
            case "PARTIAL"     -> "⚠️";
            case "NOT_COVERED" -> "❌";
            default            -> "•";
        };
    }

    private static String severityMark(String severity) {
        return switch (severity) {
            case "HIGH"   -> "🔴";
            case "MEDIUM" -> "🟠";
            default       -> "🟡";
        };
    }

    // ------------------------------------------------------------- Code Review

    /**
     * Code Review 的結果。
     *
     * 顯示核實狀態及阻礙；空 findings 不代表審查通過。
     */
    private static String renderCodeReview(Task task, StageOutcome o) {
        JsonNode r = o.result();
        String verdict = text(r, "verdict");
        StringBuilder sb = new StringBuilder();

        sb.append(verdictIcon(verdict)).append(" *任務 #").append(task.id())
          .append("　Code Review：").append(verdict).append("*\n\n");
        sb.append(text(r, "summary")).append("\n");

        JsonNode findings = r.get("findings");
        if (findings != null && findings.isArray() && !findings.isEmpty()) {
            sb.append("\n*Review 意見*\n");
            for (JsonNode f : findings) {
                sb.append("　").append(severityMark(f.path("severity").asText()));
                String file = f.path("file").asText("");
                if (!file.isBlank()) {
                    sb.append(" `").append(file);
                    if (f.hasNonNull("line") && f.path("line").asInt(0) > 0) {
                        sb.append(":").append(f.path("line").asInt());
                    }
                    sb.append("`");
                }
                sb.append(" ").append(clip(f.path("issue").asText(""), 300)).append("\n");
                String sug = f.path("suggestion").asText("");
                if (!sug.isBlank()) sb.append("　　　_建議：").append(clip(sug, 250)).append("_\n");
            }
        } else {
            sb.append("\n沒有提出意見。\n");
        }

        sb.append("\n核實狀態：").append(text(r, "verification_status"))
          .append("　把握度：").append(text(r, "confidence")).append("\n");
        appendList(sb, r, "blockers", "核實阻礙與所需處理");
        sb.append("\n_未核實、把握度不足或有阻斷問題時，會向上回報並禁止推分支與開 PR。_");
        return sb.toString();
    }

    // ------------------------------------------------------------------ 泛用

    private static String renderGeneric(Task task, StageSpec stage, StageOutcome o) {
        StringBuilder sb = new StringBuilder();
        sb.append("*任務 #").append(task.id()).append("　").append(stage.displayName())
          .append(" 結果*\n\n").append(SlackReport.render(o.result()));
        appendFooter(sb, task, o);
        return sb.toString();
    }

    // ------------------------------------------------------------------ 共用片段

    private static void appendUncertainties(StringBuilder sb, JsonNode r) {
        JsonNode unc = r.get("uncertainties");
        if (unc != null && unc.isArray() && !unc.isEmpty()) {
            sb.append("\n*不確定之處*\n");
            for (JsonNode u : unc) sb.append("　• ").append(u.asText()).append("\n");
        }
    }

    private static void appendList(StringBuilder sb, JsonNode r, String field, String label) {
        JsonNode a = r.get(field);
        if (a == null || !a.isArray() || a.isEmpty()) return;
        sb.append("\n*").append(label).append("*\n");
        for (JsonNode x : a) sb.append("　• ").append(x.asText()).append("\n");
    }

    private static void appendFooter(StringBuilder sb, Task task, StageOutcome o) {
        sb.append("\n_耗時 ").append(o.elapsedSec()).append(" 秒");
        if (o.costUsd() != null) {
            sb.append("　本階段 US$").append(String.format("%.4f", o.costUsd()));
        }
        sb.append("　任務累計 US$").append(String.format("%.4f", task.totalCost()));
        if (task.redoCount() > 0) sb.append("　第 ").append(task.redoCount()).append(" 次重做");
        sb.append("_");
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : "－";
    }

    private static String array(JsonNode n, String field) {
        JsonNode a = n.get(field);
        if (a == null || !a.isArray() || a.isEmpty()) return "";
        List<String> items = new ArrayList<>();
        a.forEach(x -> items.add("`" + x.asText() + "`"));
        return String.join("、", items);
    }
}
