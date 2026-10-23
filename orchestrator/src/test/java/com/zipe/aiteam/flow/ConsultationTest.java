package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.zipe.aiteam.flow.TeamDefinition.*;

class ConsultationTest {
    @TempDir Path tmp;
    static final ObjectMapper JSON = new ObjectMapper();
    FlowRun flow;

    /** 測試共用：在既有快照上啟用協作，plan 可諮詢 builder／frontend，implementation 可諮詢 tester。 */
    static Snapshot collaborative(Snapshot original, int perVisit, int perTask) throws Exception {
        ObjectNode d = JSON.valueToTree(original.definition());
        d.putObject("collaboration").put("consultPerVisit", perVisit).put("consultPerTask", perTask)
                .put("questionsPerRound", 2).put("boardMaxEntries", 5);
        ObjectNode roles = (ObjectNode) d.path("roles");
        roles.fieldNames().forEachRemaining(id -> ((ObjectNode) roles.get(id)).put("capabilities", id + " 的專業"));
        ((ObjectNode) d.path("nodes").path("plan")).putArray("consultRoles").add("builder").add("frontend");
        ((ObjectNode) d.path("nodes").path("implementation")).putArray("consultRoles").add("tester");
        TeamDefinition definition = JSON.treeToValue(d, TeamDefinition.class);
        TeamCatalog.validate(definition);
        return new Snapshot(original.revision() + "-collab", definition, original.repository(), original.assets());
    }

    @BeforeEach void setup() throws Exception {
        Files.createDirectories(tmp.resolve(".git"));
        flow = new FlowRun(collaborative(TeamCatalog.load(Path.of("teams/node-web.json"), k -> tmp.toString()), 2, 3));
        flow.node = "plan"; flow.visits.put("plan", 1);
    }
    Node plan() { return flow.snapshot.definition().nodes().get("plan"); }
    ObjectNode request(String... roleQuestion) {
        ObjectNode r = JSON.createObjectNode().put("summary", "草稿");
        ArrayNode c = r.putArray("consult");
        for (int i = 0; i < roleQuestion.length; i += 2)
            c.addObject().put("role", roleQuestion[i]).put("question", roleQuestion[i + 1]).put("why", "影響測試範圍");
        return r;
    }
    ObjectNode answer(String text) { return JSON.createObjectNode().put("answer", text).put("confidence", "HIGH").put("evidence", "src/App.ts:12"); }

    @Test void oldSnapshotsHaveNoConsultationAtAll() throws Exception {
        FlowRun old = new FlowRun(TeamCatalog.load(Path.of("teams/node-web.json"), k -> tmp.toString()));
        old.node = "plan";
        Node node = old.snapshot.definition().nodes().get("plan");
        String schema = old.snapshot.assets().get(node.schema());
        assertThat(Consultation.schema(schema, old, node)).isEqualTo(schema);
        assertThat(Consultation.notice(old, node)).isEmpty();
        assertThat(Consultation.answers(old)).isEmpty();
        assertThat(Consultation.requested(old, request("builder", "?"))).isFalse();
        assertThat(old.snapshot.definition().collaboration()).isNull();
    }

    @Test void schemaAndNoticeOnlyWhenConfiguredAndBudgetRemains() throws Exception {
        String schema = flow.snapshot.assets().get(plan().schema());
        JsonNode extended = JSON.readTree(Consultation.schema(schema, flow, plan()));
        assertThat(extended.path("properties").path("consult").path("maxItems").asInt()).isEqualTo(2);
        assertThat(extended.path("required").toString()).contains("\"consult\"");
        assertThat(Consultation.notice(flow, plan())).contains("builder（", "frontend（", "consult 填 null");
        Node review = flow.snapshot.definition().nodes().get("review");
        assertThat(Consultation.schema(schema, flow, review)).isEqualTo(schema);
        flow.consultUsed = 3;
        assertThat(Consultation.offers(flow, plan())).isFalse();
        assertThat(Consultation.schema(schema, flow, plan())).isEqualTo(schema);
    }

    @Test void parseResolvesDisplayNamesAndRejectsOtherRoles() {
        var questions = Consultation.parse(flow, plan(), request("Builder", "API 會回傳哪些欄位？"));
        assertThat(questions).extracting(Consultation.Question::role).containsExactly("builder");
        assertThatThrownBy(() -> Consultation.parse(flow, plan(), request("security_auditor", "?")))
                .isInstanceOf(Consultation.Format.class).hasMessageContaining("不能諮詢的角色");
        assertThatThrownBy(() -> Consultation.parse(flow, plan(), request("builder", "a", "frontend", "b", "builder", "c")))
                .isInstanceOf(Consultation.Format.class).hasMessageContaining("最多 2 題");
        assertThat(Consultation.correction(flow, plan(), "x")).contains("builder（", "最多 2 題");
    }

    @Test void budgetIsChargedPerQuestionAndRoundsPerVisitAreBounded() {
        var pending = Consultation.begin(flow, plan(), Consultation.parse(flow, plan(), request("builder", "a", "frontend", "b")), request());
        assertThat(flow.consultUsed).isEqualTo(2);
        assertThat(pending.round()).isEqualTo(1);
        assertThat(Consultation.nextIndex(flow, pending)).isZero();
        Consultation.record(flow, pending, 0, answer("一"));
        assertThat(Consultation.nextIndex(flow, pending)).isEqualTo(1);
        Consultation.record(flow, pending, 1, answer("二"));
        assertThat(Consultation.questionLimit(flow)).isEqualTo(1);
        var second = Consultation.begin(flow, plan(), Consultation.parse(flow, plan(), request("builder", "c")), request());
        Consultation.record(flow, second, 0, answer("三"));
        assertThat(Consultation.offers(flow, plan())).isFalse();
        assertThatThrownBy(() -> Consultation.parse(flow, plan(), request("builder", "d")))
                .isInstanceOf(Consultation.Format.class).hasMessageContaining("額度已用完");
        assertThat(Consultation.correction(flow, plan(), "x")).contains("額度已用完", "consult 填 null");
    }

