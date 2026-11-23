package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import static com.zipe.aiteam.flow.TeamDefinition.*;

/** Opt-in QA after deployment, backed by the sandbox executor journal. */
final class SandboxE2e {
    static final String OPERATION = "sandbox-e2e";
    private static final String GENERATION = "_sandbox_e2e_deployment";
    private static final String EVIDENCE = "_sandbox_e2e_receipt";

    private SandboxE2e() {}

    static Operation operation(TeamDefinition team) {
        return team.project().operations() == null ? null : team.project().operations().get(OPERATION);
    }

    static boolean isQa(TeamDefinition team, String node) {
        Operation operation = operation(team);
        return operation != null && operation.nodes().contains(node);
    }

    static List<String> deployments(TeamDefinition team, String qa) {
        return team.nodes().entrySet().stream().filter(e -> ServiceDelivery.postDevelopment(e.getValue())
                && qa.equals(e.getValue().next())).map(java.util.Map.Entry::getKey).toList();
    }

    static void validate(TeamDefinition team) {
        Operation operation = operation(team);
        if (operation == null) return;
        var target = team.project().serviceTarget();
        TeamCatalog.require(team.version() >= 2 && target != null && target.checks() != null
                && team.project().requirePlanApproval(), "沙箱 E2E 須有授權部署目標與測試計畫核准");
        TeamCatalog.require(operation.nodes().size() == 1 && ".".equals(operation.directory())
                && operation.argv().size() == 4 && "python3".equals(operation.argv().getFirst())
                && operation.argv().get(1).endsWith("/skills/service-build/scripts/client.py")
                && "exec".equals(operation.argv().get(2))
                && operation.scriptSha256() != null && operation.scriptSha256().matches("[0-9a-f]{64}")
                && operation.argv().get(3).matches("[a-zA-Z0-9_-]+(/[a-zA-Z0-9_.-]+)+\\.sh")
                && java.util.Arrays.stream(operation.argv().get(3).split("/")).noneMatch(p -> p.startsWith(".")),
                "沙箱 E2E 操作須在工作區根目錄透過固定 client.py exec 執行已配置的可提交腳本");
        var nodes = team.nodes();
        var qaId = operation.nodes().getFirst();
        Node qa = nodes.get(qaId);
        List<String> deploys = deployments(team, qaId);
        Operation start = team.project().operations().get(target.checks().get("start"));
        TeamCatalog.require(qa.gate() == Gate.VERIFICATION && qa.scope() == Scope.LIMITED
                && start != null && operation.writes().size() == 1 && operation.writes().equals(start.writes())
                && deploys.size() == 1 && qa.requires().contains(deploys.getFirst()),
                "沙箱 E2E 需於唯一的部署節點之後由 QA 驗收，操作寫入須限於既有佇列");
        String deploy = deploys.getFirst();
        Node deployment = nodes.get(deploy);
        Node review = nodes.get(qa.next());
        var approvals = nodes.values().stream().filter(n -> n.kind() == Kind.APPROVAL).toList();
        var implementations = deployment.requires().stream().map(nodes::get).filter(n -> n.gate() == Gate.IMPLEMENTATION).toList();
        TeamCatalog.require(!implementations.isEmpty()
                && implementations.stream().allMatch(n -> n.requires().contains(qa.plan()) && !qa.role().equals(n.role()))
                && qa.role().equals(nodes.get(qa.plan()).role())
                && !deployment.requires().contains(qaId) && review != null && review.gate() == Gate.REVIEW
                && review.requires().contains(qaId) && !deployment.role().equals(qa.role()) && !approvals.isEmpty()
                && approvals.stream().allMatch(n -> n.requires().containsAll(List.of(deploy, qaId, qa.next()))),
                "沙箱 E2E 須由獨立 QA 執行，且部署、QA 與審查必須列為發布前置");
    }

