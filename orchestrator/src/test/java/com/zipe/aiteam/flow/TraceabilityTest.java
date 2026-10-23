package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class TraceabilityTest {
    @TempDir Path tmp;
    ObjectMapper mapper = new ObjectMapper();
    FlowRun flow;
    ObjectNode baseline, plan, result;
    @BeforeEach void setup() throws Exception {
        Files.createDirectory(tmp.resolve(".git"));
        flow = new FlowRun(TeamCatalog.load(Path.of("teams/node-web.json"), key -> tmp.toString()));
        baseline = json("""
                {"summary":"mail","route":"implementation","requirements":[
                  {"id":"R1","kind":"FUNCTIONAL","description":"寄信","acceptance":"正確送達",
                   "acceptance_items":[{"id":"R1-DELIVER","criterion":"正確送達","dimension":"send/成功"}],"source":"使用者原文"},
                  {"id":"R2","kind":"NON_FUNCTIONAL","description":"不洩漏密碼","acceptance":"log 不含密碼",
                   "acceptance_items":[{"id":"R2-PLAIN","criterion":"log 不含明文密碼","dimension":"日誌/明文"}],"source":"專案安全規範"}
                ],"out_of_scope":["郵件行銷"],"open_questions":[]}
                """);
        flow.receipts.put("intake", baseline); flow.baselineRevision = Traceability.digest(baseline);
        plan = json("""
                {"status":"READY","scenarios":[{"id":"S1","requirement_ids":["R1","R2"],
                  "acceptance_ids":["R1-DELIVER","R2-PLAIN"],
                  "operation_ids":["test"],
                  "counterexample":"只檢查方法返回，不檢查收件與 log；測試會檢查兩者",
                  "criterion":"送達且log無密碼",
                  "preconditions":"SMTP","test_data":"信件","method":"整合測試","steps":"寄送並查log","expected_result":"送達且密碼未出現","environment":"測試SMTP"}]}
                """);
        plan.put("baseline_revision", flow.baselineRevision); flow.receipts.put("plan", plan);
        result = json("""
                {"scenario_results":[{"scenario_id":"S1","status":"PASS","actual_result":"送達","evidence":"log:12","command":"test"}],"uncertainties":[],"findings":[]}
                """);
        result.put("baseline_revision", flow.baselineRevision); result.put("plan_revision", Traceability.digest(plan));
    }
    ObjectNode json(String text) throws Exception { return (ObjectNode)mapper.readTree(text); }
    @Test void completeTraceabilityPasses() {
        assertThat(Traceability.requirementsReason(baseline)).isNull();
        assertThat(Traceability.planReason(plan, flow)).isNull();
        assertThat(Traceability.executionReason(flow.snapshot.definition().nodes().get("verification"), result, flow)).isNull();
    }
    @Test void openProductQuestionCannotBecomeBaseline() {
        baseline.withArray("open_questions").add("逾時後是否允許重複寄信？");
        assertThat(Traceability.requirementsReason(baseline)).contains("真人");
    }
    @Test void duplicateRequirementAndMissingAcceptanceAreRejected() {
        baseline.withArray("requirements").add(baseline.path("requirements").get(0).deepCopy());
        assertThat(Traceability.requirementsReason(baseline)).contains("重複");
        baseline.withArray("requirements").remove(2);
        ((ObjectNode)baseline.path("requirements").get(0)).put("acceptance", " ");
        assertThat(Traceability.requirementsReason(baseline)).contains("acceptance");
    }
    @Test void uncoveredRequirementNamesExactMissingId() {
        ((ObjectNode)plan.path("scenarios").get(0)).putArray("requirement_ids").add("R1");
        ((ObjectNode)plan.path("scenarios").get(0)).putArray("acceptance_ids").add("R1-DELIVER");
        assertThat(Traceability.planReason(plan, flow)).contains("遺漏需求", "R2");
    }
    @Test void inventedOrDuplicateRequirementLinkIsRejected() {
        ((ObjectNode)plan.path("scenarios").get(0)).putArray("requirement_ids").add("R9");
        assertThat(Traceability.planReason(plan, flow)).contains("未知", "R9");
    }
    @Test void staleBaselineCannotBeUsedByPlan() {
        plan.put("baseline_revision", "old");
        assertThat(Traceability.planReason(plan, flow)).contains("版本不符");
    }
    @Test void uncoveredAtomicAcceptanceNamesExactMissingId() {
        ((ObjectNode)plan.path("scenarios").get(0)).putArray("acceptance_ids").add("R1-DELIVER");
        assertThat(Traceability.planReason(plan, flow)).contains("遺漏原子驗收項目", "R2-PLAIN");
    }
    @Test void acceptanceMustBelongToLinkedRequirement() {
        ((ObjectNode)plan.path("scenarios").get(0)).putArray("requirement_ids").add("R1");
        assertThat(Traceability.planReason(plan, flow)).contains("R2-PLAIN", "未連結所屬需求", "R2");
    }
    @Test void everyScenarioNeedsCounterexample() {
        ((ObjectNode)plan.path("scenarios").get(0)).remove("counterexample");
        assertThat(Traceability.planReason(plan, flow)).contains("S1", "counterexample");
    }
    @Test void testPlanCannotInventAnUnconfiguredOperation() {
        ((ObjectNode)plan.path("scenarios").get(0)).putArray("operation_ids").add("shell-anything");
        assertThat(Traceability.planReason(plan, flow)).contains("未授權", "shell-anything");
    }

    @Test void documentationOnlyScenarioCanUseBuiltInContentInspection() {
        // #1010／#1011：改一行文件，任何配置都寫不出「讀某檔某行」的固定指令，測試計畫永遠受阻。
        ((ObjectNode)plan.path("scenarios").get(0)).putArray("operation_ids").add(OperationContract.INSPECT_CONTENT);
        assertThat(Traceability.planReason(plan, flow)).isNull();
        ((ObjectNode)plan.path("scenarios").get(0)).putArray("operation_ids").add(OperationContract.INSPECT_CONTENT).add("test");
        assertThat(Traceability.planReason(plan, flow)).isNull();
    }
    @Test void unchangedFilesMustDeclareSafeConcretePathsAndRetainAcceptanceCoverage() {
        ObjectNode scenario = (ObjectNode) plan.path("scenarios").get(0);
        scenario.putArray("operation_ids").add(FileComparison.OPERATION);
        assertThat(Traceability.planReason(plan, flow)).contains("unchanged_paths");
        scenario.putArray("unchanged_paths").add("application.properties");
        assertThat(Traceability.planReason(plan, flow)).isNull();
        scenario.withArray("unchanged_paths").add("../secret");
        assertThat(Traceability.planReason(plan, flow)).contains("路徑無效");
        scenario.putArray("unchanged_paths").add("application.properties").add("application.properties");
        assertThat(Traceability.planReason(plan, flow)).contains("重複");
        scenario.putArray("operation_ids").add(OperationContract.INSPECT_CONTENT);
        assertThat(Traceability.planReason(plan, flow)).contains("搭配");
    }
    @Test void oldPlanSchemaReceivesPathContractWithoutChangingSnapshot() throws Exception {
        String original = java.nio.file.Files.readString(java.nio.file.Path.of("teams/schemas/plan.json"));
        var extended = new com.fasterxml.jackson.databind.ObjectMapper().readTree(FileComparison.extendSchema(original));
        assertThat(extended.path("properties").path("scenarios").path("items").path("properties").has("unchanged_paths")).isTrue();
        assertThat(FileComparison.extendSchema(extended.toString())).isEqualTo(extended.toString());
        assertThat(original).doesNotContain("unchanged_paths");
    }
    @Test void everyNewScenarioMustReferenceAnOperationContract() {
        ((ObjectNode)plan.path("scenarios").get(0)).remove("operation_ids");
        assertThat(Traceability.planReason(plan, flow)).contains("S1", "operation_ids");
    }
    @Test void duplicateAtomicAcceptanceIsRejected() {
        ((ObjectNode) baseline.withArray("requirements").get(1)).withArray("acceptance_items")
                .add(baseline.path("requirements").get(0).path("acceptance_items").get(0).deepCopy());
        assertThat(Traceability.requirementsReason(baseline)).contains("驗收項目 ID", "重複");
    }
    @Test void legacySnapshotWithoutAtomicFieldsRemainsValid() throws Exception {
        baseline.withArray("requirements").forEach(r -> ((ObjectNode) r).remove("acceptance_items"));
        ((ObjectNode)plan.path("scenarios").get(0)).remove("acceptance_ids");
        ((ObjectNode)plan.path("scenarios").get(0)).remove("counterexample");
        ((ObjectNode)plan.path("scenarios").get(0)).remove("operation_ids");
        var assets = new java.util.HashMap<>(flow.snapshot.assets());
        ObjectNode oldSchema = (ObjectNode) mapper.readTree(assets.get("schemas/plan.json"));
        ((ObjectNode)oldSchema.at("/properties/scenarios/items/properties")).remove("operation_ids");
        var required = (com.fasterxml.jackson.databind.node.ArrayNode) oldSchema.at("/properties/scenarios/items/required");
        for (int i = required.size() - 1; i >= 0; i--) if ("operation_ids".equals(required.get(i).asText())) required.remove(i);
        assets.put("schemas/plan.json", oldSchema.toString());
        flow.snapshot = new TeamDefinition.Snapshot(flow.snapshot.revision(), flow.snapshot.definition(),
                flow.snapshot.repository(), java.util.Map.copyOf(assets));
        flow.baselineRevision = Traceability.digest(baseline);
        plan.put("baseline_revision", flow.baselineRevision);
        assertThat(Traceability.requirementsReason(baseline)).isNull();
        assertThat(Traceability.planReason(plan, flow)).isNull();
    }
    @Test void sameScenarioIdWithChangedExpectationInvalidatesOldResult() {
        ((ObjectNode)plan.path("scenarios").get(0)).put("expected_result", "不同預期");
        assertThat(Traceability.executionReason(flow.snapshot.definition().nodes().get("verification"), result, flow)).contains("舊測試計畫");
    }
    @Test void replanningInvalidatesAllDependentWorkButRetainsBaseline() {
        for (String id : new String[]{"implementation", "frontend", "verification", "review"}) {
            flow.receipts.put(id, result); flow.heads.put(id, "head");
        }
        flow.approvalHead = "head";
        Traceability.invalidate(flow, "plan");
        assertThat(flow.receipts).containsOnlyKeys("intake"); assertThat(flow.heads).isEmpty();
        assertThat(flow.approvalHead).isNull();
    }
    @Test void qaRerunInvalidatesReviewWithoutDiscardingImplementation() {
        flow.receipts.put("implementation", result); flow.receipts.put("verification", result); flow.receipts.put("review", result);
        Traceability.invalidate(flow, "verification");
        assertThat(flow.receipts).containsKeys("intake", "plan", "implementation").doesNotContainKeys("verification", "review");
    }
    @Test void enteringReadOnlyReviewRetainsSystemVerificationReceipt() {
        flow.independentEvidence = new IndependentVerifier.Receipt("head", flow.baselineRevision,
                flow.snapshot.revision(), "start", "end", java.util.List.of());
        Traceability.invalidate(flow, "review");
        assertThat(flow.independentEvidence).isNotNull();
    }

    void enableDeferral() throws Exception {
        ObjectNode definition = mapper.valueToTree(flow.snapshot.definition());
        ((ObjectNode) definition.path("project")).set("autonomy", mapper.readTree(
                "{\"modelTimeoutRecoveries\":0,\"maxTimeoutSeconds\":0,\"verificationContinuations\":0,"
                        + "\"requirementChangeOptions\":false,\"channelCommandRouting\":false,\"deferUnverifiable\":true}"));
        flow.snapshot = new TeamDefinition.Snapshot("defer", mapper.treeToValue(definition, TeamDefinition.class),
                flow.snapshot.repository(), flow.snapshot.assets());
    }
    ObjectNode deferPlainLog(String reason) throws Exception {
        ObjectNode deferredPlan = plan.deepCopy();
        ObjectNode scenario = (ObjectNode) deferredPlan.path("scenarios").get(0);
        scenario.putArray("requirement_ids").add("R1"); scenario.putArray("acceptance_ids").add("R1-DELIVER");
        deferredPlan.putArray("deferred_acceptance").addObject().put("acceptance_id", "R2-PLAIN")
                .put("reason", reason).put("needs", "正式環境的日誌");
        return deferredPlan;
    }
    String planCheck(ObjectNode candidate) { flow.receipts.put("plan", candidate); return Traceability.planReason(candidate, flow); }
    /** #1053～#1055：驗收環境驗不了的項目在計畫延後就算涵蓋，不停給真人；未開啟時照舊算遺漏。 */
    @Test void unverifiableAcceptanceCanBeDeferredOnlyWhenEnabled() throws Exception {
        ObjectNode deferredPlan = deferPlainLog("本環境沒有正式日誌");
        assertThat(planCheck(deferredPlan)).contains("遺漏");
        enableDeferral();
        assertThat(planCheck(deferredPlan)).isNull();
        assertThat(planCheck(deferPlainLog(" "))).contains("缺少 reason");
        ObjectNode unknown = deferPlainLog("x"); ((ObjectNode) unknown.path("deferred_acceptance").get(0)).put("acceptance_id", "R9-X");
        assertThat(planCheck(unknown)).contains("未知");
        ObjectNode both = deferPlainLog("x"); ObjectNode twice = (ObjectNode) both.path("scenarios").get(0);
        twice.withArray("requirement_ids").add("R2"); twice.withArray("acceptance_ids").add("R2-PLAIN");
        assertThat(planCheck(both)).contains("不能同時寫情境");

        flow.receipts.put("plan", deferredPlan);
        assertThat(DeferredAcceptance.lines(flow)).singleElement().asString().contains("R2-PLAIN", "log 不含明文密碼", "正式環境的日誌");
        assertThat(FlowEngine.prBody(new com.zipe.aiteam.task.Task("42", "c", "t", "u", "寄信"), flow))
                .contains("## 延後驗收（本環境無法驗證）", "R2-PLAIN");
        JsonNode schema = mapper.readTree(DeferredAcceptance.extendSchema("{\"type\":\"object\",\"properties\":{},\"required\":[]}"));
        assertThat(schema.path("required").toString()).contains("deferred_acceptance");
    }
}
