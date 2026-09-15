package com.zipe.aiteam.flow;

import com.zipe.aiteam.task.Task;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class HumanOptionsTest {
    private final Task task = new Task("1014", "c", "t", "u", "多組 SMTP 容錯");
    @TempDir Path tmp;
    private FlowRun flow;

    @BeforeEach void setup() throws Exception {
        Path repo = tmp.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        flow = new FlowRun(TeamCatalog.load(Path.of("teams/spring-starters.json"), key -> repo.toString()));
    }

    @Test void alwaysExplainsThatPlainResumeChangesNothing() {
        String text = HumanOptions.render(task, flow, true, true);

        assertThat(text).contains("你現在可以做的事")
                .contains("補充 #1014 <你的說明或決定>")
                .contains("只寫「續跑」不會改變上一輪的判斷依據")
                .contains("停止不等於完成");
    }

    @Test void mentionsFullReportOnlyInBriefMode() {
        assertThat(HumanOptions.render(task, flow, true, true)).contains("檢視完整報告");
        assertThat(HumanOptions.render(task, flow, true, false)).doesNotContain("檢視完整報告");
    }

    @Test void offersRequirementChangeApprovalOnlyWhenPending() {
        assertThat(HumanOptions.render(task, flow, true, true)).doesNotContain("核准需求變更");
        flow.changeTarget = "intake";
        assertThat(HumanOptions.render(task, flow, true, true)).contains("核准需求變更");
    }

    @Test void offersRemediationRecoveryOnlyAfterVerificationExhaustedItsRetries() {
        flow.workspaceReady = true;
        assertThat(HumanOptions.render(task, flow, true, true)).doesNotContain("核准人工修復重驗");

        var verification = flow.snapshot.definition().nodes().get("verification");
        flow.visits.put("verification", verification.maxVisits());
        assertThat(HumanOptions.render(task, flow, true, true)).contains("核准人工修復重驗");
    }

    @Test void remediationIsNotOfferedWithoutAWorkspace() {
        var verification = flow.snapshot.definition().nodes().get("verification");
        flow.visits.put("verification", verification.maxVisits());
        assertThat(HumanOptions.render(task, flow, true, true)).doesNotContain("核准人工修復重驗");
    }

    @Test void offersPlanApprovalWhenTheCurrentNodeStillNeedsIt() {
        flow.node = "verification";
        assertThat(HumanOptions.render(task, flow, true, true)).contains("核准測試計畫 plan");
    }

    @Test void saysNothingWhenNoOptionIsAvailable() {
        flow.node = "triage";
        assertThat(HumanOptions.render(task, flow, false, false)).isEmpty();
    }
}