    static String planReason(FlowRun flow, JsonNode plan) {
        if (operation(flow.snapshot.definition()) == null) return null;
        for (JsonNode scenario : plan.path("scenarios"))
            for (JsonNode id : scenario.path("operation_ids")) if (OPERATION.equals(id.asText())) return null;
        return "測試計畫必須包含使用 sandbox-e2e 的真實沙箱驗收情境";
    }

    static boolean verifiesAfterDeploy(FlowRun flow, String deploy, JsonNode issue) {
        if (!"REPAIR".equals(issue.path("scope").asText())) return false;
        var team = flow.snapshot.definition();
        Node deployment = team.nodes().get(deploy);
        if (deployment == null || !isQa(team, deployment.next())) return false;
        Node qa = team.nodes().get(deployment.next());
        if (PlanApproval.blockingReason(flow, qa.plan()) != null) return false;
        var covered = new java.util.HashSet<String>();
        var sandboxCovered = new java.util.HashSet<String>();
        for (JsonNode scenario : flow.receipts.get(qa.plan()).path("scenarios")) {
            scenario.path("requirement_ids").forEach(ref -> covered.add(ref.asText()));
            for (JsonNode id : scenario.path("operation_ids"))
                if (OPERATION.equals(id.asText())) scenario.path("requirement_ids").forEach(ref -> sandboxCovered.add(ref.asText()));
        }
        JsonNode requirements = issue.path("requirement_ids");
        if (!requirements.isArray() || requirements.isEmpty()) return false;
        boolean needsSandbox = false;
        for (JsonNode id : requirements) if (!covered.contains(id.asText())) return false;
        for (JsonNode id : requirements) if (sandboxCovered.contains(id.asText())) needsSandbox = true;
        return needsSandbox;
    }

    static String notice(FlowRun flow, Node node) {
        var team = flow.snapshot.definition();
        if (node.gate() == Gate.SERVICE && isQa(team, node.next()))
            return "\n本輪只負責部署與健康檢查，完成後交配置的 QA 節點（" + node.next() + "）執行已核准計畫。"
                    + "target 只填配置的目標名稱 " + team.project().serviceTarget().name() + "，網址與容器說明寫在摘要。"
                    + "checks 的 start 證據須填固定執行器的目前 HEAD 成功 job ID；HEAD 改變時執行新版 start。"
                    + "不要在部署階段要求 sandbox-e2e 操作，也不要重送同版本已成功的啟動作業。"
                    + "交後續 QA 驗證的 REPAIR 問題可如實留 OPEN；部署本身成功時回 COMPLETED。"
                    + "後續 QA 尚未執行寫在摘要及 issue_checks，不列為部署本身的 uncertainties；"
                    + "部署或健康檢查確有未核實事項仍須 BLOCKED，不得填 PASS。";
        if (operation(team) == null) return "";
        if (node.gate() == Gate.TEST_PLAN)
            return "\n計畫須在實作前定義至少一個 sandbox-e2e 情境，列真實前後端的測試資料、操作、預期及反例。授權沙箱先部署後才由 QA 執行。";
        if (isQa(team, flow.node))
            return "\n本輪是部署後的沙箱 QA。使用已配置的 sandbox-e2e 操作對已部署服務執行計畫。依實際斷言與執行器收據回報，不能把本機預覽或 mock 結果當沙箱驗收。";
        return "";
    }

