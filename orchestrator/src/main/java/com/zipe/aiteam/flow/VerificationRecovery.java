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
        flow.remediationRecoveryGrants.clear();
        flow.node=nodeId;flow.activeNode=null;
        // 即使已有新版本核准，仍呈現計畫，要求此次恢復再核准後才派工。
        flow.planApprovals.clear();
    }

    /**
     * 原配置的恢復已用完後，允許負責人針對「新的、乾淨的修復 commit」再授權一次重驗。
     * 不清除 visit／transition；同一配置、節點與 commit 永遠只能使用一次。
     */
    public static void authorizeRemediation(FlowRun flow, String nodeId, String head, String user) {
        if (!Objects.equals(user,flow.acceptanceOwner) || flow.activeNode!=null || flow.dialogue
                || flow.changeTarget!=null || flow.pendingPlanRevision!=null)
            throw new IllegalArgumentException("恢復負責人或等待狀態不符");
        Node node=flow.snapshot.definition().nodes().get(nodeId);
        if(node==null || node.gate()!=Gate.VERIFICATION || !flow.snapshot.definition().project().requirePlanApproval())
            throw new IllegalArgumentException("只允許恢復需真人計畫核准的驗收節點");
        if(flow.visits.getOrDefault(nodeId,0)<node.maxVisits())
            throw new IllegalArgumentException("驗收節點尚未達重跑上限，請使用一般續跑");
        if(flow.transitions>=flow.snapshot.definition().maxTransitions())
            throw new IllegalArgumentException("流程總上限已用盡，不授予恢復");
        if(head==null || head.isBlank()) throw new IllegalArgumentException("缺少人工修復 commit");
        String previous=flow.attemptHeads.get(nodeId);
        // 舊快照沒有 attemptHeads；beforeHead 是最近一次 workspace 節點啟動時留下的現場。
        if(previous==null) previous=flow.beforeHead;
        if(previous==null && node.requires()!=null) {
            for(String dep:node.requires()) if(flow.heads.get(dep)!=null) previous=flow.heads.get(dep);
        }
        if(previous==null) throw new IllegalArgumentException("缺少前次驗收 commit，不能核實人工修復");
        if(Objects.equals(previous,head)) throw new IllegalArgumentException("工作區沒有新的修復 commit，拒絕重驗");
        String key=remediationKey(flow,nodeId,head);
        if(flow.remediationRecoveryKeys.contains(key))
            throw new IllegalArgumentException("此人工修復 commit 的一次重驗授權已使用");
        for(String dep:node.requires()) {
            String block=FlowGates.blockingReason(flow.snapshot.definition().nodes().get(dep),flow.receipts.get(dep),flow);
            if(block!=null)throw new IllegalArgumentException("前置契約 "+dep+" 無效："+block);
        }
        flow.remediationRecoveryKeys.add(key);
        flow.recoveryGrants.clear();flow.recoveryGrants.put(nodeId,flow.snapshot.revision());
        flow.remediationRecoveryGrants.clear();flow.remediationRecoveryGrants.put(nodeId,head);
        flow.node=nodeId;flow.activeNode=null;
        flow.planApprovals.clear();
    }

    static String remediationKey(FlowRun flow,String nodeId,String head) {
        return flow.snapshot.revision()+":"+nodeId+":"+head;
    }
    public static boolean permitted(FlowRun flow,String node) {
        return flow.snapshot.revision().equals(flow.recoveryGrants.get(node));
    }
}
