package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AgentDispatchTest {
    @TempDir Path temp;
    ObjectMapper mapper = new ObjectMapper();
    FlowRun flow;
    @BeforeEach void setup() throws Exception {
        Files.createDirectories(temp.resolve(".git"));
        flow = new FlowRun(TeamCatalog.load(Path.of("teams/bestpartner.json"), k -> temp.toString()));
    }
    ObjectNode report(String role) {
        ObjectNode report = mapper.createObjectNode();
        report.putObject("handoff").put("role", role).put("reason", "需要容器環境專業").put("instruction", "設計 SIT 環境並列出可驗收成果");
        return report;
    }
    @Test void choosesRoleFromCapabilitiesAndRestoresFromSnapshot() throws Exception {
        var source = flow.snapshot.definition().nodes().get("intake");
        var a = AgentDispatch.choose(flow, source, "design", report("infrastructure"));
        AgentDispatch.save(flow, a);
        FlowRun restored = mapper.readValue(mapper.writeValueAsString(flow), FlowRun.class);
        assertThat(AgentDispatch.effective(restored, "design").role()).isEqualTo("infrastructure");
        assertThat(AgentDispatch.effective(restored, "design").scope()).isEqualTo(TeamDefinition.Scope.READ_ONLY);
        assertThat(restored.assignmentHistory).containsExactly(a);
        assertThat(restored.snapshot.definition().nodes().get("design").role()).isEqualTo("architect");
    }
    @Test void reportNamesTheSelectedRoleInsteadOfTheConfiguredDefault() {
        var report = report("infrastructure"); report.put("route", "implementation");
        String label = FlowEngine.routeLabels(flow, flow.snapshot.definition().nodes().get("intake"), report).apply("implementation");
        assertThat(label).contains("Infrastructure Agent").doesNotContain("Architecture Agent");
    }
    @Test void rejectsMissingUnknownAndUnauthorizedChoices() {
        var source = flow.snapshot.definition().nodes().get("intake");
        for (JsonNode report : List.of(mapper.createObjectNode(), report("missing"), report("inspector")))
            assertThatThrownBy(() -> AgentDispatch.choose(flow, source, "design", report)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentDispatch.choose(flow, source, "implementation", report("infrastructure")))
                .hasMessageContaining("未配置");
        assertThatThrownBy(() -> AgentDispatch.choose(flow, source, "human", report("infrastructure")))
                .hasMessageContaining("必須為 null");
        assertThat(flow.assignments).isEmpty();
    }
    @Test void cannotSmugglePermissionsInHandoff() {
        var report = report("infrastructure"); ((ObjectNode) report.get("handoff")).put("scope", "WORKSPACE");
        assertThatThrownBy(() -> AgentDispatch.choose(flow, flow.snapshot.definition().nodes().get("intake"), "design", report))
                .hasMessageContaining("欄位不合法");
    }
    @Test void fixedServiceRoleAcceptsMatchingHandoffWithoutAllowingReassignment() throws Exception {
        var source = flow.snapshot.definition().nodes().get("intake");
        var a = AgentDispatch.choose(flow, source, "service", report("infrastructure"));
        AgentDispatch.save(flow, a);
        var restored = mapper.readValue(mapper.writeValueAsString(flow), FlowRun.class);
        assertThat(AgentDispatch.effective(restored, "service").role()).isEqualTo("infrastructure");
        assertThat(AgentDispatch.effective(restored, "service").skills()).isEqualTo(flow.snapshot.definition().nodes().get("service").skills());
        assertThatThrownBy(() -> AgentDispatch.choose(flow, source, "service", report("builder")))
                .hasMessageContaining("資格");
        var extra = report("infrastructure"); extra.with("handoff").put("scope", "WORKSPACE");
        assertThatThrownBy(() -> AgentDispatch.choose(flow, source, "service", extra)).hasMessageContaining("欄位不合法");
    }
    @Test void promptListsCapabilitiesAndOnlyExtendsEligibleSchemas() throws Exception {
        var source = flow.snapshot.definition().nodes().get("intake");
        assertThat(AgentDispatch.notice(flow, source)).contains("infrastructure", "架構", "handoff", "權限 READ_ONLY");
        String original = flow.snapshot.assets().get(source.schema());
        assertThat(mapper.readTree(AgentDispatch.schema(original, flow, source)).path("required").toString()).contains("handoff");
        flow.dialogue = true;
        assertThat(AgentDispatch.schema(original, flow, source)).isEqualTo(original);
        assertThat(AgentDispatch.notice(flow, source)).isEmpty();
    }
    @Test void reviewerCannotBeAnImplementationCandidate() throws Exception {
        ObjectNode d = mapper.valueToTree(flow.snapshot.definition());
        ((ObjectNode) d.path("roles").path("inspector")).put("capabilities", "審查").withArray("tools").add("Edit");
        ((ObjectNode) d.path("nodes").path("implementation")).withArray("candidateRoles").add("inspector");
        assertThatThrownBy(() -> TeamCatalog.validate(mapper.treeToValue(d, TeamDefinition.class)))
                .hasMessageContaining("獨立審查");
    }
    @Test void writableCandidateNeedsExplicitWriteTools() throws Exception {
        ObjectNode d = mapper.valueToTree(flow.snapshot.definition());
        ((ObjectNode) d.path("roles").path("infrastructure")).putArray("tools").add("Read");
        assertThatThrownBy(() -> TeamCatalog.validate(mapper.treeToValue(d, TeamDefinition.class)))
                .hasMessageContaining("工具");
    }
    @Test void legacySnapshotsStayOnTheirOriginalRoles() throws Exception {
        FlowRun legacy = new FlowRun(TeamCatalog.load(Path.of("teams/node-web.json"), k -> temp.toString()));
        ObjectNode json = mapper.valueToTree(legacy); json.remove(List.of("assignments", "assignmentHistory"));
        FlowRun restored = mapper.treeToValue(json, FlowRun.class);
        assertThat(restored.assignments).isEmpty(); assertThat(restored.assignmentHistory).isEmpty();
        assertThat(AgentDispatch.effective(restored, "implementation").role()).isEqualTo("builder");
        assertThat(AgentDispatch.choose(restored, restored.snapshot.definition().nodes().get("plan"), "implementation", mapper.createObjectNode())).isNull();
    }
    @Test void triageCanReassignAndKeepsPreviousDecision() {
        AgentDispatch.save(flow, AgentDispatch.choose(flow, flow.snapshot.definition().nodes().get("intake"), "design", report("architect")));
        flow.node = "triage";
        AgentDispatch.save(flow, AgentDispatch.choose(flow, flow.snapshot.definition().nodes().get("triage"), "design", report("infrastructure")));
        assertThat(AgentDispatch.effective(flow, "design").role()).isEqualTo("infrastructure");
        assertThat(flow.assignmentHistory).extracting(AgentDispatch.Assignment::role).containsExactly("architect", "infrastructure");
    }
}
