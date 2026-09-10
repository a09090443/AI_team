package com.zipe.aiteam.flow;

import java.util.Objects;
import static com.zipe.aiteam.flow.TeamDefinition.*;

/** 能力配置經真人核准後，另一個明確指令授予一次驗收；不重設歷史計數。 */
public final class VerificationRecovery {
    public static void authorize(FlowRun flow, String nodeId, String revision, String user) {
        if (!Objects.equals(user,flow.acceptanceOwner) || !Objects.equals(revision,flow.snapshot.revision())
                || flow.activeNode!=null || flow.dialogue || flow.changeTarget!=null || flow.pendingPlanRevision!=null)
            throw new IllegalArgumentException("恢復負責人、配置版本或等待狀態不符");
        if(flow.migrationHistory.isEmpty() || flow.recoveryRevisions.contains(revision))
            throw new IllegalArgumentException("此配置尚未核准遷移，或一次恢復授權已使用");
        var preview=TeamMigration.preview(flow.migrationHistory.getLast(),flow.snapshot);
        if(preview.changes().isEmpty())throw new IllegalArgumentException("配置未修正任何能力");
        Node node=flow.snapshot.definition().nodes().get(nodeId);
        if(node==null || node.gate()!=Gate.VERIFICATION || !flow.snapshot.definition().project().requirePlanApproval())
            throw new IllegalArgumentException("只允許恢復需真人計畫核准的驗收節點");
        if(flow.transitions>=flow.snapshot.definition().maxTransitions())
            throw new IllegalArgumentException("流程總上限已用盡，不授予恢復");
        for(String dep:node.requires()) {
            String block=FlowGates.blockingReason(flow.snapshot.definition().nodes().get(dep),flow.receipts.get(dep),flow);
            if(block!=null)throw new IllegalArgumentException("前置契約 "+dep+" 無效："+block);
        }
        flow.recoveryRevisions.add(revision);
        flow.recoveryGrants.clear();flow.recoveryGrants.put(nodeId,revision);
        flow.node=nodeId;flow.activeNode=null;
        // 即使已有新版本核准，仍呈現計畫，要求此次恢復再核准後才派工。
        flow.planApprovals.clear();
    }
    public static boolean permitted(FlowRun flow,String node) {
        return flow.snapshot.revision().equals(flow.recoveryGrants.get(node));
    }
}
