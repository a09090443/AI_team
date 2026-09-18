package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 防循環要擋的是原地打轉，不是正常往前推進。 */
class NodeInputTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path tmp;
    private FlowRun flow;
    private TeamDefinition.Node node;

    @BeforeEach void setup() throws Exception {
        Path repo = tmp.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        flow = new FlowRun(TeamCatalog.load(Path.of("teams/spring-starters.json"), key -> repo.toString()));
        flow.node = "verification";
        node = flow.snapshot.definition().nodes().get("verification");
    }

    @Test void identicalInputCountsAsARepeat() {
        String first = NodeInput.fingerprint(flow, node, "head1");
        assertThat(NodeInput.record(flow, first)).isEqualTo(1);
        assertThat(NodeInput.record(flow, NodeInput.fingerprint(flow, node, "head1"))).isEqualTo(2);
        assertThat(NodeInput.record(flow, NodeInput.fingerprint(flow, node, "head1"))).isEqualTo(3);
    }

    @Test void newCommitMeansProgressSoTheCountRestarts() {
        NodeInput.record(flow, NodeInput.fingerprint(flow, node, "head1"));
        NodeInput.record(flow, NodeInput.fingerprint(flow, node, "head1"));

        assertThat(NodeInput.record(flow, NodeInput.fingerprint(flow, node, "head2"))).isEqualTo(1);
    }

    @Test void newUpstreamResultOrBlockedFindingMeansProgress() throws Exception {
        NodeInput.record(flow, NodeInput.fingerprint(flow, node, "head1"));

        flow.receipts.put("plan", mapper.readTree("{\"scenarios\":[{\"id\":\"s1\"}]}"));
        assertThat(NodeInput.record(flow, NodeInput.fingerprint(flow, node, "head1"))).isEqualTo(1);

        flow.blockedFindings.put("review", mapper.readTree("{\"detail\":\"TLS 退化\"}"));
        assertThat(NodeInput.record(flow, NodeInput.fingerprint(flow, node, "head1"))).isEqualTo(1);
    }

    @Test void aHumanSupplementAlwaysCountsAsNewInput() {
        NodeInput.record(flow, NodeInput.fingerprint(flow, node, "head1"));
        NodeInput.record(flow, NodeInput.fingerprint(flow, node, "head1"));

        flow.supplements++;
        assertThat(NodeInput.record(flow, NodeInput.fingerprint(flow, node, "head1"))).isEqualTo(1);
    }

    @Test void anOldSnapshotWithoutAFingerprintIsTreatedAsTheSameInput() {
        // 舊資料沒有指紋欄位。這時若當成「新輸入」，防循環上限會被無條件繞過一次；
        // 視為相同輸入則不會誤擋，因為舊資料的 repeats 同樣是 0。
        flow.repeats.put("verification", node.maxVisits());

        assertThat(NodeInput.repeated(flow, NodeInput.fingerprint(flow, node, "head1"))).isTrue();
    }

    @Test void theAgentsOwnRepeatedReportDoesNotResetTheCount() {
        // context 每輪都會被追加，若把它算進指紋，防循環就完全失效。
        NodeInput.record(flow, NodeInput.fingerprint(flow, node, "head1"));
        flow.context = FlowContext.append(flow.context, "節點 verification 受阻：同樣的原因");

        assertThat(NodeInput.record(flow, NodeInput.fingerprint(flow, node, "head1"))).isEqualTo(2);
    }
}
