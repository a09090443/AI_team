package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ServiceDeliveryTest {
    final ObjectMapper mapper = new ObjectMapper();
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
