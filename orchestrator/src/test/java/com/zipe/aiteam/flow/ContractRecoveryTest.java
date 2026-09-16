package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

/** #1014：implementation 已成功並提交，卻因 for 迴圈被 ToolTrust 否決，verification 永遠啟動不了。 */
class ContractRecoveryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String HEAD = "fa96f9f0000000000000000000000000000000000";
    @TempDir Path tmp;
    private FlowRun flow;

    @BeforeEach void setup() throws Exception {
        Path repo = tmp.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        flow = new FlowRun(TeamCatalog.load(Path.of("teams/spring-starters.json"), key -> repo.toString()));
        flow.node = "verification";
        flow.workspaceReady = true;
    }

    private JsonNode successfulImplementation() throws Exception {
        return mapper.readTree("{\"execution_status\":\"COMPLETED\",\"failure_report\":null,\"status\":\"SUCCESS\","
                + "\"summary\":\"已修正 TLS\",\"quality_gate\":{\"verify_passed\":true},\"boundary_conflict\":null}");
    }

    @Test void restoresAWronglyRejectedSuccessfulImplementation() throws Exception {
        flow.blockedFindings.put("implementation", successfulImplementation());

        assertThat(ContractRecovery.blockingReason(flow, "implementation", HEAD, true)).isNull();
        assertThat(ContractRecovery.candidate(flow, HEAD, true)).isEqualTo("implementation");

        ContractRecovery.restore(flow, "implementation", HEAD);

        assertThat(flow.receipts).containsKey("implementation");
        assertThat(flow.heads).containsEntry("implementation", HEAD);
        assertThat(flow.blockedFindings).doesNotContainKey("implementation");
        assertThat(FlowGates.blockingReason(flow.snapshot.definition().nodes().get("implementation"),
                flow.receipts.get("implementation"), flow)).isNull();
    }

    @Test void refusesAReportThatGenuinelyFailedItsGate() throws Exception {
        // 真正失敗的報告不是被誤判，不能靠這條路洗成有效。
        flow.blockedFindings.put("implementation", mapper.readTree("{\"execution_status\":\"COMPLETED\","
                + "\"failure_report\":null,\"status\":\"FAILED\",\"summary\":\"建置失敗\","
                + "\"quality_gate\":{\"verify_passed\":false},\"boundary_conflict\":null}"));

        assertThat(ContractRecovery.blockingReason(flow, "implementation", HEAD, true))
                .contains("原始報告本身沒有通過節點閘門");
        assertThat(ContractRecovery.candidate(flow, HEAD, true)).isNull();
    }

    @Test void refusesWhenThereIsNoRejectedReport() {
        assertThat(ContractRecovery.blockingReason(flow, "implementation", HEAD, true)).contains("沒有節點");
    }

    @Test void refusesADirtyWorkspace() throws Exception {
        flow.blockedFindings.put("implementation", successfulImplementation());
        assertThat(ContractRecovery.blockingReason(flow, "implementation", HEAD, false)).contains("未提交修改");
    }

    @Test void refusesWhenHeadIsUnknown() throws Exception {
        flow.blockedFindings.put("implementation", successfulImplementation());
        assertThat(ContractRecovery.blockingReason(flow, "implementation", null, true)).contains("HEAD");
    }

    @Test void refusesReadOnlyNodesBecauseTheirOutputIsAClaim() throws Exception {
        // plan 是 verification 的前置依賴，但它是 READ_ONLY；工具遭拒會直接動搖主張型產出。
        flow.blockedFindings.put("plan", mapper.readTree("{\"execution_status\":\"COMPLETED\",\"failure_report\":null}"));
        assertThat(ContractRecovery.blockingReason(flow, "plan", HEAD, true)).contains("唯讀節點");
    }

    @Test void refusesNodesThatAreNotADependencyOfTheCurrentNode() throws Exception {
        flow.blockedFindings.put("implementation", successfulImplementation());
        flow.node = "triage";
        assertThat(ContractRecovery.blockingReason(flow, "implementation", HEAD, true)).contains("不是目前節點");
    }

    @Test void refusesWhenAValidReceiptAlreadyExists() throws Exception {
        flow.receipts.put("implementation", successfulImplementation());
        flow.blockedFindings.put("implementation", successfulImplementation());
        assertThat(ContractRecovery.blockingReason(flow, "implementation", HEAD, true)).contains("不需要恢復");
    }
}
