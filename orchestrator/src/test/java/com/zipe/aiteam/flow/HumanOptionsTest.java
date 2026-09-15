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

    private String text() { return HumanOptions.render(task, flow, true, true, null); }

    @Test void alwaysExplainsThatPlainResumeChangesNothing() {
        assertThat(text()).contains("你現在可以做的事")
                .contains("告訴它還缺什麼")
                .contains("只寫「續跑」不會改變上一輪的判斷依據")
                .contains("停止不等於完成");
    }

    @Test void mentionsFullReportOnlyInBriefMode() {
        assertThat(HumanOptions.render(task, flow, true, true, null)).contains("看完整報告");
        assertThat(HumanOptions.render(task, flow, true, false, null)).doesNotContain("看完整報告");
    }

    @Test void offersRequirementChangeApprovalOnlyWhenPending() {
        assertThat(text()).doesNotContain("需求要改");
        flow.changeTarget = "intake";
        assertThat(text()).contains("需求要改").contains("核准需求變更 <具體變更內容>");
    }

    @Test void offersRemediationRecoveryOnlyAfterVerificationExhaustedItsRetries() {
        flow.workspaceReady = true;
        assertThat(text()).doesNotContain("請它重驗");

        var verification = flow.snapshot.definition().nodes().get("verification");
        flow.visits.put("verification", verification.maxVisits());
        assertThat(text()).contains("請它重驗").contains("核准人工修復重驗 <驗收節點> <commit>");
    }

    @Test void remediationIsNotOfferedWithoutAWorkspace() {
        var verification = flow.snapshot.definition().nodes().get("verification");
        flow.visits.put("verification", verification.maxVisits());
        assertThat(text()).doesNotContain("請它重驗");
    }

    @Test void offersPlanApprovalWhenTheCurrentNodeStillNeedsIt() {
        flow.node = "verification";
        assertThat(text()).contains("核准測試計畫");
    }

    @Test void saysNothingWhenNoOptionIsAvailable() {
        flow.node = "triage";
        assertThat(HumanOptions.render(task, flow, false, false, null)).isEmpty();
    }

    // ---- 按鈕 ----

    @Test void extensionOptionCarriesTheTokenSoNobodyHasToRetypeIt() {
        String token = "a".repeat(64);
        var options = HumanOptions.options(task, flow, true, true, token);

        var extend = options.stream().filter(o -> HumanOptions.EXTEND.equals(o.kind())).findFirst().orElseThrow();
        assertThat(extend.button()).isTrue();
        assertThat(extend.command()).isEqualTo("核准延長自動交接 " + token);
        assertThat(HumanOptions.render(task, flow, true, true, token)).contains("再給系統兩次機會").contains(token);
    }

    @Test void noExtensionOptionWithoutAToken() {
        assertThat(HumanOptions.options(task, flow, true, true, null))
                .noneMatch(o -> HumanOptions.EXTEND.equals(o.kind()));
        assertThat(HumanOptions.options(task, flow, true, true, "  "))
                .noneMatch(o -> HumanOptions.EXTEND.equals(o.kind()));
    }

    @Test void freeTextOptionsAreNeverTurnedIntoButtons() {
        flow.changeTarget = "intake";
        flow.workspaceReady = true;
        flow.visits.put("verification", flow.snapshot.definition().nodes().get("verification").maxVisits());

        assertThat(HumanOptions.options(task, flow, true, true, "t".repeat(64)))
                .filteredOn(o -> java.util.Set.of(HumanOptions.REQUIREMENT, HumanOptions.REMEDIATE,
                        HumanOptions.PLAN, HumanOptions.SUPPLEMENT).contains(o.kind()))
                .isNotEmpty()
                .allSatisfy(o -> assertThat(o.button()).isFalse());
    }

    @Test void readOnlyReportOptionIsAButton() {
        var report = HumanOptions.options(task, flow, true, true, null).stream()
                .filter(o -> HumanOptions.REPORT.equals(o.kind())).findFirst().orElseThrow();
        assertThat(report.button()).isTrue();
        assertThat(report.command()).isEqualTo("檢視完整報告");
    }
}
