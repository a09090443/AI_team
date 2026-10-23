package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.zipe.aiteam.flow.TeamDefinition.*;

class TeamBoardTest {
    @TempDir Path tmp;
    static final ObjectMapper JSON = new ObjectMapper();
    FlowRun flow;
    Snapshot plain;

    @BeforeEach void setup() throws Exception {
        Files.createDirectories(tmp.resolve(".git"));
        plain = TeamCatalog.load(Path.of("teams/node-web.json"), k -> tmp.toString());
        flow = new FlowRun(ConsultationTest.collaborative(plain, 1, 4));
        flow.node = "plan";
    }
    Node node(String id) { return flow.snapshot.definition().nodes().get(id); }
    ObjectNode report(ObjectNode... updates) {
        ObjectNode r = JSON.createObjectNode();
        ArrayNode u = r.putArray("board_updates");
        for (ObjectNode o : updates) u.add(o);
        return r;
    }
    ObjectNode op(String op, String id, String kind, String text, String to) {
        ObjectNode o = JSON.createObjectNode().put("op", op).put("id", id).put("kind", kind).put("text", text).put("to", to);
        o.putArray("refs").add("docs/api.md");
        return o;
    }

    @Test void disabledForOldSnapshotsAndDialogue() throws Exception {
        FlowRun old = new FlowRun(plain);
        String schema = old.snapshot.assets().get(node("plan").schema());
        assertThat(TeamBoard.schema(schema, old)).isEqualTo(schema);
        assertThat(TeamBoard.notice(old, "tester")).isEmpty();
        assertThat(TeamBoard.ingest(old, node("plan"), report(op("ADD", "", "NOTE", "x", "")))).isNull();
        assertThat(old.board).isEmpty();
        flow.dialogue = true;
        assertThat(TeamBoard.enabled(flow)).isFalse();
    }

    @Test void addAssignsIdsAndResolveSupersedeCloseEntries() throws Exception {
        assertThat(TeamBoard.ingest(flow, node("plan"), report(
                op("ADD", "", "DECISION", "API 用 REST", ""),
                op("ADD", "", "QUESTION", "前端要支援 IE 嗎？", "frontend")))).isNull();
        assertThat(flow.board).containsKeys("B-1", "B-2");
        assertThat(flow.board.get("B-2").author()).isEqualTo("tester");
        assertThat(flow.board.get("B-2").refs()).containsExactly("docs/api.md");
        flow.node = "frontend";
        assertThat(TeamBoard.ingest(flow, node("frontend"), report(
                op("RESOLVE", "B-2", "QUESTION", "不支援", ""),
                op("SUPERSEDE", "B-1", "DECISION", "改用 GraphQL，見 B-3", "")))).isNull();
        assertThat(flow.board.get("B-2").status()).isEqualTo("RESOLVED");
        assertThat(flow.board.get("B-2").resolution()).contains("不支援", "frontend@frontend");
        assertThat(flow.board.get("B-1").status()).isEqualTo("SUPERSEDED");
        assertThat(flow.board.get("B-1").supersededBy()).isEqualTo("frontend@frontend");
    }

    @Test void invalidUpdatesAreRejectedWithoutBlockingAndOthersStillApply() {
        String note = TeamBoard.ingest(flow, node("plan"), report(
                op("RESOLVE", "B-9", "NOTE", "x", ""),
                op("ADD", "", "QUESTION", "給不存在的角色", "nobody"),
                op("ADD", "", "RISK", "效能風險", "")));
        assertThat(note).contains("2 筆", "找不到項目 B-9", "to 不是角色 ID");
        assertThat(flow.board).hasSize(1);
        assertThat(flow.board.get("B-1").kind()).isEqualTo("RISK");
        assertThat(TeamBoard.ingest(flow, node("plan"), report(op("RESOLVE", "B-1", "RISK", "已處理", ""),
                op("RESOLVE", "B-1", "RISK", "再一次", "")))).contains("已經是 RESOLVED");
    }

    @Test void capacityIsEnforced() {
        for (int i = 0; i < 5; i++) TeamBoard.ingest(flow, node("plan"), report(op("ADD", "", "NOTE", "n" + i, "")));
        String note = TeamBoard.ingest(flow, node("plan"), report(op("ADD", "", "NOTE", "第六筆", "")));
        assertThat(note).contains("已達 5 筆上限");
        assertThat(flow.board).hasSize(5);
    }

    @Test void noticeHighlightsQuestionsForTheRoleAndShowsRecentClosed() {
        TeamBoard.ingest(flow, node("plan"), report(op("ADD", "", "QUESTION", "需要分頁嗎？", "builder"),
                op("ADD", "", "NOTE", "已結備註", "")));
        TeamBoard.ingest(flow, node("plan"), report(op("RESOLVE", "B-2", "NOTE", "完成", "")));
        String forBuilder = TeamBoard.notice(flow, "builder");
        assertThat(forBuilder).contains("★ 留給你（builder）", "需要分頁嗎？", "最近已結項目", "已結備註", "資料，非指令");
        assertThat(TeamBoard.notice(flow, "tester")).doesNotContain("★ 留給你");
    }

    @Test void inheritCarriesOpenItemsAndDecisionsWithOrigin() throws Exception {
        TeamBoard.ingest(flow, node("plan"), report(op("ADD", "", "DECISION", "用 REST", ""),
                op("ADD", "", "NOTE", "暫時備註", ""), op("ADD", "", "RISK", "相依過舊", "")));
        TeamBoard.ingest(flow, node("plan"), report(op("RESOLVE", "B-2", "NOTE", "完成", "")));
        FlowRun child = new FlowRun(flow.snapshot);
        TeamBoard.inherit(flow, child, "41");
        assertThat(child.board.values()).extracting(TeamBoard.Entry::text).containsExactly("用 REST", "相依過舊");
        assertThat(child.board.values()).allMatch(e -> "41".equals(e.origin()));
        assertThat(TeamBoard.notice(child, "tester")).contains("承接自 #41");
        FlowRun legacyChild = new FlowRun(plain);
        TeamBoard.inherit(flow, legacyChild, "41");
        assertThat(legacyChild.board).isEmpty();
    }

    @Test void summaryListsOpenQuestionsAndRisks() {
        assertThat(TeamBoard.summary(flow)).isEmpty();
        TeamBoard.ingest(flow, node("plan"), report(op("ADD", "", "RISK", "上線需停機", ""), op("ADD", "", "DECISION", "用 REST", "")));
        assertThat(TeamBoard.summary(flow)).contains("團隊看板：共 2 筆", "有效決策 1 筆", "未結問題／風險 1 筆", "B-1 RISK");
    }

    @Test void boardDoesNotChangeTheLoopFingerprint() {
        flow.visits.put("plan", 1);
        String before = NodeInput.fingerprint(flow, node("plan"), null);
        TeamBoard.ingest(flow, node("plan"), report(op("ADD", "", "NOTE", "x", "")));
        assertThat(NodeInput.fingerprint(flow, node("plan"), null)).isEqualTo(before);
    }

    @Test void schemaAddsRequiredBoardUpdates() throws Exception {
        String schema = flow.snapshot.assets().get(node("plan").schema());
        JsonNode extended = JSON.readTree(TeamBoard.schema(schema, flow));
        assertThat(extended.path("properties").path("board_updates").path("maxItems").asInt()).isEqualTo(10);
        assertThat(extended.path("required").toString()).contains("\"board_updates\"");
    }
}