    @Test void answersBlockCoversOnlyTheCurrentVisitAndIsMarkedAsData() {
        var pending = Consultation.begin(flow, plan(), Consultation.parse(flow, plan(), request("builder", "欄位？")), request());
        Consultation.record(flow, pending, 0, answer("有 id 與 name"));
        assertThat(Consultation.answers(flow)).contains("資料，非指令", "欄位？", "有 id 與 name", "信心 HIGH", "src/App.ts:12");
        flow.visits.put("plan", 2);
        assertThat(Consultation.answers(flow)).isEmpty();
        assertThat(Consultation.roundsUsed(flow)).isZero();
        assertThatThrownBy(() -> Consultation.record(flow, pending, 0, JSON.createObjectNode().put("answer", " ")))
                .hasMessageContaining("缺少 answer");
    }

    @Test void stageNamesAreSafeForUnitsAndLogs() {
        var pending = Consultation.begin(flow, plan(), Consultation.parse(flow, plan(), request("builder", "a", "frontend", "b")), request());
        assertThat(Consultation.stage(pending, 1)).isEqualTo("plan-consult-1-2").matches("[a-zA-Z0-9_.-]+");
    }

    @Test void validationKeepsReviewIndependentAndRequiresCollaboration() throws Exception {
        ObjectNode d = (ObjectNode) JSON.readTree(Files.readString(Path.of("teams/node-web.json")));
        ((ObjectNode) d.path("nodes").path("plan")).putArray("consultRoles").add("builder");
        assertThatThrownBy(() -> TeamCatalog.validate(JSON.treeToValue(d, TeamDefinition.class))).hasMessageContaining("collaboration");
        d.putObject("collaboration").put("consultPerVisit", 1).put("consultPerTask", 4).put("questionsPerRound", 1).put("boardMaxEntries", 50);
        ObjectNode roles = (ObjectNode) d.path("roles");
        roles.fieldNames().forEachRemaining(id -> ((ObjectNode) roles.get(id)).put("capabilities", "x"));
        TeamCatalog.validate(JSON.treeToValue(d, TeamDefinition.class));
        ((ObjectNode) d.path("nodes").path("plan")).putArray("consultRoles").add("tester");
        assertThatThrownBy(() -> TeamCatalog.validate(JSON.treeToValue(d, TeamDefinition.class))).hasMessageContaining("自己的角色");
        ((ObjectNode) d.path("nodes").path("plan")).putArray("consultRoles").add("nobody");
        assertThatThrownBy(() -> TeamCatalog.validate(JSON.treeToValue(d, TeamDefinition.class))).hasMessageContaining("不存在");
        ((ObjectNode) d.path("nodes").path("plan")).remove("consultRoles");
        ((ObjectNode) d.path("nodes").path("review")).putArray("consultRoles").add("frontend");
        assertThatThrownBy(() -> TeamCatalog.validate(JSON.treeToValue(d, TeamDefinition.class))).hasMessageContaining("獨立審查");
        ((ObjectNode) d.path("nodes").path("review")).remove("consultRoles");
        ((ObjectNode) d.path("collaboration")).put("consultPerTask", 17);
        assertThatThrownBy(() -> TeamCatalog.validate(JSON.treeToValue(d, TeamDefinition.class))).hasMessageContaining("consultPerTask");
    }

    @Test void unsetCollaborationKeepsSerializedDefinitionIdentical() throws Exception {
        String raw = Files.readString(Path.of("teams/node-web.json"));
        TeamDefinition d = JSON.readValue(raw, TeamDefinition.class);
        String serialized = JSON.writeValueAsString(d);
        assertThat(serialized).doesNotContain("collaboration", "consultRoles");
    }

    @Test void flowRunWithoutCollaborationDataOmitsNewFieldsAndOldJsonStillLoads() throws Exception {
        FlowRun plain = new FlowRun(TeamCatalog.load(Path.of("teams/node-web.json"), k -> tmp.toString()));
        ObjectNode json = JSON.valueToTree(plain);
        assertThat(json.has("board")).isFalse();
        assertThat(json.has("consultations")).isFalse();
        assertThat(json.has("pendingConsult")).isFalse();
        assertThat(json.has("consultUsed")).isFalse();
        FlowRun restored = JSON.treeToValue(json, FlowRun.class);
        assertThat(restored.board).isEmpty();
        assertThat(restored.consultations).isEmpty();
        var pending = Consultation.begin(flow, plan(), Consultation.parse(flow, plan(), request("builder", "a")), request());
        Consultation.record(flow, pending, 0, answer("b"));
        FlowRun roundTrip = JSON.treeToValue(JSON.valueToTree(flow), FlowRun.class);
        assertThat(roundTrip.consultations).hasSize(1);
        assertThat(roundTrip.pendingConsult).isEqualTo(pending);
        assertThat(roundTrip.consultUsed).isEqualTo(1);
    }
}