    static String deploymentReason(FlowRun flow, String nodeId, JsonNode report, Path home, String task, String head) {
        var team = flow.snapshot.definition();
        Node node = team.nodes().get(nodeId);
        if (node == null || !ServiceDelivery.postDevelopment(node) || !isQa(team, node.next())) return null;
        var target = team.project().serviceTarget();
        Operation start = team.project().operations().get(target.checks().get("start"));
        var argv = start.argv();
        if ((argv.size() != 3 && argv.size() != 4)
                || !argv.get(1).endsWith("/skills/service-build/scripts/client.py")
                || !(argv.size() == 3 ? "start" : "exec").equals(argv.get(2)))
            return "沙箱 QA 前的啟動操作須使用可核對收據的固定服務執行器";
        String script = argv.size() == 4 ? argv.get(3) : "";
        String evidence = "";
        for (JsonNode check : report.path("checks"))
            if ("start".equals(check.path("name").asText())) evidence = check.path("evidence").asText();
        var jobs = new OperationJobs(home).read(task, nodeId, head).jobs().stream()
                .filter(job -> "SUCCEEDED".equals(job.state()) && target.name().equals(job.target())
                        && argv.get(2).equals(job.action()) && script.equals(job.script())
                        && job.result().path("source_sha256").asText().matches("[0-9a-f]{64}"))
                .toList();
        if (OperationJobs.reported(jobs, target.name(), evidence, "") == null)
            return "缺少目前提交的可信沙箱啟動收據；請執行目前 HEAD 的 start 並在 checks.evidence 引用成功 job ID";
        return null;
    }

    static void stampDeployment(FlowRun flow, JsonNode report) {
        Operation operation = operation(flow.snapshot.definition());
        if (operation != null && deployments(flow.snapshot.definition(), operation.nodes().getFirst()).contains(flow.node))
            ((ObjectNode) report).put(GENERATION, UUID.randomUUID().toString());
    }

    static String attest(FlowRun flow, JsonNode report, Path home, String task, String head) {
        var team = flow.snapshot.definition();
        if (!isQa(team, flow.node)) return null;
        var output = (ObjectNode) report;
        output.remove(EVIDENCE);
        String deploy = deployments(team, flow.node).getFirst();
        String generation = generation(flow, deploy);
        if (generation.isBlank() || !Objects.equals(head, flow.heads.get(deploy))) return "缺少目前提交的沙箱部署收據";
        String target = team.project().serviceTarget().name();
        String script = operation(team).argv().get(3);
        var job = new OperationJobs(home).read(task, flow.node, head).jobs().stream()
                .filter(j -> "exec".equals(j.action()) && target.equals(j.target()) && script.equals(j.script())
                        && generation.equals(j.deploymentGeneration()))
                .reduce((a, b) -> b).orElse(null);
        if (job == null || !"SUCCEEDED".equals(job.state()) || !executedScenarios(job.result(), head))
            return "缺少同版本、同次部署的沙箱 E2E 通過收據，或實際執行情境數為零";
        output.putObject(EVIDENCE).put("job", job.id()).put("deployment", generation).put("head", head);
        return null;
    }

    static String publicationReason(FlowRun flow, String head) {
        Operation operation = operation(flow.snapshot.definition());
        if (operation == null) return null;
        String qa = operation.nodes().getFirst();
        String deploy = deployments(flow.snapshot.definition(), qa).getFirst();
        JsonNode result = flow.receipts.get(qa);
        String generation = generation(flow, deploy);
        if (head == null || result == null || generation.isBlank() || !head.equals(flow.heads.get(deploy))
                || !generation.equals(result.path(EVIDENCE).path("deployment").asText())
                || !head.equals(result.path(EVIDENCE).path("head").asText()))
            return "缺少目前部署版本的沙箱 E2E 通過收據";
        return null;
    }

    private static String generation(FlowRun flow, String deploy) {
        JsonNode receipt = flow.receipts.get(deploy);
        return receipt == null ? "" : receipt.path(GENERATION).asText();
    }

    private static boolean executedScenarios(JsonNode result, String head) {
        if (result == null || !head.matches("[0-9a-f]{40,64}")
                || !result.path("source_sha256").asText().matches("[0-9a-f]{64}")) return false;
        String marker = "EXEC_OUTPUT: AI_TEAM_SANDBOX_E2E_PASS " + head + " ";
        for (JsonNode line : result.path("evidence"))
            if (line.asText().startsWith(marker) && line.asText().substring(marker.length()).matches("[1-9][0-9]{0,8}")) return true;
        return false;
    }
}
