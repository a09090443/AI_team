package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceDeliveryTest {
    final ObjectMapper mapper = new ObjectMapper();

    @Test void sandboxQaRequiresARealNonemptyRunForTheCurrentDeployment(@org.junit.jupiter.api.io.TempDir Path home) throws Exception {
        FlowRun flow = flow(true);
        Path runner = Files.createDirectories(home.resolve("qa")).resolve("sandbox-e2e.sh");
        String runnerText = "#!/bin/bash\nexit 0\n";
        Files.writeString(runner, runnerText);
        ObjectNode definition = mapper.valueToTree(flow.snapshot.definition());
        ObjectNode project = (ObjectNode) definition.path("project");
        project.put("requirePlanApproval", true);
        if (java.util.stream.StreamSupport.stream(project.path("requiredTools").spliterator(), false)
                .noneMatch(tool -> "python3".equals(tool.asText()))) project.withArray("requiredTools").add("python3");
        ObjectNode operations = (ObjectNode) project.path("operations");
        ((ObjectNode) operations.path("service-start")).putArray("writes").add("ai-team-sandbox");
        ((ObjectNode) operations.path("service-start")).putArray("argv")
                .add("python3").add("/opt/skills/service-build/scripts/client.py").add("exec").add("sit/start.sh");
        operations.set("sandbox-e2e", mapper.readTree("""
                {"nodes":["verification"],"directory":".","argv":["python3","/opt/skills/service-build/scripts/client.py","exec","qa/sandbox-e2e.sh"],
                 "writes":["ai-team-sandbox"],"scriptSha256":"%s"}
                """.formatted(IndependentVerifier.hash(runner))));
        ObjectNode nodes = (ObjectNode) definition.path("nodes");
        for (String id : java.util.List.of("service", "deploy")) {
            var paths = ((ObjectNode) nodes.path(id)).withArray("writablePaths");
            paths.add("ai-team-sandbox/**");
        }
        ((ObjectNode) nodes.path("deploy")).put("next", "verification").putArray("requires").add("intake").add("implementation");
        ((ObjectNode) nodes.path("verification")).put("localTestNetwork", false).withArray("requires").add("deploy");
        ((ObjectNode) nodes.path("verification")).withArray("writablePaths").add("ai-team-sandbox/**");
        ((ObjectNode) nodes.path("approval")).withArray("requires").add("deploy");
        TeamDefinition team = mapper.treeToValue(definition, TeamDefinition.class);
        TeamCatalog.validate(team);
        flow.snapshot = new TeamDefinition.Snapshot("sandbox", team, "/repo", java.util.Map.of());
        var workspace = mock(com.zipe.aiteam.workspace.WorkspaceManager.class);
        String deploymentHead = "a".repeat(40);
        when(workspace.pathFor("42")).thenReturn(home);
        when(workspace.headSha("42")).thenReturn(deploymentHead);
        when(workspace.readCommittedText("42", deploymentHead, "qa/sandbox-e2e.sh", 1_048_576))
                .thenReturn("old committed runner\n", runnerText, runnerText);
        assertThatThrownBy(() -> SandboxE2e.requireDeploymentPin(flow, team.nodes().get("deploy"), workspace, "42"))
                .hasMessageContaining("尚未執行部署");
        SandboxE2e.requireDeploymentPin(flow, team.nodes().get("deploy"), workspace, "42");
        Files.writeString(runner, "uncommitted change\n");
        assertThatThrownBy(() -> SandboxE2e.requireDeploymentPin(flow, team.nodes().get("deploy"), workspace, "42"))
                .hasMessageContaining("尚未執行部署");
        flow.acceptanceOwner = "qa-owner";
        flow.baselineRevision = "baseline";
        var plan = mapper.readTree("""
                {"scenarios":[{"id":"browser","requirement_ids":["R1"],"operation_ids":["sandbox-e2e"]}]}
                """);
        flow.receipts.put("plan", plan);
        assertThat(SandboxE2e.planReason(flow, plan)).isNull();
        assertThat(SandboxE2e.planReason(flow, mapper.readTree("""
                {"scenarios":[{"operation_ids":["sandbox-e2e"]},{"operation_ids":["e2e"]}]}
                """))).contains("不能以本機 e2e 代替");
        var issue = (ObjectNode) mapper.readTree("{\"scope\":\"REPAIR\",\"requirement_ids\":[\"R1\"]}");
        assertThat(SandboxE2e.verifiesAfterDeploy(flow, "deploy", issue)).isFalse();
        flow.planApprovals.put("plan", PlanApproval.approve(flow, "plan", Traceability.digest(plan), "qa-owner"));
        assertThat(SandboxE2e.verifiesAfterDeploy(flow, "deploy", issue)).isTrue();
        issue.withArray("requirement_ids").add("not-covered");
        assertThat(SandboxE2e.verifiesAfterDeploy(flow, "deploy", issue)).isFalse();
        // 同一問題的功能由沙箱驗證、文件由獨立情境驗證，不能要求文件也假掛到瀏覽器情境。
        var mixedPlan = mapper.readTree("""
                {"scenarios":[
                 {"id":"browser","requirement_ids":["R1"],"operation_ids":["sandbox-e2e"]},
                 {"id":"docs","requirement_ids":["R2"],"operation_ids":["inspect-content"]}]}
                """);
        flow.receipts.put("plan", mixedPlan);
        assertThat(SandboxE2e.verifiesAfterDeploy(flow, "deploy", issue)).isFalse(); // 舊核准不能沿用
        flow.planApprovals.put("plan", PlanApproval.approve(flow, "plan", Traceability.digest(mixedPlan), "qa-owner"));
        issue.putArray("requirement_ids").add("R1").add("R2");
        assertThat(SandboxE2e.verifiesAfterDeploy(flow, "deploy", issue)).isTrue();
        issue.withArray("requirement_ids").add("R3");
        assertThat(SandboxE2e.verifiesAfterDeploy(flow, "deploy", issue)).isFalse();
        issue.putArray("requirement_ids").add("R2");
        assertThat(SandboxE2e.verifiesAfterDeploy(flow, "deploy", issue)).isFalse(); // 無關沙箱情境不放行
        issue.putArray("requirement_ids").add("R1").add("R2");
        issue.put("scope", "DECISION");
        assertThat(SandboxE2e.verifiesAfterDeploy(flow, "deploy", issue)).isFalse();
        issue.put("scope", "REPAIR").put("id", "I1");
        flow.snapshot = new TeamDefinition.Snapshot("sandbox", team, "/repo", java.util.Map.of(IssueTracker.CONTRACT, "v1"));
        flow.receipts.put("intake", mapper.readTree("""
                {"requirements":[{"id":"R1","acceptance_items":[{"id":"A1"}]},
                                 {"id":"R2","acceptance_items":[{"id":"A2"}]}]}
                """));
        flow.issueInventory.put("I1", issue);
        var openDeployment = report();
        openDeployment.putArray("issue_updates");
        openDeployment.put("issue_revision", IssueTracker.revision(flow)).putArray("issue_checks")
                .addObject().put("id", "I1").put("status", "OPEN").put("evidence", "部署成功，交已核准計畫的 QA 實測");
        flow.node = "deploy";
        assertThat(IssueTracker.reportReason(team.nodes().get("deploy"), openDeployment, flow)).isNull();
        assertThat(ServiceDelivery.blockingReason(openDeployment, flow)).isNull();
        var unhealthy = openDeployment.deepCopy();
        ((ObjectNode) unhealthy.path("checks").get(1)).put("status", "FAIL").put("exit_code", 1);
        assertThat(ServiceDelivery.blockingReason(unhealthy, flow)).contains("服務檢查失敗");
        var uncertain = openDeployment.deepCopy();
        uncertain.withArray("uncertainties").add("部署健康狀態尚未核實");
        assertThat(ServiceDelivery.blockingReason(uncertain, flow)).contains("未核實事項");
        assertThat(SandboxE2e.notice(flow, team.nodes().get("deploy"))).contains("交配置的 QA 節點", "回 COMPLETED", "仍須 BLOCKED");
        assertThat(IssueTracker.reportReason(team.nodes().get("verification"), openDeployment, flow)).contains("問題尚未解決");
        assertThat(IssueTracker.reportReason(team.nodes().get("review"), openDeployment, flow)).contains("問題尚未解決");
        flow.node = "deploy";
        String head = "a".repeat(40), id = "b".repeat(32);
        var deployment = report();
        String startId = "d".repeat(32);
        ((ObjectNode) deployment.path("checks").get(0)).put("evidence", "job " + startId);
        var start = mapper.createObjectNode().put("version", 1).put("task", "42").put("node", "deploy")
                .put("id", startId).put("target", "sit-host").put("action", "exec").put("script", "sit/start.sh")
                .put("state", "SUCCEEDED").put("verified", true).put("source_head", "e".repeat(40))
                .put("source_sha256", "f".repeat(64));
        start.putObject("result").put("id", startId).put("target", "sit-host").put("action", "exec")
                .put("source_sha256", "f".repeat(64)).put("exit_code", 0);
        Path startJournal = Files.createDirectories(home.resolve("operation-jobs/42")).resolve(startId + ".json");
        mapper.writeValue(startJournal.toFile(), start);
        assertThat(SandboxE2e.deploymentReason(flow, "deploy", deployment, home, "42", head)).contains("目前提交");
        start.put("source_head", head).put("script", "sit/health.sh");
        mapper.writeValue(startJournal.toFile(), start);
        assertThat(SandboxE2e.deploymentReason(flow, "deploy", deployment, home, "42", head)).contains("目前提交");
        start.put("script", "sit/start.sh");
        mapper.writeValue(startJournal.toFile(), start);
        assertThat(SandboxE2e.deploymentReason(flow, "deploy", deployment, home, "42", head)).isNull();
        SandboxE2e.stampDeployment(flow, deployment);
        flow.receipts.put("deploy", deployment);
        flow.heads.put("deploy", head);
        flow.node = "verification";
        var qa = mapper.createObjectNode();
        assertThat(SandboxE2e.attest(flow, qa, home, "42", head)).contains("收據");
        var job = mapper.createObjectNode().put("version", 1).put("id", id).put("task", "42")
                .put("action", "exec").put("state", "SUCCEEDED").put("verified", true).put("node", "verification")
                .put("source_head", head).put("target", "sit-host").put("script", "qa/sandbox-e2e.sh")
                .put("source_sha256", "c".repeat(64))
                .put("deployment_generation", deployment.path("_sandbox_e2e_deployment").asText());
        var evidence = job.putObject("result").put("id", id).put("action", "exec").put("target", "sit-host")
                .put("source_sha256", "c".repeat(64)).put("exit_code", 0).putArray("evidence");
        Path journal = Files.createDirectories(home.resolve("operation-jobs/42")).resolve(id + ".json");
        evidence.add("EXEC_OUTPUT: AI_TEAM_SANDBOX_E2E_PASS " + head + " 0");
        mapper.writeValue(journal.toFile(), job);
        assertThat(SandboxE2e.attest(flow, qa, home, "42", head)).contains("為零");
        evidence.removeAll().add("EXEC_OUTPUT: AI_TEAM_SANDBOX_E2E_PASS " + head + " 3");
        mapper.writeValue(journal.toFile(), job);
        assertThat(SandboxE2e.attest(flow, qa, home, "42", head)).isNull();
        flow.receipts.put("verification", qa);
        assertThat(SandboxE2e.publicationReason(flow, head)).isNull();
        flow.node = "deploy";
        SandboxE2e.stampDeployment(flow, deployment);
        assertThat(SandboxE2e.publicationReason(flow, head)).contains("沙箱 E2E");
    }
    FlowRun flow(boolean target) throws Exception {
        ObjectNode d = (ObjectNode) mapper.readTree(Files.readString(Path.of("teams/gradle-fullstack.json")));
        if (target) {
            ((ObjectNode)d.path("project")).set("serviceTarget", mapper.readTree("""
                {"name":"sit-host","checks":{"start":"service-start","health":"service-health"}}
                """));
            var operations = (ObjectNode)d.path("project").path("operations");
            for (String id : new String[]{"service-start", "service-health"}) operations.set(id, mapper.readTree("""
                {"nodes":["service","deploy"],"directory":".","argv":["git","status"],"writes":[]}
                """));
        } else ((ObjectNode)d.path("project")).putNull("serviceTarget");
        TeamDefinition definition = mapper.treeToValue(d, TeamDefinition.class);
        TeamCatalog.validate(definition);
        FlowRun f = new FlowRun();
        f.snapshot = new TeamDefinition.Snapshot("revision", definition, "/repo", java.util.Map.of());
        f.node = "service";
        f.receipts.put("intake", mapper.readTree("{\"requirements\":[{\"acceptance_items\":[{\"id\":\"A1\"}]}]}"));
        return f;
    }
    ObjectNode report() throws Exception {
        return (ObjectNode)mapper.readTree("""
            {"execution_status":"COMPLETED","failure_report":null,"service_status":"RUNNING",
             "target":"sit-host","product_code_changed":false,"uncertainties":[],
             "checks":[{"name":"start","operation_id":"service-start","status":"PASS","exit_code":0,"evidence":"log 1","acceptance_ids":["A1"]},
                       {"name":"health","operation_id":"service-health","status":"PASS","exit_code":0,"evidence":"log 2","acceptance_ids":["A1"]}]}
            """);
    }
    @Test void serviceRouteHasNoQaPlanButKeepsDevelopmentGate() throws Exception {
        var f = flow(true);
        assertThat(f.snapshot.definition().nodes().get("service").requires()).containsExactly("intake");
        assertThat(f.snapshot.definition().nodes().get("implementation").requires()).contains("plan");
        assertThat(FlowGates.blockingReason(f.snapshot.definition().nodes().get("service"), report(), f)).isNull();
    }
    @Test void absentTargetCannotBeClaimedAsRunning() throws Exception {
        assertThat(ServiceDelivery.blockingReason(report(), flow(false))).contains("尚未配置", "主機");
    }
    @Test void intakeIsToldServiceRouteIsOffUntilTargetConfigured() throws Exception {
        var off = flow(false);
        var intake = off.snapshot.definition().nodes().get("intake");
        assertThat(ServiceDelivery.notice(off, intake)).contains("尚未由管理者設定部署目標", "不要選 service 路線");
        var on = flow(true);
        assertThat(ServiceDelivery.notice(on, intake)).contains("服務建置與開發分流", "sit-host").doesNotContain("不要選 service 路線");
    }
    @Test void wrongHostOrFilesOnlyCannotComplete() throws Exception {
        assertThat(ServiceDelivery.blockingReason(report().put("target", "other-host"), flow(true))).contains("目標");
        assertThat(ServiceDelivery.blockingReason(report().put("service_status", "NOT_RUNNING"), flow(true))).contains("尚未");
    }
    @Test void failedOrMissingHealthCheckCannotComplete() throws Exception {
        var r = report(); ((ObjectNode)r.path("checks").get(1)).put("exit_code", 1);
        assertThat(ServiceDelivery.blockingReason(r, flow(true))).contains("失敗");
        r = report(); ((com.fasterxml.jackson.databind.node.ArrayNode)r.path("checks")).remove(1);
        assertThat(ServiceDelivery.blockingReason(r, flow(true))).contains("全部");
    }
    @Test void unknownOperationAndMissingEvidenceCannotComplete() throws Exception {
        var r = report(); ((ObjectNode)r.path("checks").get(1)).put("operation_id", "invented");
        assertThat(ServiceDelivery.blockingReason(r, flow(true))).contains("未對應");
        r = report(); ((ObjectNode)r.path("checks").get(1)).put("evidence", "");
        assertThat(ServiceDelivery.blockingReason(r, flow(true))).contains("證據");
    }
    @Test void codeChangesAndUncertaintyMustNotBypassDevelopment() throws Exception {
        assertThat(ServiceDelivery.blockingReason(report().put("product_code_changed", true), flow(true))).contains("開發流程");
        var r = report(); r.withArray("uncertainties").add("login not tested");
        assertThat(ServiceDelivery.blockingReason(r, flow(true))).contains("未核實");
    }
    @Test void everyAcceptanceItemMustBeCovered() throws Exception {
        var f = flow(true);
        ((ObjectNode)f.receipts.get("intake").path("requirements").get(0)).withArray("acceptance_items")
                .addObject().put("id", "A2");
        assertThat(ServiceDelivery.blockingReason(report(), f)).contains("全部需求");
    }
    @Test void deploymentAfterDevelopmentReturnsToReviewAndCoversOnlyDeploymentItems() throws Exception {
        // #1006：開發完成後審查發現部署未做，分流沒有能進 SERVICE 的路線，任務只能停下等真人。
        var f = flow(true);
        var nodes = f.snapshot.definition().nodes();
        assertThat(nodes.get("triage").routes()).containsEntry("service", "deploy");
        assertThat(ServiceDelivery.postDevelopment(nodes.get("deploy"))).isTrue();
        assertThat(ServiceDelivery.postDevelopment(nodes.get("service"))).isFalse();
        assertThat(ServiceDelivery.notice(f, nodes.get("triage"))).contains("選 service 路線交部署階段");
        assertThat(ServiceDelivery.notice(f, nodes.get("deploy"))).contains("開發後部署", "不提交");
        assertThat(ServiceDelivery.notice(f, nodes.get("service"))).doesNotContain("開發後部署").contains("代為提交");
        // 其他驗收項目已由驗證涵蓋，部署只需對應部署相關的項目；只部署的 service 仍要全部涵蓋。
        ((ObjectNode)f.receipts.get("intake").path("requirements").get(0)).withArray("acceptance_items").addObject().put("id", "A2");
        assertThat(ServiceDelivery.blockingReason(report(), f)).contains("全部需求");
        f.node = "deploy";
        assertThat(ServiceDelivery.blockingReason(report(), f)).isNull();
        var unknown = report(); ((ObjectNode)unknown.path("checks").get(0)).putArray("acceptance_ids").add("A9");
        assertThat(ServiceDelivery.blockingReason(unknown, f)).contains("未知驗收項目");
    }
    @Test void pendingCommitsAndProductDefectsLeadBackToDevelopment() throws Exception {
        // #1007：承接任務帶著未交付的開發 commit，intake 選了只部署路線；部署查出產品 bug 後分流也回不了開發。
        var f = flow(true);
        var nodes = f.snapshot.definition().nodes();
        assertThat(ServiceDelivery.standaloneRoute(f, nodes.get("intake"))).isEqualTo("service");
        assertThat(ServiceDelivery.standaloneRoute(f, nodes.get("triage"))).isNull();
        assertThat(ServiceDelivery.notice(f, nodes.get("intake"), java.util.List.of("9b3e4fe 新增範例", "eb97fef 受保護檔案")))
                .contains("2 個尚未合併到基準的 commit", "9b3e4fe 新增範例", "不開 PR", "部署階段（deploy）");
        assertThat(ServiceDelivery.notice(f, nodes.get("intake"))).doesNotContain("未交付的修改");
        assertThat(nodes.get("service_triage").routes()).containsEntry("implementation", "plan");
        assertThat(ServiceDelivery.notice(f, nodes.get("service_triage"))).contains("選 implementation 路線改走開發流程");
        assertThat(ServiceDelivery.notice(f, nodes.get("intake"))).doesNotContain("選 implementation 路線改走開發流程");
    }
    @Test void deploymentAfterDevelopmentMustReturnToReview() throws Exception {
        ObjectNode d = (ObjectNode) mapper.readTree(Files.readString(Path.of("teams/gradle-fullstack.json")));
        ((ObjectNode)d.path("nodes").path("deploy")).put("next", "approval");
        assertThatThrownBy(() -> TeamCatalog.validate(mapper.treeToValue(d, TeamDefinition.class))).hasMessageContaining("回到審查節點");
        ((ObjectNode)d.path("nodes").path("deploy")).put("next", "review").putObject("routes").put("human", "human");
        assertThatThrownBy(() -> TeamCatalog.validate(mapper.treeToValue(d, TeamDefinition.class))).hasMessageContaining("不自行分流");
    }
    @Test void realSecretsCannotBeCommittedButTemplatesCan() {
        for (String p : java.util.List.of("docs/docker/sit/.env.sit", "docs/docker/sit/.secrets/db-password", "docs/server.key"))
            assertThat(ServiceDelivery.sensitivePath(p)).isTrue();
        assertThat(ServiceDelivery.sensitivePath("docs/docker/sit/.env.sit.example")).isFalse();
    }
}
