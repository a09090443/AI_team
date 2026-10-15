package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.zipe.aiteam.flow.TeamDefinition.*;

class IssueTrackerTest {
    @TempDir Path tmp;
    ObjectMapper json = new ObjectMapper();
    FlowRun flow;
    @BeforeEach void setup() throws Exception {
        Files.createDirectories(tmp.resolve(".git"));
        flow = new FlowRun(TeamCatalog.load(Path.of("teams/node-web.json"), k -> tmp.toString()));
    }
    ObjectNode report() {
        ObjectNode r = json.createObjectNode().put("issue_revision", IssueTracker.revision(flow));
        r.putArray("issue_updates"); r.putArray("issue_checks");
        return r;
    }
    ObjectNode issue(String id, String scope) {
        ObjectNode i = json.createObjectNode().put("id", id).put("description", "Problem " + id)
                .put("evidence", "file:12 mismatch").put("scope", scope).put("reason", "Requested target");
        i.putArray("requirement_ids").add("R1"); return i;
    }
    Node node(Gate gate) { return flow.snapshot.definition().nodes().values().stream().filter(n -> n.gate() == gate).findFirst().orElseThrow(); }
    void discover(int count) {
        ObjectNode r = report(); for (int i=1; i<=count; i++) r.withArray("issue_updates").add(issue("I" + i, "REPAIR"));
        assertThat(IssueTracker.ingest(flow, node(Gate.REQUIREMENTS), r, "42")).isNull();
    }
    ObjectNode intake(String intent, String route) {
        ObjectNode r = report().put("work_intent", intent).put("intent_evidence", "Please check and fix").put("route", route);
        r.putArray("requirements").addObject().put("id", "R1");
        flow.issueInventory.values().forEach(i -> r.withArray("issue_updates").add(i.deepCopy()));
        return r;
    }
    ObjectNode checks(int count) {
        ObjectNode r = report(); for(int i=1;i<=count;i++) r.withArray("issue_checks").addObject()
                .put("id", "I" + i).put("status", "PASS").put("evidence", "Independent verification file:12 / operation log");
        return r;
    }
    @Test void nineFindingsCannotCloseWithOneCheckOrImplementationClaim() {
        discover(9);
        assertThat(IssueTracker.reportReason(node(Gate.REQUIREMENTS), intake("WORK", "question"), flow)).contains("純問答");
        assertThat(IssueTracker.checksReason(checks(1), flow)).contains("I9", "I2");
        flow.receipts.put("implementation", checks(9));
        assertThat(IssueTracker.completionReason(flow)).isNotNull();
        flow.receipts.put("verification", checks(9));
        assertThat(IssueTracker.completionReason(flow)).isNull();
    }
    /** #1040／#1042：改寫既有 description 是格式錯誤，給出原文讓同一個 agent 修正；原則類錯誤不給。 */
    @Test void formatErrorsGetCorrectionWithStoredTextButPolicyErrorsDoNot() {
        discover(1);
        ObjectNode changed = report(); changed.withArray("issue_updates").add(issue("I1", "REPAIR").put("description", "Problem I1；負責人已決定接受"));
        String reason = IssueTracker.ingest(flow, node(Gate.VERIFICATION), changed, "42");
        assertThat(reason).isEqualTo(IssueTracker.DESCRIPTION_CHANGED + "I1");
        assertThat(IssueTracker.formatCorrection(flow, reason)).contains("不計重跑次數").contains("逐字為：\nProblem I1").contains("evidence／reason");

        ObjectNode stale = report().put("issue_revision", "old");
        String staleReason = IssueTracker.ingest(flow, node(Gate.VERIFICATION), stale, "42");
        assertThat(IssueTracker.formatCorrection(flow, staleReason)).contains("issue_revision=" + IssueTracker.revision(flow));

        ObjectNode downgrade = report(); downgrade.withArray("issue_updates").add(issue("I1", "EXCLUDED"));
        String policy = IssueTracker.ingest(flow, node(Gate.VERIFICATION), downgrade, "42");
        assertThat(policy).contains("降為");
        assertThat(IssueTracker.formatCorrection(flow, policy)).isNull();
        assertThat(IssueTracker.formatCorrection(flow, IssueTracker.DESCRIPTION_CHANGED + "UNKNOWN")).isNull();
        assertThat(IssueTracker.formatCorrection(flow, null)).isNull();
    }
    @Test void noSilentDeletionOrScopeDowngradeOrIdReuse() {
        discover(2);
        assertThat(IssueTracker.ingest(flow,node(Gate.REQUIREMENTS),report(),"42")).isNull();
        assertThat(flow.issueInventory).hasSize(2);
        ObjectNode r=report();r.withArray("issue_updates").add(issue("I1","EXCLUDED"));
        assertThat(IssueTracker.ingest(flow,node(Gate.REQUIREMENTS),r,"42")).contains("降為");
        r=report(); r.withArray("issue_updates").add(issue("I1","REPAIR").put("description","different"));
        assertThat(IssueTracker.ingest(flow,node(Gate.REQUIREMENTS),r,"42")).contains("覆寫");
        assertThat(flow.issueInventory.get("I1").path("scope").asText()).isEqualTo("REPAIR");
    }
    @Test void mappingAndFullIntakeInventoryAreMandatory() {
        discover(2);ObjectNode r=intake("WORK","implementation");
        ((ArrayNode)r.path("issue_updates")).remove(1);
        assertThat(IssueTracker.reportReason(node(Gate.REQUIREMENTS),r,flow)).contains("遺漏");
        r=intake("WORK","implementation");r.putArray("requirements");
        assertThat(IssueTracker.reportReason(node(Gate.REQUIREMENTS),r,flow)).contains("不存在");
    }
    @Test void explicitReadOnlyRemainsReadOnlyAndFindingsRemainVisible() {
        ObjectNode r=report();r.withArray("issue_updates").add(issue("I1","OBSERVATION"));
        assertThat(IssueTracker.ingest(flow,node(Gate.REQUIREMENTS),r,"42")).isNull();
        assertThat(IssueTracker.reportReason(node(Gate.REQUIREMENTS),intake("READ_ONLY","question"),flow)).isNull();
        assertThat(IssueTracker.reportReason(node(Gate.REQUIREMENTS),intake("READ_ONLY","implementation"),flow)).contains("唯讀");
        assertThat(IssueTracker.reportReason(node(Gate.REQUIREMENTS),intake("WORK","question"),flow)).contains("觀察");
    }
    @Test void continuationPersistsAllIssuesAndEvidenceButRequiresNewVerification() throws Exception {
        discover(9); ObjectNode verified=checks(9);flow.receipts.put("verification",verified);
        IssueTracker.rememberEvidence(flow,node(Gate.VERIFICATION),verified,"abc");
        FlowRun child=new FlowRun(flow.snapshot);IssueTracker.inherit(flow,child);
        FlowRun restored=json.readValue(json.writeValueAsString(child),FlowRun.class);
        assertThat(restored.issueInventory).hasSize(9);assertThat(restored.issueEvidence).hasSize(9);
        assertThat(restored.issueInventory.get("I9").path("source_task").asText()).isEqualTo("42");
        assertThat(IssueTracker.completionReason(restored)).isNotNull();
        child.issueInventory.clear();assertThat(flow.issueInventory).hasSize(9);
    }
    @Test void laterDiscoveryInvalidatesPreviousVerification() {
        discover(1);ObjectNode previous=checks(1);flow.receipts.put("verification",previous);
        ObjectNode r=report();r.withArray("issue_updates").add(issue("I2","REPAIR"));
        assertThat(IssueTracker.ingest(flow,node(Gate.REVIEW),r,"42")).isNull();
        assertThat(IssueTracker.checksReason(previous,flow)).contains("變更");
        assertThat(IssueTracker.completionReason(flow)).isNotNull();
    }
    @Test void duplicateUnknownAndOpenChecksRejected() {
        discover(1); ObjectNode r=checks(1);r.withArray("issue_checks").add(r.path("issue_checks").get(0));
        assertThat(IssueTracker.checksReason(r,flow)).contains("無效");
        r=checks(2);assertThat(IssueTracker.checksReason(r,flow)).contains("無效");
        r=checks(1);((ObjectNode)r.path("issue_checks").get(0)).put("status","OPEN");
        assertThat(IssueTracker.checksReason(r,flow)).contains("尚未解決");
    }
    @Test void decisionCannotBeSelfApprovedByPassingCheck() {
        ObjectNode r=report();r.withArray("issue_updates").add(issue("I1","DECISION"));
        assertThat(IssueTracker.ingest(flow,node(Gate.REQUIREMENTS),r,"42")).isNull();
        assertThat(IssueTracker.checksReason(checks(1),flow)).contains("必要決策");
    }
    @Test void staleReportsAndMalformedBatchesDoNotPartiallyMutateInventory() {
        ObjectNode stale=report();discover(1);
        assertThat(IssueTracker.ingest(flow,node(Gate.REQUIREMENTS),stale,"42")).contains("過期");
        ObjectNode r=report();r.withArray("issue_updates").add(issue("I2","REPAIR")).add(issue("I1","EXCLUDED"));
        assertThat(IssueTracker.ingest(flow,node(Gate.REQUIREMENTS),r,"42")).isNotNull();
        assertThat(flow.issueInventory).containsOnlyKeys("I1");
    }
    @Test void decisionRequiresNewHumanInputAndScopeExclusionRequiresExplicitChangeGrant() {
        ObjectNode r=report();r.withArray("issue_updates").add(issue("I1","DECISION"));
        assertThat(IssueTracker.ingest(flow,node(Gate.REQUIREMENTS),r,"42")).isNull();
        r=report();r.withArray("issue_updates").add(issue("I1","REPAIR"));
        assertThat(IssueTracker.ingest(flow,node(Gate.REQUIREMENTS),r,"42")).contains("真人回覆");
        flow.supplements++;
        r=report();r.withArray("issue_updates").add(issue("I1","REPAIR"));
        assertThat(IssueTracker.ingest(flow,node(Gate.REQUIREMENTS),r,"42")).isNull();
        r=report();r.withArray("issue_updates").add(issue("I1","EXCLUDED"));
        assertThat(IssueTracker.ingest(flow,node(Gate.REQUIREMENTS),r,"42")).contains("降為");
        flow.issueScopeChangeAuthorized=true;
        assertThat(IssueTracker.ingest(flow,node(Gate.REQUIREMENTS),r,"42")).isNull();
        assertThat(flow.issueInventory.get("I1").path("scope").asText()).isEqualTo("EXCLUDED");
    }
    @Test void publishedOrTerminalPathsCannotBypassRegisteredOpenIssues() {
        discover(2);
        flow.node="approval";
        assertThat(FlowGates.publicationReason(flow,"head")).contains("待處理");
        flow.node="end";
        assertThat(IssueTracker.completionReason(flow)).contains("不能宣稱");
    }
    @Test void contractIsUniversalFrozenAndOldSnapshotsKeepTheirContract() throws Exception {
        for(String team:List.of("node-web","spring-starters","bestpartner")) {
            FlowRun f=new FlowRun(TeamCatalog.load(Path.of("teams/"+team+".json"),k->tmp.toString()));
            assertThat(IssueTracker.enabled(f)).isTrue();
            Node n=f.snapshot.definition().nodes().get(f.snapshot.definition().entry());
            JsonNode schema=json.readTree(IssueTracker.schema(f.snapshot.assets().get(n.schema()),n,f));
            assertThat(schema.path("properties").has("issue_updates")).isTrue();
            assertThat(schema.path("properties").has("work_intent")).isTrue();
            var oldAssets=new LinkedHashMap<>(f.snapshot.assets());oldAssets.remove(IssueTracker.CONTRACT);
            f.snapshot=new Snapshot("old",f.snapshot.definition(),f.snapshot.repository(),oldAssets);
            assertThat(IssueTracker.enabled(f)).isFalse();
            assertThat(IssueTracker.ingest(f,n,json.createObjectNode(),"42")).isNull();
        }
    }
}
