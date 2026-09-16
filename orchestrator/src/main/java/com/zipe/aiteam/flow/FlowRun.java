package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** 每次狀態變更整份保存；快照不隨磁碟上配置更動。 */
public class FlowRun {
    public TeamDefinition.Snapshot snapshot;
    public String node;
    public int transitions;
    public boolean workspaceReady;
    public String workspacePath;
    public String context = "";
    public String lastBlockReason;
    /** 目前受阻鏈的證據摘要；只允許配置內既有節點使用有界自動交接。 */
    public String automaticHandoff;
    /** 任務生命週期內已使用的自動交接額度，不因新錯誤文字或 commit 重設。 */
    public int automaticHandoffs;
    /** 真人針對當前受阻證據核准的額外事件額度；不重設既有事件或節點計數。 */
    public int automaticHandoffExtraBudget;
    /** 同一受阻事件已通過的節點數；另設上限，避免同一證據在節點間無限循環。 */
    public int automaticHandoffHops;
    public Set<String> automaticHandoffKeys = new LinkedHashSet<>();
    /** 已使用的額度延長授權 token，永久保留以防相同現場重放。 */
    public Set<String> automaticHandoffExtensionKeys = new LinkedHashSet<>();
    public Map<String,String> recoveryGrants = new LinkedHashMap<>();
    public Set<String> recoveryRevisions = new LinkedHashSet<>();
    /** 人工修復後的重驗授權綁定節點與實際 commit；已使用 key 永久保留以防重放。 */
    public Map<String,String> remediationRecoveryGrants = new LinkedHashMap<>();
    public Set<String> remediationRecoveryKeys = new LinkedHashSet<>();
    public String approvalHead;
    public String origin;
    public String branch;
    public String beforeHead;
    public String activeNode;
    public boolean dialogue;
    public String dialogueRole;
    public String parentId;
    /** 用完自動處理次數後，由負責人承接成的新任務編號；有值時本任務不再接受任何操作。 */
    public String successor;
    /** 本任務承接自哪個任務；工作區從該任務的分支 HEAD 開始。 */
    public String continuationOf;
    public String acceptanceOwner;
    public String baselineRevision;
    public String changeTarget;
    public String pendingPlanRevision;
    public boolean changeAuthorized;
    public String prUrl;
    public String publishedHead;
    public IndependentVerifier.Receipt independentEvidence;
    public List<JsonNode> baselineHistory = new ArrayList<>();
    public Map<String,Integer> visits = new LinkedHashMap<>();
    public Map<String,JsonNode> receipts = new LinkedHashMap<>();
    /**
     * 尚未解除的受阻發現，以節點 ID 為鍵（因此筆數受配置節點數限制，不會無限成長）。
     * 受阻報告不會寫入 receipts，而 context 有長度上限會淘汰舊段落；
     * 這份紀錄是唯一保證送達下游節點的開放缺陷來源，該節點自己通過後才移除。
     */
    public Map<String,JsonNode> blockedFindings = new LinkedHashMap<>();
    public Map<String,String> heads = new LinkedHashMap<>();
    /** 各 workspace 節點最近一次真正開始執行時的 HEAD，供人工修復重驗比對。 */
    public Map<String,String> attemptHeads = new LinkedHashMap<>();
    public Map<String,OperationPreflight.Evidence> executionContexts = new LinkedHashMap<>();
    public Map<String,PlanApproval> planApprovals = new LinkedHashMap<>();
    public List<TeamDefinition.Snapshot> migrationHistory = new ArrayList<>();
    public FlowRun() {}
    public FlowRun(TeamDefinition.Snapshot snapshot) { this.snapshot = snapshot; this.node = snapshot.definition().entry(); }
}
