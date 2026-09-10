package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PlanApprovalTest {
    @TempDir Path tmp;
    FlowRun flow;
    @BeforeEach void setup() throws Exception {
        Files.createDirectories(tmp.resolve(".git"));
        flow = new FlowRun(TeamCatalog.load(Path.of("teams/node-web.json"), key -> tmp.toString()));
        flow.acceptanceOwner = "owner"; flow.baselineRevision = "baseline";
        flow.receipts.put("plan", new ObjectMapper().createObjectNode().put("approved", true));
    }
    @Test void modelApprovalAndBuildReceiptCannotApprovePlan() {
        flow.independentEvidence = new IndependentVerifier.Receipt("head", "baseline", flow.snapshot.revision(), "start", "end", List.of());
        assertThat(PlanApproval.blockingReason(flow, "plan")).contains("真人核准");
    }
    @Test void wrongOwnerOrDigestRejected() {
        assertThatThrownBy(() -> PlanApproval.approve(flow, "plan", Traceability.digest(flow.receipts.get("plan")), "bot"))
                .hasMessageContaining("核准者或版本不符");
        assertThatThrownBy(() -> PlanApproval.approve(flow, "plan", "old", "owner")).hasMessageContaining("核准者或版本不符");
    }
    @Test void approvalInvalidatedByPlanBaselineOrConfigurationChanges() {
        flow.planApprovals.put("plan", PlanApproval.approve(flow, "plan", Traceability.digest(flow.receipts.get("plan")), "owner"));
        assertThat(PlanApproval.blockingReason(flow, "plan")).isNull();
        flow.baselineRevision = "changed";
        assertThat(PlanApproval.blockingReason(flow, "plan")).isNotNull();
        flow.baselineRevision = "baseline";
        flow.receipts.put("plan", new ObjectMapper().createObjectNode().put("revised", true));
        assertThat(PlanApproval.blockingReason(flow, "plan")).isNotNull();
    }
}
