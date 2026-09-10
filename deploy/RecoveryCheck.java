import com.fasterxml.jackson.databind.ObjectMapper;
import com.zipe.aiteam.flow.*;
import com.zipe.aiteam.slack.PlanReview;
import java.nio.file.*;

/** 離線檢查受控恢復快照與產生完整 Slack 計畫；不呼叫模型、不修改 DB。 */
class RecoveryCheck {
    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper();
        FlowRun flow = mapper.readValue(Files.readString(Path.of(args[0])), FlowRun.class);
        if (!flow.node.equals("verification") || flow.activeNode != null || flow.visits.get("triage") != 3
                || !flow.planApprovals.isEmpty() || !flow.snapshot.definition().roles().get("tester").runtime().equals("codex"))
            throw new IllegalStateException("恢復狀態不符");
        String invalid = Traceability.planReason(flow.receipts.get("plan"), flow);
        if (invalid != null) throw new IllegalStateException(invalid);
        if (PlanApproval.blockingReason(flow, "plan") == null) throw new IllegalStateException("未核准仍須停止");
        // 保留前置契約有效，核准後不需要重做 implementation。
        for (String id : flow.snapshot.definition().nodes().get("verification").requires()) {
            invalid = FlowGates.blockingReason(flow.snapshot.definition().nodes().get(id), flow.receipts.get(id), flow);
            if (invalid != null) throw new IllegalStateException(id + ": " + invalid);
        }
        var pages = PlanReview.pages("1014", "plan", flow.receipts.get("plan"));
        Path output = Path.of(args[1]); Files.createDirectories(output);
        for (int i = 0; i < pages.size(); i++) Files.writeString(output.resolve(String.format("%02d.txt", i)), pages.get(i));
        System.out.println("PASS recovery; pages=" + pages.size() + "; plan_revision=" + Traceability.digest(flow.receipts.get("plan")));
    }
}
